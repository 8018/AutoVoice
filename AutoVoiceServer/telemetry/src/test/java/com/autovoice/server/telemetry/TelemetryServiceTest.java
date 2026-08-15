package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryServiceTest {

    @TempDir
    Path tmp;

    private TelemetryService newService() {
        return new TelemetryService(new TelemetryProperties(true,
                tmp.resolve("t.db").toString(), tmp.resolve("audio").toString(), 7));
    }

    @Test
    void recordsEventsAndQueriesRound() {
        TelemetryService svc = newService();
        svc.record("utt-1", TelemetryStages.UTTERANCE_START, "info",
                Map.of("source", "button"));
        svc.record("utt-1", TelemetryStages.CLOUD_ASR, "info", Map.of("text", "空调调到二十四度"));
        svc.recordDeviceRound(new TelemetryService.DeviceRoundPayload("utt-1", "s1", "demo-1",
                "button", 1000L, 5000L,
                List.of(new TelemetryEvent(TelemetryStages.CLOUD_ARBITER, 4000L, "info",
                        Map.of("route", "cloud", "reason", "cloud_won")))));
        var round = svc.queryRound("utt-1");
        assertNotNull(round);
        assertEquals("demo-1", round.deviceId());
        assertEquals(3, round.events().size());
        // 聚合字段由 events 推导：cloud_arbiter.route → cloud_decision
        assertEquals("cloud", round.cloudDecision());
    }

    @Test
    void recordDerivesAggregatesForCloudOnlyRounds() {
        // Task 4 服务端插桩：纯 record() 路径（端侧不上报 /round）也要推导聚合列——
        // cloud_arbiter→cloud_decision、llm→llm_reply、cloud_asr→asr_cloud
        TelemetryService svc = newService();
        svc.record("utt-cloud", TelemetryStages.CLOUD_ASR, "info", Map.of("text", "把空调调到二十四度"));
        svc.record("utt-cloud", TelemetryStages.LLM, "info", Map.of("text", "好的，空调温度已调到24度"));
        svc.record("utt-cloud", TelemetryStages.CLOUD_ARBITER, "info",
                Map.of("route", "cloud", "reason", "llm_reply"));
        var round = svc.queryRound("utt-cloud");
        assertNotNull(round);
        assertEquals("cloud", round.cloudDecision());
        assertEquals(3, round.events().size());
    }

    @Test
    void deviceArbiterFinalDecisionPrefersReasonOverRoute() {
        // 面板按 final_decision 含 "failed" 判定失败：reason=both_failed → 标红；
        // reason 缺失时回退 route（cloud/local，不会误标红）
        TelemetryService svc = newService();
        svc.recordDeviceRound(new TelemetryService.DeviceRoundPayload("utt-dev-1", "s1", "demo-1",
                "button", 1000L, 5000L,
                List.of(new TelemetryEvent(TelemetryStages.DEVICE_ARBITER, 3000L, "info",
                        Map.of("route", "local", "reason", "both_failed")))));
        assertEquals("both_failed", svc.queryRound("utt-dev-1").finalDecision());

        TelemetryService svc2 = newService();
        svc2.recordDeviceRound(new TelemetryService.DeviceRoundPayload("utt-dev-2", "s1", "demo-1",
                "button", 1000L, 5000L,
                List.of(new TelemetryEvent(TelemetryStages.DEVICE_ARBITER, 3000L, "info",
                        Map.of("route", "cloud")))));
        assertEquals("cloud", svc2.queryRound("utt-dev-2").finalDecision());
    }

    @Test
    void savesWavFile() throws Exception {
        TelemetryService svc = newService();
        byte[] pcm = new byte[3200];
        svc.saveAudio("utt-2", pcm);
        byte[] wav = Files.readAllBytes(tmp.resolve("audio/utt-2.wav"));
        assertEquals(44 + 3200, wav.length);
        // 44 字节标准头（clarification #2）：RIFF 魔数在 0-3，WAVE 标记在 8-11
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        assertEquals('W', wav[8]);
        assertEquals('A', wav[9]);
        assertEquals('V', wav[10]);
        assertEquals('E', wav[11]);
        // review finding 3：audio_path 存完整文件名（含 .wav），readAudio 原样 resolve
        assertEquals("utt-2.wav", svc.queryRound("utt-2").audioPath());
        assertEquals(44 + 3200, svc.readAudio("utt-2.wav").orElseThrow().length);
        // 无扩展名/缺失/根路径（finding 4：不 500，返回 empty）
        assertTrue(svc.readAudio("utt-2").isEmpty());
        assertTrue(svc.readAudio("no-such.wav").isEmpty());
        assertTrue(svc.readAudio("/").isEmpty());
        assertTrue(svc.readAudio("../utt-2.wav").isEmpty());
    }

    @Test
    void cleanupDeletesOldRoundsAndAudio() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        TelemetryService svc = new TelemetryService(
                new TelemetryProperties(true, tmp.resolve("t.db").toString(),
                        tmp.resolve("audio").toString(), 7), clock::get);
        // 先用 listener 确定性阻塞 writer，模拟 CI 负载下 record 排队的情况。created_ms
        // 必须取 record 调用时刻，而不能等 writer 恢复后才取已经推进 8 天的时钟。
        CountDownLatch writerBlocked = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        svc.addListener(summary -> {
            if ("utt-blocker".equals(summary.utteranceId())) {
                writerBlocked.countDown();
                try {
                    releaseWriter.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        svc.recordDeviceRound(new TelemetryService.DeviceRoundPayload(
                "utt-blocker", "s1", "demo", "test", 1L, 2L, List.of()));
        assertTrue(writerBlocked.await(5, TimeUnit.SECONDS));

        svc.record("utt-old", TelemetryStages.UTTERANCE_START, "info", Map.of());
        svc.saveAudio("utt-old", new byte[1600]);
        // 推进时钟 8 天（retention 7）：created_ms=插入时刻 < cutoff，应被清理
        clock.addAndGet(8L * 86400000L);
        svc.cleanupOld();
        releaseWriter.countDown();
        assertNull(svc.queryRound("utt-old"));
        assertFalse(Files.exists(tmp.resolve("audio/utt-old.wav")));
    }
}
