package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        svc.record("utt-old", TelemetryStages.UTTERANCE_START, "info", Map.of());
        svc.saveAudio("utt-old", new byte[1600]);
        // 推进时钟 8 天（retention 7）：created_ms=插入时刻 < cutoff，应被清理
        clock.addAndGet(8L * 86400000L);
        svc.cleanupOld();
        assertNull(svc.queryRound("utt-old"));
        assertFalse(Files.exists(tmp.resolve("audio/utt-old.wav")));
    }
}
