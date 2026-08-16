package com.autovoice.server.app;

import com.autovoice.server.asrgateway.AliyunAsrProvider;
import com.autovoice.server.asrgateway.AliyunTokenClient;
import com.autovoice.server.asrgateway.IflytekIatAsrProvider;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.llm.DeepSeekLlmProvider;
import com.autovoice.server.skillmcp.McpSkillRegistry;
import com.autovoice.server.skillmcp.McpToolExecutor;
import com.autovoice.server.skillmcp.SystemPromptStore;
import com.autovoice.server.speechclassic.ClassicOnlineSpeechProvider;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Classic 构建变体专用装配；Omni 产物不会编译本类，也不会携带 DeepSeek/Classic speech。 */
@Configuration
public class ClassicBackendConfig {

    @Bean
    public LlmProvider llmProvider(OkHttpClient client, AppConfig.AutovoiceProperties props,
                                   TelemetryRecorder recorder, McpSkillRegistry registry,
                                   SystemPromptStore promptStore) {
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
                new McpToolExecutor(registry::callTool), promptStore::get);
    }

    @Bean
    public AliyunTokenClient aliyunTokenClient(OkHttpClient client, AppConfig.AutovoiceProperties props) {
        return new AliyunTokenClient(client, props.secrets().aliyunAk(), props.secrets().aliyunSk());
    }

    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public AsrProvider asrProvider(OkHttpClient client, AliyunTokenClient tokenClient,
                                   java.time.Clock clock, AppConfig.AutovoiceProperties props) {
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
    public OnlineSpeechProvider onlineSpeechProvider(AsrProvider asr, LlmProvider llm) {
        return new ClassicOnlineSpeechProvider(asr, llm);
    }
}
