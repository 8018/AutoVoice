package com.autovoice.server.app;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NavigationDialog;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.skillmcp.ChatSystemPromptStore;
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.McpToolExecutor;
import com.autovoice.server.speechqwenomni.HybridBusinessChatSpeechProvider;
import com.autovoice.server.speechqwenomni.QwenOmniRealtimeChatProvider;
import com.autovoice.server.speechqwenomni.QwenOmniSpeechProvider;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Omni variant adds isolated Qwen chat capabilities around the shared business backend. */
@Configuration
public class OmniBackendConfig {

    @Bean
    public OnlineSpeechProvider onlineSpeechProvider(OkHttpClient client, AsrProvider asr,
                                                     LlmProvider businessLlm,
                                                     AppConfig.AutovoiceProperties props,
                                                     McpSkillRegistry registry,
                                                     ChatSystemPromptStore chatPromptStore,
                                                     NavigationDialog navigationDialog) {
        ToolProvider chatTools = () -> {
            List<FunctionTool> tools = new ArrayList<>();
            tools.add(QwenOmniSpeechProvider.exitChatTool());
            tools.addAll(registry.enabledChatToolSpecs());
            return tools;
        };
        OnlineSpeechProvider qwen = new QwenOmniSpeechProvider(client,
                props.secrets().dashscopeApiKey(), QwenOmniSpeechProvider.DEFAULT_ENDPOINT,
                QwenOmniSpeechProvider.DEFAULT_MODEL, QwenOmniSpeechProvider.DEFAULT_VOICE,
                chatTools,
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
                asr, businessLlm, qwen, navigationDialog, realtime);
    }
}
