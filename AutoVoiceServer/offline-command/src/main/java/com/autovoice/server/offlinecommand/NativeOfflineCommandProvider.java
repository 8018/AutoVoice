package com.autovoice.server.offlinecommand;

import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.SessionContext;

import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 讯飞离线命令词识别（Linux x86-64 原生 SDK + JNI 桥，S6 的 {@code native/autovoice_offline_esr.cpp}）。
 *
 * <p>关键性质：</p>
 * <ul>
 *   <li><b>懒加载</b>：构造与类加载都不触碰 .so（不 {@code System.load}、不联网激活）——
 *       Mac 上可安全构造、可测降级路径；首次 {@link #recognize} 才加载并初始化；</li>
 *   <li><b>单线程串行</b>：所有 native 调用经同一单线程执行器（SDK 引擎非线程安全）；</li>
 *   <li><b>30s 调用超时</b>：native 卡死时 future 超时完成空结果，不阻塞调用方；</li>
 *   <li><b>失败 → 空结果</b>：初始化失败 / 识别失败 / 空结果一律 {@link Optional#empty()}（等同未命中），
 *       绝不向上抛异常、绝不崩服务；</li>
 *   <li><b>GBK</b>：FSA 词表与识别结果均为 GBK，native 返回原始字节，Java 侧
 *       {@code new String(bytes, Charset.forName("GBK")).trim()} 解码。</li>
 * </ul>
 *
 * <p>凭据语义（S6 部署）：联网激活（authType=0）使用讯飞 appId/apiKey/apiSecret（与在线听写同一套
 * 环境变量，不入库不打印）；licenseFile 非空时走 license 离线授权（authType=1）。</p>
 */
public final class NativeOfflineCommandProvider implements OfflineCommandProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NativeOfflineCommandProvider.class);
    private static final Charset GBK = Charset.forName("GBK");
    private static final long RECOGNIZE_TIMEOUT_MS = 30_000;

    private final String libPath;
    private final String resourceDir;
    private final String workDir;
    private final String fsaPath;
    private final String licenseFile;
    private final String appId;
    private final String apiKey;
    private final String apiSecret;

    /** 单线程串行：SDK 引擎非线程安全，所有 native 调用排队执行。 */
    private final ExecutorService engine = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "offline-engine");
        t.setDaemon(true);
        return t;
    });

    private volatile long handle; // 0 = 未初始化（nativeInit 返回的引擎句柄）
    private volatile boolean initFailed;

    /**
     * @param libPath      JNI 桥 .so 绝对路径（S6 的 libautovoice_offline_esr.so）
     * @param resourceDir  讯飞 SDK 资源目录（SDK/…/resource，含 aiengine 等）
     * @param workDir      可写工作目录（SDK 日志 aikit/aeeLog.txt 落点）
     * @param fsaPath      FSA 词表 cn_fsa.txt 绝对路径（GBK 编码）
     * @param licenseFile  license 授权文件绝对路径；空字符串/null → 联网激活（authType=0）
     * @param appId        讯飞 appId（联网激活凭据，不打印）
     * @param apiKey       讯飞 apiKey（联网激活凭据，不打印）
     * @param apiSecret    讯飞 apiSecret（联网激活凭据，不打印）
     */
    public NativeOfflineCommandProvider(String libPath, String resourceDir, String workDir, String fsaPath,
                                        String licenseFile, String appId, String apiKey, String apiSecret) {
        this.libPath = libPath;
        this.resourceDir = resourceDir;
        this.workDir = workDir;
        this.fsaPath = fsaPath;
        this.licenseFile = licenseFile;
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    @Override
    public CompletableFuture<Optional<String>> recognize(byte[] pcm16k, SessionContext ctx) {
        CompletableFuture<Optional<String>> out = new CompletableFuture<>();
        engine.execute(() -> {
            long h;
            try {
                h = ensureInitialized();
            } catch (Throwable t) {
                LOG.error("offline engine init failed: {}", String.valueOf(t.getMessage()));
                out.complete(Optional.empty());
                return;
            }
            if (h == 0) {
                out.complete(Optional.empty());
                return;
            }
            try {
                byte[] raw = nativeRecognize(h, pcm16k);
                if (raw == null || raw.length == 0) {
                    LOG.info("Offline no result");
                    out.complete(Optional.empty());
                    return;
                }
                String text = new String(raw, GBK).trim();
                if (text.isEmpty()) {
                    LOG.info("Offline no result (blank)");
                    out.complete(Optional.empty());
                    return;
                }
                LOG.info("Offline ASR ok: \"{}\"", text);
                out.complete(Optional.of(text));
            } catch (Throwable t) {
                LOG.error("Offline ASR failed: {}", String.valueOf(t.getMessage()));
                out.complete(Optional.empty()); // 失败 → 空结果（等同未命中），绝不崩服务
            }
        });
        // 30s 调用超时兜底：native 卡死时 future 按时完成空结果，调用方不被阻塞
        out.orTimeout(RECOGNIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return out.handle((r, e) -> e != null ? Optional.<String>empty() : r);
    }

    /**
     * 懒加载 + 单次初始化：{@code System.load} 桥 .so → nativeInit（联网激活或 license 授权）→
     * 返回引擎句柄。初始化失败记日志并缓存失败态（后续调用直接返回 0，不再重试）。
     */
    private long ensureInitialized() {
        long h = handle;
        if (h != 0 || initFailed) {
            return h;
        }
        synchronized (this) {
            if (handle != 0) {
                return handle;
            }
            if (initFailed) {
                return 0;
            }
            try {
                System.load(libPath);
                long init = nativeInit(appId, apiKey, apiSecret, workDir, resourceDir, fsaPath, licenseFile);
                if (init == 0) {
                    initFailed = true;
                    LOG.error("Offline SDK init failed: {} (see {}/aikit/engineLog.txt)",
                            lastError(), workDir);
                    return 0;
                }
                handle = init;
                LOG.info("Offline SDK init ok (license: {})",
                        licenseFile != null && !licenseFile.isBlank() ? "file" : "online-activation");
                return init;
            } catch (Throwable t) {
                initFailed = true;
                LOG.error("Offline SDK init failed (System.load or native init): {}",
                        String.valueOf(t.getMessage()));
                return 0;
            }
        }
    }

    // ---- JNI（native/autovoice_offline_esr.cpp，S6） ----

    /** 初始化引擎；返回句柄（0 = 失败）。appId/apiKey/apiSecret 仅用于联网激活（licenseFile 为空时）。 */
    private static native long nativeInit(String appId, String apiKey, String apiSecret,
                                          String workDir, String resourceDir, String fsaPath,
                                          String licenseFile);

    /** 识别一段 PCM（S16LE/16kHz/单声道）；返回 GBK 原始字节（识别文本），无结果返回 null。 */
    private static native byte[] nativeRecognize(long handle, byte[] pcm);

    /** 最近一次 native 错误描述（init 失败排查）。 */
    private static native String lastError();
}
