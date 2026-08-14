package com.autovoice.server.ttsserver;

import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.ttsgateway.AliyunTtsProvider;
import okhttp3.OkHttpClient;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TTS 服务装配：DashScope sambert 合成（架构变更：缓存移回端侧，本服务不再有缓存层，
 * 合成成败事件 tts_synth_request/ok/failed 由 AliyunTtsProvider 记录）。
 * secrets 来自环境变量占位符（DASHSCOPE_API_KEY），无 env 也能启动（provider 调用时才失败）。
 */
@Configuration
@EnableConfigurationProperties(TtsAppConfig.TtsProperties.class)
public class TtsAppConfig {

    /** {@code autovoice.*} 配置（constructor binding）。telemetryUrl 为网关 telemetry 基址
     *  （{@code autovoice.telemetry.url}，空 → 不转发，Noop）。 */
    @ConfigurationProperties(prefix = "autovoice")
    public record TtsProperties(Secrets secrets, String telemetryUrl) {

        public record Secrets(String dashscopeApiKey) {
        }
    }

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient();
    }

    /** 链路事件记录器（Task 5）：telemetryUrl 空 → Noop（不转发不依赖网关）；否则 HTTP 转发网关。 */
    @Bean
    public TelemetryRecorder ttsTelemetryRecorder(OkHttpClient client, TtsProperties props) {
        String url = props.telemetryUrl();
        if (url == null || url.isBlank()) {
            return NoopTelemetryRecorder.INSTANCE;
        }
        return new TtsTelemetryForwarder(client, url);
    }

    @Bean
    public TtsProvider ttsProvider(OkHttpClient client, TtsProperties props, TelemetryRecorder recorder) {
        // 架构变更：缓存移回端侧（TtsCache），本服务直接合成，无 CachedTtsProvider 包装
        return new AliyunTtsProvider(client, props.secrets().dashscopeApiKey(),
                AliyunTtsProvider.DEFAULT_ENDPOINT, recorder);
    }
}
