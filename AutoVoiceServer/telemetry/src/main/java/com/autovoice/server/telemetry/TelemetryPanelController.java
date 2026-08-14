package com.autovoice.server.telemetry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 面板路径重定向：{@code /telemetry} 与 {@code /telemetry/} → {@code /telemetry/index.html}。
 * 静态资源位于子目录，Spring Boot 不自动解析子目录 index.html（与 skill 平台 rootfix 同款）。
 */
@Controller
@ConditionalOnProperty(prefix = "autovoice.telemetry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TelemetryPanelController {

    @GetMapping({"/telemetry", "/telemetry/"})
    public String panel() {
        return "redirect:/telemetry/index.html";
    }
}
