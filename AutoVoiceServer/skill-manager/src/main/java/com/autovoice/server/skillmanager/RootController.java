package com.autovoice.server.skillmanager;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路径与无尾斜杠的 /skill-manager 重定向到管理面板。
 * 静态资源位于 /skill-manager/ 子目录，Spring Boot 不自动解析子目录 index.html。
 */
@Controller
public class RootController {

    @GetMapping({"/", "/skill-manager"})
    public String root() {
        return "redirect:/skill-manager/index.html";
    }
}
