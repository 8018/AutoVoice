package com.autovoice.server.app;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NavigationDialog;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.speechclassic.ClassicOnlineSpeechProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Classic variant only selects the Classic speech adapter; business capabilities are shared. */
@Configuration
public class ClassicBackendConfig {

    @Bean
    public OnlineSpeechProvider onlineSpeechProvider(AsrProvider asr, LlmProvider llm,
                                                     NavigationDialog navigationDialog) {
        return new ClassicOnlineSpeechProvider(asr, llm, navigationDialog);
    }
}
