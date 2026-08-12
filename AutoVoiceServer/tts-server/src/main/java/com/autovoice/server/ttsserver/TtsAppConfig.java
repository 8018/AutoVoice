package com.autovoice.server.ttsserver;

import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.ttsgateway.AliyunTtsProvider;
import com.autovoice.server.ttsgateway.CachedTtsProvider;
import okhttp3.OkHttpClient;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TTS 服务装配：本地合成（DashScope sambert）+ 缓存（内存/磁盘，缓存归 TTS 服务侧，
 * 网关 RemoteTtsProvider 纯转发）。secrets 来自环境变量占位符（DASHSCOPE_API_KEY），
 * 无 env 也能启动（provider 调用时才失败）。
 */
@Configuration
@EnableConfigurationProperties(TtsAppConfig.TtsProperties.class)
public class TtsAppConfig {

    /** {@code autovoice.*} 配置（constructor binding）。telemetryUrl 为网关 telemetry 基址
     *  （{@code autovoice.telemetry.url}，空 → 不转发，Noop）。 */
    @ConfigurationProperties(prefix = "autovoice")
    public record TtsProperties(String cacheDir, Secrets secrets, String telemetryUrl) {

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
        TtsProvider delegate = new AliyunTtsProvider(client, props.secrets().dashscopeApiKey(),
                AliyunTtsProvider.DEFAULT_ENDPOINT, recorder);
        String cacheDir = props.cacheDir();
        if (cacheDir == null || cacheDir.isBlank()) {
            return new CachedTtsProvider(delegate, null, recorder); // 仅内存缓存
        }
        return new CachedTtsProvider(delegate, java.nio.file.Path.of(cacheDir), recorder);
    }
}
