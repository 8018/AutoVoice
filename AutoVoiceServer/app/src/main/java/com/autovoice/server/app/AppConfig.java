package com.autovoice.server.app;

import com.autovoice.server.asrgateway.AliyunAsrProvider;
import com.autovoice.server.asrgateway.AliyunTokenClient;
import com.autovoice.server.asrgateway.IflytekIatAsrProvider;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OfflineCommandProvider;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.gateway.VoiceGatewayHandler;
import com.autovoice.server.llm.DeepSeekLlmProvider;
import com.autovoice.server.offlinecommand.NativeOfflineCommandProvider;
import com.autovoice.server.offlinecommand.NoopOfflineCommandProvider;
import com.autovoice.server.offlinecommand.OfflineCommandService;
import com.autovoice.server.offlinecommand.OfflineEnginePool;
import com.autovoice.server.session.SessionRegistry;
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.McpToolExecutor;
import com.autovoice.server.skillmcp.McpToolSession;
import com.autovoice.server.skillmcp.SkillPlatformClient;
import com.autovoice.server.skillmcp.ToolInjector;
import com.autovoice.server.skillmcp.ToolInjectors;
import com.autovoice.server.ttsgateway.RemoteTtsProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                                      Offline offline, Tts tts, Gateway gateway, SkillManager skillManager) {

        /** 配置缺省时（yml 未配 autovoice.gateway.*）：鉴权关、设备表空、连接上限 32；
         *  skill-manager 缺省：平台空白（MCP 工具不注入）、轮询 600s。 */
        public AutovoiceProperties {
            gateway = gateway == null ? new Gateway(false, "{}", 32) : gateway;
            skillManager = skillManager == null ? new SkillManager("", "", 600_000) : skillManager;
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

        /**
         * TTS 播报链路（M4 独立服务）：remoteUrl 指向 tts-server 的 /tts 端点，网关纯转发
         * （RemoteTtsProvider）；合成与缓存（CachedTtsProvider）归 TTS 服务侧，多实例 =
         * TTS_PORT 换端口部署。默认本机 8082（本地需同时起 tts-server；服务挂掉 → TTS_FAILED
         * → 端侧文本兜底，不崩识别链路）。
         */
        public record Tts(String remoteUrl) {

            public Tts {
                remoteUrl = remoteUrl == null || remoteUrl.isBlank()
                        ? "http://127.0.0.1:8082/tts" : remoteUrl;
            }
        }

        /**
         * 接入网关策略（多设备加固 M1）：auth-enabled 默认 false（本地裸连兼容）；devices 为
         * {@code {deviceId: token}} 表（env 注入 JSON 字符串，如
         * {@code AUTOVOICE_GATEWAY_AUTH_DEVICES={"demo-1":"..."}}，值不打印）。部署实测 Boot
         * 无 String→Map 转换器（ConverterNotFoundException），故组件按字符串接收、由
         * {@link #authDevicesMap()} 解析；max-connections 默认 32，超限新连接 close(4001)。
         */
        public record Gateway(boolean authEnabled, String authDevices, int maxConnections) {

            private static final ObjectMapper JSON = new ObjectMapper();

            public Gateway {
                authDevices = authDevices == null || authDevices.isBlank() ? "{}" : authDevices;
                maxConnections = maxConnections < 1 ? 32 : maxConnections;
            }

            /** 解析 {@code {deviceId: token}} 设备表；非法 JSON 快速失败（鉴权配置错误不应静默）。 */
            public Map<String, String> authDevicesMap() {
                try {
                    return JSON.readValue(authDevices, new TypeReference<Map<String, String>>() {});
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "AUTOVOICE_GATEWAY_AUTH_DEVICES 不是合法的 {deviceId: token} JSON map: " + authDevices, e);
                }
            }
        }

        /**
         * skill 平台接入（MCP 工具注入）：url 空白 → 平台未接入（SkillPlatformClient.isEnabled=false，
         * registry 快照恒空、不注入任何 MCP 工具）；serviceToken 同时用于拉取请求与 webhook
         * 端点鉴权（X-Skill-Service-Token）；pollMs 为 registry 定时兜底轮询周期（<1 视为 600s）。
         */
        public record SkillManager(String url, String serviceToken, long pollMs) {

            public SkillManager {
                url = url == null || url.isBlank() ? "" : url;
                serviceToken = serviceToken == null ? "" : serviceToken;
                pollMs = pollMs < 1 ? 600_000 : pollMs;
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

    /** skill 平台拉取客户端：url 空白 → 功能关闭（fetchEnabled 空表，MCP 工具不注入）。 */
    @Bean
    public SkillPlatformClient skillPlatformClient(OkHttpClient client, AutovoiceProperties props) {
        AutovoiceProperties.SkillManager sm = props.skillManager();
        return new SkillPlatformClient(client, sm.url(), sm.serviceToken());
    }

    /** 启用的 skill 会话快照：异步首拉 + 定时兜底轮询 + webhook 触发重拉（SkillRefreshController）。 */
    @Bean
    public McpSkillRegistry mcpSkillRegistry(SkillPlatformClient platformClient, AutovoiceProperties props) {
        // 注入策略按"当前启用工具总数"动态选择（≤8 direct / >8 direct+warn，selector 预留）
        ToolInjector dynamic = all -> ToolInjectors.forCount(all.size()).inject(all);
        McpSkillRegistry registry = new McpSkillRegistry(platformClient, dynamic,
                props.skillManager().pollMs(), 5_000,
                (cfg, timeout) -> {
                    try {
                        return McpToolSession.connect(cfg, timeout);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        registry.start();
        return registry;
    }

    /**
     * LLM：多轮工具循环（Task 9）+ MCP 工具合并——tools = car_control 默认 skill + registry
     * 启用快照（经注入策略），工具调用由 McpToolExecutor 按名路由回 registry（未知工具抛
     * McpToolException，错误文本回 LLM 续轮）。
     */
    @Bean
    public LlmProvider llmProvider(OkHttpClient client, AutovoiceProperties props,
                                   TelemetryRecorder recorder, McpSkillRegistry registry) {
        if (!"deepseek".equals(props.providers().llm())) {
            throw new IllegalArgumentException(
                    "unknown providers.llm: " + props.providers().llm() + " (deepseek)");
        }
        ToolProvider merged = () -> {
            List<FunctionTool> out = new ArrayList<>(DeepSeekLlmProvider.defaultTools());
            out.addAll(registry.enabledToolSpecs());
            return out;
        };
        return new DeepSeekLlmProvider(client, props.secrets().deepseekApiKey(),
                DeepSeekLlmProvider.DEFAULT_ENDPOINT, recorder, merged,
                DeepSeekLlmProvider.DEFAULT_TOOL_LOOP_BUDGET_MS,
                new McpToolExecutor(registry::callTool));
    }

    /**
     * 平台 webhook 接收端点（/api/internal/skills/refresh）：service-token 校验后触发重拉。
     * 显式 @Bean 定义覆盖组件扫描装配（SkillRefreshController 同时是 @RestController）：
     * serviceToken 由此方法注入，删掉本 @Bean 会让扫描装配因 String 构造参数失败。
     */
    @Bean
    public SkillRefreshController skillRefreshController(McpSkillRegistry registry, AutovoiceProperties props) {
        AutovoiceProperties.SkillManager sm = props.skillManager();
        // 平台已接入（url 非空）但 token 空白 = webhook 端点静默开门：启动期快速失败
        if (!sm.url().isBlank() && sm.serviceToken().isBlank()) {
            throw new IllegalStateException(
                    "autovoice.skill-manager.service-token 不能为空（SKILL_SERVICE_TOKEN）：已配置 skill-manager.url");
        }
        return new SkillRefreshController(registry, sm.serviceToken());
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

    /** TTS 转发（M4 独立服务）：网关不本地合成，HTTP 转发 tts-server /tts（RemoteTtsProvider）。 */
    @Bean
    public TtsProvider ttsProvider(OkHttpClient client, AutovoiceProperties props) {
        if (!"aliyun".equals(props.providers().tts())) {
            throw new IllegalArgumentException(
                    "unknown providers.tts: " + props.providers().tts() + " (aliyun)");
        }
        return new RemoteTtsProvider(client, props.tts().remoteUrl());
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
    public OfflineCommandService offlineCommandService(AutovoiceProperties props,
                                                       TelemetryRecorder recorder) {
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
            // recorder 注入位置随 bean 创建路径走：构造降级（catch）时 Noop provider 无插桩需求
            return new OfflineCommandService(new OfflineEnginePool(workers, recorder));
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
                                                   AutovoiceProperties props,
                                                   TelemetryRecorder recorder) {
        AutovoiceProperties.Gateway g = props.gateway();
        return new VoiceGatewayHandler(asr, llm, tts, offline, registry,
                props.arbitration().safetyTimeoutMs(), props.offline().asrFailWaitMs(),
                props.arbitration().offlineGraceMs(), g.authEnabled(), g.authDevicesMap(), g.maxConnections(),
                recorder);
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AppConfig.class);
}
