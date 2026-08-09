package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TTS 缓存装饰器（TTS 解耦后的独立播报链路，设备按回复文本另发 tts_request）：
 * key = 文本，内存 {@link ConcurrentHashMap} + 磁盘写穿（{@code cacheDir/sha256(text).hex.wav}）。
 * 命中直接回放缓存（回 {@code audio/wav}），未命中委托底层 provider 合成并写穿缓存。
 *
 * <p>容错：磁盘文件缺失或空/损坏 → 视为未命中，重新合成并覆盖；写盘失败只记日志不中断
 * （内存缓存仍然生效）。空文本不缓存，直接委托底层（由底层拒绝）。</p>
 *
 * <p>缓存命中日志：{@code TTS cache HIT: "..." -> N bytes}；合成日志：
 * {@code TTS ok: "..." -> N bytes (cache MISS)}（阿里云部署后按日志确认缓存生效）。</p>
 */
public final class CachedTtsProvider implements TtsProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CachedTtsProvider.class);

    private final TtsProvider delegate;
    private final Path cacheDir;
    private final ConcurrentMap<String, byte[]> memory = new ConcurrentHashMap<>();

    /** 仅内存缓存（cacheDir = null，S5 配置缺省）。 */
    public CachedTtsProvider(TtsProvider delegate) {
        this(delegate, null);
    }

    /**
     * @param cacheDir 磁盘缓存目录；null 表示仅内存缓存
     */
    public CachedTtsProvider(TtsProvider delegate, Path cacheDir) {
        this.delegate = delegate;
        this.cacheDir = cacheDir;
    }

    @Override
    public Reply synthesize(String text, SessionContext ctx) {
        if (text == null || text.isBlank()) {
            return delegate.synthesize(text, ctx); // 空文本不缓存，直接委托
        }
        byte[] cached = memory.get(text);
        if (cached == null && cacheDir != null) {
            cached = readDisk(text);
            if (cached != null) {
                memory.putIfAbsent(text, cached);
            }
        }
        if (cached != null) {
            LOG.info("TTS cache HIT: \"{}\" -> {} bytes", text, cached.length);
            return Reply.ofAudio("audio/wav", cached);
        }
        Reply reply = delegate.synthesize(text, ctx);
        if ("audio".equals(reply.kind()) && reply.data() != null && reply.data().length > 0) {
            byte[] data = reply.data();
            memory.put(text, data);
            if (cacheDir != null) {
                writeDisk(text, data);
            }
            LOG.info("TTS ok: \"{}\" -> {} bytes (cache MISS)", text, data.length);
        }
        return reply;
    }

    /** 读磁盘缓存；文件缺失或损坏（空文件/读失败）→ null（视为未命中，重新合成）。 */
    private byte[] readDisk(String text) {
        Path file = cacheDir.resolve(keyFile(text));
        try {
            byte[] data = Files.readAllBytes(file);
            return data.length > 0 ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeDisk(String text, byte[] data) {
        try {
            Files.createDirectories(cacheDir);
            Files.write(cacheDir.resolve(keyFile(text)), data);
        } catch (IOException e) {
            LOG.warn("TTS cache write failed: {}", String.valueOf(e.getMessage()));
        }
    }

    private static String keyFile(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + ".wav";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // JDK 必带
        }
    }
}
