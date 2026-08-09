package com.autovoice.server.app;

import com.autovoice.server.asrgateway.AliyunAsrProvider;
import com.autovoice.server.asrgateway.AliyunTokenClient;
import com.autovoice.server.asrgateway.IflytekIatAsrProvider;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.gateway.VoiceGatewayHandler;
import com.autovoice.server.llm.DeepSeekLlmProvider;
import com.autovoice.server.offlinecommand.NoopOfflineCommandProvider;
import com.autovoice.server.offlinecommand.OfflineCommandService;
import com.autovoice.server.session.SessionRegistry;
import com.autovoice.server.ttsgateway.AliyunTtsProvider;
import okhttp3.OkHttpClient;
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
    public record AutovoiceProperties(Arbitration arbitration, Providers providers, Secrets secrets) {

        public record Arbitration(long safetyTimeoutMs) {
        }

        public record Providers(String llm, String asr, String tts) {
        }

        /** secrets 全部 ${VAR:} 空默认：不写入任何 secret 字面值。 */
        public record Secrets(String xfyunAppid, String xfyunApiKey, String xfyunApiSecret,
                              String deepseekApiKey, String aliyunAk, String aliyunSk,
                              String aliyunNlsAppkey, String dashscopeApiKey) {
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
        return new AliyunTtsProvider(client, props.secrets().dashscopeApiKey(),
                AliyunTtsProvider.DEFAULT_ENDPOINT);
    }

    /**
     * 离线命令链：S3 暂以 Noop 装配（离线未启用，与改造前行为一致）；
     * S5 按 {@code autovoice.offline.enabled} 切换 Native/Noop。
     */
    @Bean
    public OfflineCommandService offlineCommandService() {
        return new OfflineCommandService(new NoopOfflineCommandProvider());
    }

    /**
     * 网关 WS 处理器：仲裁参数来自配置（safetyTimeoutMs）；每连接的
     * RaceArbiter 复用 handler 内部 daemon 调度线程池（随 JVM 退出，无需 app 级关闭）。
     */
    @Bean
    public VoiceGatewayHandler voiceGatewayHandler(AsrProvider asr, LlmProvider llm,
                                                   TtsProvider tts, OfflineCommandService offline,
                                                   SessionRegistry registry,
                                                   AutovoiceProperties props) {
        return new VoiceGatewayHandler(asr, llm, tts, offline, registry,
                props.arbitration().safetyTimeoutMs(), 2000);
    }
}
