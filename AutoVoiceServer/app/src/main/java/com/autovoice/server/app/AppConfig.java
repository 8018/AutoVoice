package com.autovoice.server.app;

import com.autovoice.server.asrgateway.AliyunAsrProvider;
import com.autovoice.server.asrgateway.AliyunTokenClient;
import com.autovoice.server.asrgateway.IflytekIatAsrProvider;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.gateway.VoiceGatewayHandler;
import com.autovoice.server.llm.DeepSeekLlmProvider;
import com.autovoice.server.offlinecommand.NativeOfflineCommandProvider;
import com.autovoice.server.offlinecommand.NoopOfflineCommandProvider;
import com.autovoice.server.offlinecommand.OfflineCommandService;
import com.autovoice.server.offlinecommand.OfflineEnginePool;
import com.autovoice.server.session.SessionRegistry;
import com.autovoice.server.ttsgateway.AliyunTtsProvider;
import com.autovoice.server.ttsgateway.CachedTtsProvider;
import okhttp3.OkHttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 云端 app 装配：按 {@code autovoice.*} 配置选择 provider 实现并注入
 * {@link VoiceGatewayHandler}（每连接 pipeline + RaceArbiter 由 handler 内部构建）。
 *
 * <p>provider 选择（application.yml {@code autovoice.providers.*}）：llm=deepseek
 * （语义由 LLM function calling 承担，原 NLU 链路已随讯飞 AIUI 下线退役）、asr 支持
 * {@code iflytek}（讯飞在线听写，默认）与 {@code aliyun}（一句话识别）、tts=aliyun。
 * secrets 全部来自环境变量占位符，无 env 也能启动（provider 调用时才失败）。</p>
 */
@Configuration
@EnableConfigurationProperties(AppConfig.AutovoiceProperties.class)
public class AppConfig {

