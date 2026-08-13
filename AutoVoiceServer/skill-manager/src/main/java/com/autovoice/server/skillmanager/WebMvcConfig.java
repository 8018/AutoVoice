package com.autovoice.server.skillmanager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册平台鉴权拦截器（/api/skills/** 与 /api/admin/**；login/logout 放行）。 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SkillProperties props;

    public WebMvcConfig(SkillProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor(props.adminToken(), props.serviceToken()))
                .addPathPatterns("/api/skills/**", "/api/admin/**")
                .excludePathPatterns("/api/admin/login", "/api/admin/logout");
    }

    @Bean
    public SkillService skillService(SqliteSkillStore store, SkillWebhookNotifier notifier) {
        return new SkillService(store, notifier, System::currentTimeMillis);
    }

    @Bean
    public SqliteSkillStore sqliteSkillStore(SkillProperties props) {
        SqliteSkillStore store = new SqliteSkillStore(props.dbPath());
        store.init();
        return store;
    }

    @Bean
    public SkillWebhookNotifier skillWebhookNotifier(SkillProperties props) {
        return new SkillWebhookPublisher(new okhttp3.OkHttpClient(),
                props.gatewayWebhookUrl(), props.serviceToken());
    }

    @Bean
    public McpDiscoveryClient mcpDiscoveryClient(SkillProperties props) {
        return new McpDiscoveryClient(props.mcpConnectTimeoutMs());
    }
}
