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
        // 空 token = 管理界面"静默开门"：启动期快速失败（不放 SkillProperties compact ctor——
        // 那里只归一化，测试用默认值直接构造 record 不应触发启动语义）
        if (props.adminToken() == null || props.adminToken().isBlank()) {
            throw new IllegalStateException(
                    "autovoice.skill-manager.admin-token 不能为空（SKILL_MANAGER_ADMIN_TOKEN）");
        }
        // service-token 仅在启用 webhook 推送（gatewayWebhookUrl 非空）时要求：feature 启用才校验
        if ((props.gatewayWebhookUrl() != null && !props.gatewayWebhookUrl().isBlank())
                && (props.serviceToken() == null || props.serviceToken().isBlank())) {
            throw new IllegalStateException(
                    "autovoice.skill-manager.service-token 不能为空（SKILL_SERVICE_TOKEN）：已配置 gateway-webhook-url");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor(props.adminToken(), props.serviceToken()))
                .addPathPatterns("/api/skills/**", "/api/admin/**", "/api/config/**")
                .excludePathPatterns("/api/admin/login", "/api/admin/logout");
    }

    @Bean
    public ConfigService configService(SqliteSkillStore store, SkillWebhookNotifier notifier) {
        return new ConfigService(store, notifier);
    }

    /**
     * 平台级配置（system prompt）。显式 @Bean 定义覆盖组件扫描装配（ConfigController
     * 同时是 @RestController）：adminToken 由此方法注入，删掉本 @Bean 会让扫描装配
     * 因构造参数缺失失败。
     */
    @Bean
    public ConfigController configController(ConfigService service, SkillProperties props) {
        return new ConfigController(service, props);
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
