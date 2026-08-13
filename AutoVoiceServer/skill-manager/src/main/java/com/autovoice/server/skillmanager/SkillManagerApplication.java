package com.autovoice.server.skillmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** skill 管理平台：管理第三方 MCP server 封装（skill）的独立应用。 */
@SpringBootApplication(scanBasePackages = "com.autovoice.server.skillmanager")
@ConfigurationPropertiesScan
public class SkillManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkillManagerApplication.class, args);
    }
}
