package com.autovoice.server.app;

import com.autovoice.server.agentloop.AgentExecutionRuntime;
import com.autovoice.server.asrgateway.AliyunAsrProvider;
import com.autovoice.server.asrgateway.AliyunTokenClient;
import com.autovoice.server.asrgateway.IflytekIatAsrProvider;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessBackendConfigTest {
    private final BusinessBackendConfig config = new BusinessBackendConfig();
    private final OkHttpClient client = new OkHttpClient();
    private final AgentExecutionRuntime agentRuntime = new AgentExecutionRuntime();

    @AfterEach void closeRuntime() { agentRuntime.close(); }

    @Test
    void createsConfiguredAsrForEitherBuildVariant() {
        assertInstanceOf(IflytekIatAsrProvider.class, asr("iflytek"));
        assertInstanceOf(AliyunAsrProvider.class, asr("aliyun"));
    }

    @Test
    void rejectsUnknownAsrAndBusinessLlmProvidersAtAssemblyBoundary() {
        assertThrows(IllegalArgumentException.class, () -> asr("other"));
        assertThrows(IllegalArgumentException.class, () -> config.businessLlmProvider(
                client, properties("other", "iflytek"), NoopTelemetryRecorder.INSTANCE,
                null, null, agentRuntime));
    }

    private AsrProvider asr(String provider) {
        AppConfig.AutovoiceProperties props = properties("deepseek", provider);
        AliyunTokenClient tokenClient = config.aliyunTokenClient(client, props);
        return config.businessAsrProvider(client, tokenClient, Clock.systemUTC(), props);
    }

    private static AppConfig.AutovoiceProperties properties(String llm, String asr) {
        return new AppConfig.AutovoiceProperties(
                null,
                new AppConfig.AutovoiceProperties.Providers(llm, asr, "aliyun"),
                new AppConfig.AutovoiceProperties.Secrets(
                        "xfyun-app", "xfyun-key", "xfyun-secret", "deepseek-key",
                        "aliyun-ak", "aliyun-sk", "aliyun-app", "dashscope-key", "workspace"),
                null, null, null, null);
    }
}
