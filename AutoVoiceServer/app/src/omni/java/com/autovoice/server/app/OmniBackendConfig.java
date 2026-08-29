package com.autovoice.server.app;

import com.autovoice.server.asrgateway.AliyunAsrProvider;
import com.autovoice.server.asrgateway.AliyunTokenClient;
import com.autovoice.server.asrgateway.IflytekIatAsrProvider;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.McpToolExecutor;
import com.autovoice.server.skillmcp.SystemPromptStore;
import com.autovoice.server.skillmcp.ChatSystemPromptStore;
import com.autovoice.server.llm.DeepSeekLlmProvider;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.speechqwenomni.HybridBusinessChatSpeechProvider;
import com.autovoice.server.speechqwenomni.QwenOmniSpeechProvider;
import com.autovoice.server.speechqwenomni.QwenOmniRealtimeChatProvider;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Omni 混合变体：默认 ASR → DeepSeek 业务域；显式进入闲聊后才使用 Qwen S2S。 */
@Configuration
public class OmniBackendConfig {

    @Bean
    public NavigationDialogState navigationDialogState() {
        return new NavigationDialogState();
    }

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
    public LlmProvider omniBusinessLlmProvider(OkHttpClient client,
                                               AppConfig.AutovoiceProperties props,
                                               TelemetryRecorder recorder,
                                               McpSkillRegistry registry,
                                               SystemPromptStore promptStore) {
        if (!"deepseek".equals(props.providers().llm())) {
            throw new IllegalArgumentException(
                    "unknown providers.llm: " + props.providers().llm() + " (deepseek)");
        }
        ToolProvider businessTools = () -> {
            List<FunctionTool> out = new ArrayList<>(DeepSeekLlmProvider.defaultTools());
            out.addAll(registry.enabledToolSpecs());
            return out;
        };
        return new DeepSeekLlmProvider(client, props.secrets().deepseekApiKey(),
                DeepSeekLlmProvider.DEFAULT_ENDPOINT, recorder, businessTools,
                DeepSeekLlmProvider.DEFAULT_TOOL_LOOP_BUDGET_MS,
                new McpToolExecutor(registry::callTool), promptStore::get);
    }

    @Bean
    public OnlineSpeechProvider onlineSpeechProvider(OkHttpClient client, AsrProvider transcriptProvider,
                                                     LlmProvider businessLlm,
                                                     AppConfig.AutovoiceProperties props,
                                                     McpSkillRegistry registry,
                                                     ChatSystemPromptStore chatPromptStore,
                                                     NavigationDialogState navigationDialog) {
        ToolProvider chatTools = () -> {
            List<FunctionTool> out = new ArrayList<>();
            out.add(QwenOmniSpeechProvider.exitChatTool());
            out.addAll(registry.enabledChatToolSpecs());
            return out;
        };
        OnlineSpeechProvider qwen = new QwenOmniSpeechProvider(client, props.secrets().dashscopeApiKey(),
                QwenOmniSpeechProvider.DEFAULT_ENDPOINT, QwenOmniSpeechProvider.DEFAULT_MODEL,
                QwenOmniSpeechProvider.DEFAULT_VOICE, chatTools,
                new McpToolExecutor((name, args) -> registry.callTool("chat", name, args)),
                () -> {
                    String configured = chatPromptStore.get();
                    return configured == null || configured.isBlank()
                            ? QwenOmniSpeechProvider.DEFAULT_CHAT_SYSTEM_PROMPT : configured;
                });
        QwenOmniRealtimeChatProvider realtime = new QwenOmniRealtimeChatProvider(
                client, props.secrets().dashscopeApiKey(), props.secrets().dashscopeWorkspaceId(),
                QwenOmniRealtimeChatProvider.DEFAULT_MODEL,
                QwenOmniRealtimeChatProvider.DEFAULT_VOICE,
                () -> {
                    String configured = chatPromptStore.get();
                    return configured == null || configured.isBlank()
                            ? QwenOmniRealtimeChatProvider.DEFAULT_SYSTEM_PROMPT : configured;
                });
        return new HybridBusinessChatSpeechProvider(
                transcriptProvider, businessLlm, qwen, navigationDialog, realtime);
    }
}