    /** {@code autovoice.*} 配置（constructor binding）。 */
    @ConfigurationProperties(prefix = "autovoice")
    public record AutovoiceProperties(Arbitration arbitration, Providers providers, Secrets secrets,
                                      Offline offline, Tts tts, Gateway gateway) {

        /** 配置缺省时（yml 未配 autovoice.gateway.*）：鉴权关、设备表空、连接上限 32。 */
        public AutovoiceProperties {
            gateway = gateway == null ? new Gateway(false, Map.of(), 32) : gateway;
        }

        public record Arbitration(long safetyTimeoutMs, long offlineGraceMs) {
        }

        public record Providers(String llm, String asr, String tts) {
        }

        /** secrets 全部 ${VAR:} 空默认：不写入任何 secret 字面值。 */
        public record Secrets(String xfyunAppid, String xfyunApiKey, String xfyunApiSecret,
                              String deepseekApiKey, String aliyunAk, String aliyunSk,
                              String aliyunNlsAppkey, String dashscopeApiKey) {
        }

        /**
         * 离线命令词链路（S1 起加入）：默认关闭（Mac 本地跑老链路）；阿里云
         * 部署时 {@code AUTOVOICE_OFFLINE_ENABLED=true}。sdk 路径非 secret，随
         * application-demo-full.yml 固定于 /opt/autovoice/iflytek-offline/。
         *
         * <p>M3 多设备加固：{@code poolSize} 为离线引擎池大小（默认 2，clamp ≥1）——
         * 每个 worker 一个独立 NativeOfflineCommandProvider（各自 JNI 桥实例与串行队列），
         * 会话级 sticky 分配、引擎间并行，池满快速失败降级 ASR/LLM（见 OfflineEnginePool）。</p>
         */
        public record Offline(boolean enabled, long asrFailWaitMs, int poolSize, Sdk sdk) {

            public Offline {
                poolSize = poolSize < 1 ? 2 : poolSize;
            }

            public record Sdk(String libPath, String resourceDir, String workDir,
                              String fsaPath, String licenseFile) {
            }
        }

        /** TTS 播报链路：cacheDir 为空 → 仅内存缓存（本地默认）；部署时可指磁盘目录持久缓存。 */
        public record Tts(String cacheDir) {
        }

        /**
         * 接入网关策略（多设备加固 M1）：auth-enabled 默认 false（本地裸连兼容）；devices 为
         * {@code {deviceId: token}} 表（env 注入 JSON map，如
         * {@code AUTOVOICE_GATEWAY_AUTH_DEVICES={"demo-1":"..."}}，值不打印）；max-connections
         * 默认 32，超限新连接 close(4001)。
         */
        public record Gateway(boolean authEnabled, Map<String, String> authDevices, int maxConnections) {

            public Gateway {
                authDevices = authDevices == null ? Map.of() : authDevices;
                maxConnections = maxConnections < 1 ? 32 : maxConnections;
            }
        }
    }

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient();
    }

    /**
     * Tomcat JSR-356 容器缓冲：Tomcat 默认消息缓冲仅 8192 字节，整帧（Whole handler）
     * 超过即关连接（1009 buffer too small）。调大到 256KB：16KB 单帧 PCM 与
     * protocol.md §4.4 约定的一次下发的超大 base64 reply（64KB 音频 ≈ 87KB base64）都放得下。
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainerFactoryBean() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxTextMessageBufferSize(256 * 1024);
        factory.setMaxBinaryMessageBufferSize(256 * 1024);
        factory.setMaxSessionIdleTimeout(30 * 60 * 1000L);
        return factory;
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistry();
    }

    @Bean
    public LlmProvider llmProvider(OkHttpClient client, AutovoiceProperties props) {
        if (!"deepseek".equals(props.providers().llm())) {
            throw new IllegalArgumentException(
                    "unknown providers.llm: " + props.providers().llm() + " (deepseek)");
        }
        return new DeepSeekLlmProvider(client, props.secrets().deepseekApiKey(),
                DeepSeekLlmProvider.DEFAULT_ENDPOINT);
    }

    /** NLS token 接口的 AK/SK 以明文 query 出现（该 API 设计固有，URL 会进代理日志）。 */
    @Bean
    public AliyunTokenClient aliyunTokenClient(OkHttpClient client, AutovoiceProperties props) {
        return new AliyunTokenClient(client, props.secrets().aliyunAk(), props.secrets().aliyunSk());
    }

    /** 听写鉴权时间源（签名 date 字段）。 */
    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public AsrProvider asrProvider(OkHttpClient client, AliyunTokenClient tokenClient,
                                   java.time.Clock clock, AutovoiceProperties props) {
        return switch (props.providers().asr()) {
            case "iflytek" -> new IflytekIatAsrProvider(client, props.secrets().xfyunAppid(),
                    props.secrets().xfyunApiKey(), props.secrets().xfyunApiSecret(),
                    IflytekIatAsrProvider.DEFAULT_ENDPOINT, clock);
            case "aliyun" -> new AliyunAsrProvider(client, props.secrets().aliyunNlsAppkey(),
                    AliyunAsrProvider.DEFAULT_ENDPOINT, tokenClient::token);
            default -> throw new IllegalArgumentException(
                    "unknown providers.asr: " + props.providers().asr() + " (iflytek | aliyun)");
        };
    }

    @Bean
    public TtsProvider ttsProvider(OkHttpClient client, AutovoiceProperties props) {
        if (!"aliyun".equals(props.providers().tts())) {
            throw new IllegalArgumentException(
                    "unknown providers.tts: " + props.providers().tts() + " (aliyun)");
        }
        TtsProvider delegate = new AliyunTtsProvider(client, props.secrets().dashscopeApiKey(),
                AliyunTtsProvider.DEFAULT_ENDPOINT);
        String cacheDir = props.tts().cacheDir();
        if (cacheDir == null || cacheDir.isBlank()) {
            return new CachedTtsProvider(delegate); // 仅内存缓存
        }
        return new CachedTtsProvider(delegate, java.nio.file.Path.of(cacheDir));
    }

    /** 仅 Linux x86-64 上可用原生离线识别（JNI 桥 .so）；其余平台一律 Noop。 */
    private static boolean isLinuxX86_64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    /**
     * 离线命令链（可开关，默认关）：{@code offline.enabled=true} 且 Linux x86-64 →
     * Native SDK 引擎池（M3：{@code poolSize} 个 worker 各一个 NativeOfflineCommandProvider
     * 实例——JNI 桥能力级 init 进程内一次、识别互斥兜底，见 autovoice_offline_esr.cpp；
     * 会话级 sticky 路由 + 池满快速失败，见 OfflineEnginePool）；否则 Noop（与改造前行为
     * 一致）。构造异常降级为 Noop，绝不崩服务。凭据复用 XFYUN_APPID/API_KEY/API_SECRET
     * 环境变量，值不打印。
     */
    @Bean
    public OfflineCommandService offlineCommandService(AutovoiceProperties props) {
        if (!props.offline().enabled() || !isLinuxX86_64()) {
            return new OfflineCommandService(new NoopOfflineCommandProvider());
        }
        AutovoiceProperties.Offline.Sdk sdk = props.offline().sdk();
        int poolSize = props.offline().poolSize();
        try {
            List<OfflineCommandProvider> workers = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                workers.add(new NativeOfflineCommandProvider(
                        sdk.libPath(), sdk.resourceDir(), sdk.workDir(), sdk.fsaPath(), sdk.licenseFile(),
                        props.secrets().xfyunAppid(), props.secrets().xfyunApiKey(),
                        props.secrets().xfyunApiSecret()));
            }
            return new OfflineCommandService(new OfflineEnginePool(workers));
        } catch (Throwable t) {
            LOG.error("offline command init failed, degraded to Noop: {}", String.valueOf(t.getMessage()));
            return new OfflineCommandService(new NoopOfflineCommandProvider());
        }
    }

    /**
     * 网关 WS 处理器：仲裁参数（safety / offline 宽限期）、ASR 失败等离线窗口与接入策略
     * （鉴权/连接上限，M1）均来自配置；每连接的 RaceArbiter 复用 handler 内部 daemon 调度线程池（随 JVM 退出）。
     */
    @Bean
    public VoiceGatewayHandler voiceGatewayHandler(AsrProvider asr, LlmProvider llm,
                                                   TtsProvider tts, OfflineCommandService offline,
                                                   SessionRegistry registry,
                                                   AutovoiceProperties props) {
        AutovoiceProperties.Gateway g = props.gateway();
        return new VoiceGatewayHandler(asr, llm, tts, offline, registry,
                props.arbitration().safetyTimeoutMs(), props.offline().asrFailWaitMs(),
                props.arbitration().offlineGraceMs(), g.authEnabled(), g.authDevices(), g.maxConnections());
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AppConfig.class);
}
