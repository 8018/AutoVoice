package com.autovoice.server.ttsserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TTS 独立服务（多设备加固 M4）：与接入网关同机的独立 Spring Boot 进程，HTTP 端点
 * {@code POST /tts} 承接网关的 RemoteTtsProvider 转发；合成（DashScope sambert）归本服务，
 * 缓存归端侧（TtsCache，架构变更后本服务无缓存层），多实例 = 换端口（TTS_PORT）部署（"一样的设计"）。
 */
@SpringBootApplication
public class TtsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TtsServerApplication.class, args);
    }
}
