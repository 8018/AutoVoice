package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTS 缓存装饰器测试：命中/未命中委托计数、磁盘写穿与冷启动加载、
 * 损坏文件回源、非 audio 回复不缓存、空文本不缓存。
 */
class CachedTtsProviderTest {

    private static final SessionContext CTX = new SessionContext("s1", "zh-CN", Map.of());
    private static final byte[] WAV = {0x52, 0x49, 0x46, 0x46, 0x01, 0x02, 0x03};

    private static TtsProvider countingDelegate(AtomicInteger calls) {
        return (text, ctx) -> {
            calls.incrementAndGet();
            return Reply.ofAudio("audio/wav", WAV);
        };
    }

    @Test
    void missSynthesizesThenSecondCallHitsMemory() {
        AtomicInteger calls = new AtomicInteger();
        CachedTtsProvider p = new CachedTtsProvider(countingDelegate(calls));

        Reply first = p.synthesize("打开空调", CTX);
        assertEquals("audio", first.kind());
        assertArrayEquals(WAV, first.data());

        Reply second = p.synthesize("打开空调", CTX);
        assertArrayEquals(WAV, second.data());
        assertEquals(1, calls.get(), "第二次调用应命中缓存，不再委托底层");
    }

    @Test
    void distinctTextsMissEach() {
        AtomicInteger calls = new AtomicInteger();
        CachedTtsProvider p = new CachedTtsProvider(countingDelegate(calls));
        p.synthesize("打开空调", CTX);
        p.synthesize("好的，空调已打开", CTX);
        assertEquals(2, calls.get());
    }

    @Test
    void diskCacheWrittenOnMissAndLoadedOnColdStart(@TempDir Path dir) {
        AtomicInteger calls = new AtomicInteger();
        CachedTtsProvider warm = new CachedTtsProvider(countingDelegate(calls), dir);
        Reply first = warm.synthesize("打开空调", CTX);
        assertArrayEquals(WAV, first.data());
        assertEquals(1, calls.get());

        // 磁盘写穿：cacheDir 出现 sha256(text).hex.wav
        try (var stream = Files.list(dir)) {
            assertEquals(1, stream.count(), "miss 后应写穿磁盘缓存");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        // 冷启动（新实例）：磁盘命中，不再委托
        CachedTtsProvider cold = new CachedTtsProvider(countingDelegate(calls), dir);
        Reply second = cold.synthesize("打开空调", CTX);
        assertArrayEquals(WAV, second.data());
        assertEquals(1, calls.get(), "冷启动应从磁盘加载缓存，不再委托底层");
    }

    @Test
    void corruptDiskFileReSynthesizes(@TempDir Path dir) {
        AtomicInteger calls = new AtomicInteger();
        CachedTtsProvider p = new CachedTtsProvider(countingDelegate(calls), dir);
        p.synthesize("打开空调", CTX);
        assertEquals(1, calls.get());

        // 损坏磁盘文件（空文件）→ 视为未命中，重新合成
        try (var stream = Files.list(dir)) {
            Path file = stream.findFirst().orElseThrow();
            Files.write(file, new byte[0]);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        CachedTtsProvider fresh = new CachedTtsProvider(countingDelegate(calls), dir);
        Reply again = fresh.synthesize("打开空调", CTX);
        assertArrayEquals(WAV, again.data());
        assertEquals(2, calls.get(), "损坏文件应回源重新合成");
    }

    @Test
    void nonAudioReplyPassesThroughWithoutCaching() {
        AtomicInteger calls = new AtomicInteger();
        TtsProvider textDelegate = (text, ctx) -> {
            calls.incrementAndGet();
            return Reply.ofText("nope");
        };
        CachedTtsProvider p = new CachedTtsProvider(textDelegate);
        assertEquals("text", p.synthesize("x", CTX).kind());
        assertEquals("text", p.synthesize("x", CTX).kind());
        assertEquals(2, calls.get(), "非 audio 回复不缓存");
    }

    @Test
    void blankTextDelegatesWithoutCaching() {
        AtomicInteger calls = new AtomicInteger();
        CachedTtsProvider p = new CachedTtsProvider(countingDelegate(calls));
        p.synthesize("  ", CTX);
        p.synthesize("", CTX);
        assertEquals(2, calls.get(), "空文本不缓存，直接委托底层");
        assertTrue(calls.get() > 0);
    }

    @Test
    void recordsCacheHitAndMissEvents() {
        AtomicInteger calls = new AtomicInteger();
        RecordingRecorder rec = new RecordingRecorder();
        CachedTtsProvider p = new CachedTtsProvider(countingDelegate(calls), null, rec);

        Reply first = p.synthesize("打开空调", CTX, "utt-1"); // MISS → 合成并写缓存
        assertArrayEquals(WAV, first.data());
        Reply second = p.synthesize("打开空调", CTX, "utt-2"); // HIT → 回放缓存

        assertEquals(2, rec.events.size(), "MISS 与 HIT 各记一条 tts_cache");
        assertEquals("utt-1", rec.utteranceIds.get(0));
        assertEquals("utt-2", rec.utteranceIds.get(1));

        var miss = rec.events.get(0);
        assertEquals(TelemetryStages.TTS_CACHE, miss.stage());
        assertEquals("info", miss.level());
        assertEquals(Boolean.FALSE, miss.payload().get("hit"));
        assertEquals(WAV.length, miss.payload().get("bytes"));

        var hit = rec.events.get(1);
        assertEquals(Boolean.TRUE, hit.payload().get("hit"));
        assertEquals(WAV.length, hit.payload().get("bytes"));
    }

    @Test
    void recordsErrorWhenDelegateFails() {
        RecordingRecorder rec = new RecordingRecorder();
        TtsProvider failing = (text, ctx) -> {
            throw new RuntimeException("aliyun down");
        };
        CachedTtsProvider p = new CachedTtsProvider(failing, null, rec);

        assertThrows(RuntimeException.class, () -> p.synthesize("打开空调", CTX, "utt-3"));

        assertEquals(1, rec.events.size());
        assertEquals(TelemetryStages.TTS_CACHE, rec.events.get(0).stage());
        assertEquals("error", rec.events.get(0).level());
        assertEquals("utt-3", rec.utteranceIds.get(0));
    }
}
