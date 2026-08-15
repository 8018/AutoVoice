package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * telemetry 装配。recorder 策略（enabled 由 {@code autovoice.telemetry.enabled} 控制，
 * 默认 true）：
 * <ul>
 *   <li>enabled：{@link TelemetryService}（@Component + 类级 @ConditionalOnProperty）作为
 *       唯一 {@link TelemetryRecorder} 实现——此处<b>不</b>再定义 recorder bean，否则出现
 *       telemetryService/telemetryRecorder 两个同类型 bean（NoUniqueBeanDefinitionException）；</li>
 *   <li>enabled=false：TelemetryService/TelemetryController 均不注册，本类提供 Noop bean
 *       （服务端插桩零影响）。</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling}：开启 {@link TelemetryService#scheduledCleanup()} 的每日
 * 清理（review finding 1）——全仓唯一 @EnableScheduling，无重复；TelemetryService 禁用
 * 时不注册 bean，调度自然不生效。</p>
 */
@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
@EnableScheduling
public class TelemetryConfig implements WebMvcConfigurer {

    private final TelemetryProperties properties;

    public TelemetryConfig(TelemetryProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TelemetryAuthInterceptor(properties.accessToken()))
                .addPathPatterns("/api/telemetry/**");
    }

    @Bean
    @ConditionalOnProperty(prefix = "autovoice.telemetry", name = "enabled",
            havingValue = "false")
    public TelemetryRecorder noopTelemetryRecorder() {
        return NoopTelemetryRecorder.INSTANCE;
    }
}
