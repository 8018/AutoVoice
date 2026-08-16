package com.autovoice.server.app;

import com.autovoice.server.asrgateway.AliyunAsrProvider;
import com.autovoice.server.asrgateway.AliyunTokenClient;
import com.autovoice.server.asrgateway.IflytekIatAsrProvider;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.McpToolExecutor;
import com.autovoice.server.skillmcp.SystemPromptStore;
import com.autovoice.server.speechqwenomni.QwenOmniSpeechProvider;
import com.autovoice.server.speechqwenomni.TranscriptEnrichedSpeechProvider;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Omni 构建变体专用装配；产物不包含 DeepSeek，ASR 仅用于用户原话识别框。 */
@Configuration
public class OmniBackendConfig {

    @Bean
    public AliyunTokenClient omniAliyunTokenClient(OkHttpClient client,
                                                    AppConfig.AutovoiceProperties props) {
        return new AliyunTokenClient(client, props.secrets().aliyunAk(), props.secrets().aliyunSk());
    }

    @Bean
    public java.time.Clock omniClock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public AsrProvider omniTranscriptProvider(OkHttpClient client, AliyunTokenClient tokenClient,
                                              java.time.Clock clock,
                                              AppConfig.AutovoiceProperties props) {
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
    public OnlineSpeechProvider onlineSpeechProvider(OkHttpClient client, AsrProvider transcriptProvider,
                                                     AppConfig.AutovoiceProperties props,
                                                     McpSkillRegistry registry,
                                                     SystemPromptStore promptStore) {
        ToolProvider merged = () -> {
            List<FunctionTool> out = new ArrayList<>(QwenOmniSpeechProvider.defaultTools());
            out.addAll(registry.enabledToolSpecs());
            return out;
        };
        OnlineSpeechProvider qwen = new QwenOmniSpeechProvider(client, props.secrets().dashscopeApiKey(),
                QwenOmniSpeechProvider.DEFAULT_ENDPOINT, QwenOmniSpeechProvider.DEFAULT_MODEL,
                QwenOmniSpeechProvider.DEFAULT_VOICE, merged,
                new McpToolExecutor(registry::callTool), promptStore::get);
        return new TranscriptEnrichedSpeechProvider(qwen, transcriptProvider);
    }
}
