package com.autovoice.server.app;

import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.McpToolExecutor;
import com.autovoice.server.skillmcp.SystemPromptStore;
import com.autovoice.server.speechqwenomni.QwenOmniSpeechProvider;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Omni 构建变体专用装配；产物不包含 DeepSeek 和在线 ASR 实现。 */
@Configuration
public class OmniBackendConfig {

    @Bean
    public OnlineSpeechProvider onlineSpeechProvider(OkHttpClient client,
                                                     AppConfig.AutovoiceProperties props,
                                                     McpSkillRegistry registry,
                                                     SystemPromptStore promptStore) {
        ToolProvider merged = () -> {
            List<FunctionTool> out = new ArrayList<>(QwenOmniSpeechProvider.defaultTools());
            out.addAll(registry.enabledToolSpecs());
            return out;
        };
        return new QwenOmniSpeechProvider(client, props.secrets().dashscopeApiKey(),
                QwenOmniSpeechProvider.DEFAULT_ENDPOINT, QwenOmniSpeechProvider.DEFAULT_MODEL,
                QwenOmniSpeechProvider.DEFAULT_VOICE, merged,
                new McpToolExecutor(registry::callTool), promptStore::get);
    }
}
