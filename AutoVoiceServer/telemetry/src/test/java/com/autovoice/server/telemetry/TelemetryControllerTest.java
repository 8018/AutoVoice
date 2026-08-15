package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * telemetry 是无应用入口的库模块（@SpringBootApplication 在 app 模块），@WebMvcTest
 * 需要包内 @SpringBootConfiguration 提供组件扫描——用嵌套测试配置类（@SpringBootApplication
 * 带 @ComponentScan，TelemetryController 经 WebMvcTypeExcludeFilter 纳入切片）。
 */
@WebMvcTest(TelemetryController.class)
class TelemetryControllerTest {

    @SpringBootApplication
    static class SliceTestConfig {
    }

    @Autowired
    MockMvc mvc;

    @MockBean
    TelemetryService svc;

    @Test
    void acceptsRoundPost() throws Exception {
        String body = "{\"utteranceId\":\"utt-1\",\"sessionId\":\"s1\",\"deviceId\":\"demo-1\","
                + "\"source\":\"button\",\"startMs\":1000,\"endMs\":5000,"
                + "\"events\":[{\"stage\":\"local_asr\",\"tsMs\":2000,\"level\":\"info\",\"payload\":{\"text\":\"打开空调\"}}]}";
        mvc.perform(post("/api/telemetry/round")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        verify(svc).recordDeviceRound(any());
    }

    @Test
    void acceptsAudioUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "utt-1.pcm", "application/octet-stream",
                new byte[1600]);
        mvc.perform(multipart("/api/telemetry/audio")
                        .file(file).param("utteranceId", "utt-1"))
                .andExpect(status().isOk());
        verify(svc).saveAudio(eq("utt-1"), any(byte[].class));

        MockMultipartFile oversized = new MockMultipartFile("file", "large.pcm", "application/octet-stream",
                new byte[(int) TelemetryController.MAX_AUDIO_UPLOAD_BYTES + 1]);
        mvc.perform(multipart("/api/telemetry/audio")
                        .file(oversized).param("utteranceId", "utt-large"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void acceptsEventsForward() throws Exception {
        // tts-server 转发（Task 5）：body {utteranceId, events[]} → 逐条 record
        String body = "{\"utteranceId\":\"utt-9\",\"events\":["
                + "{\"stage\":\"tts_request\",\"tsMs\":100,\"level\":\"info\",\"payload\":{\"text\":\"好的\"}}]}";
        mvc.perform(post("/api/telemetry/events")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        verify(svc).record(eq("utt-9"), any(TelemetryEvent.class));

        // 缺 events → 400；JSON 解析错误 → 400
        mvc.perform(post("/api/telemetry/events")
                        .contentType("application/json").content("{\"utteranceId\":\"utt-9\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/telemetry/events")
                        .contentType("application/json").content("{not json"))
                .andExpect(status().isBadRequest());

        String event = "{\"stage\":\"local_asr\",\"tsMs\":1,\"level\":\"info\"}";
        String oversized = "{\"utteranceId\":\"utt-large\",\"events\":["
                + String.join(",", java.util.Collections.nCopies(
                        TelemetryController.MAX_EVENTS_PER_REQUEST + 1, event)) + "]}";
        mvc.perform(post("/api/telemetry/events")
                        .contentType("application/json").content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void queriesRoundsAndRound() throws Exception {
        when(svc.queryRounds("demo-1", 0, Long.MAX_VALUE)).thenReturn(List.of());
        mvc.perform(get("/api/telemetry/rounds").param("device", "demo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 命中：200 + {summary, events} JSON 形状（Task 9 E2E/面板依赖）
        RoundSummary summary = new RoundSummary("utt-1", "demo-1", "button", 1000L, 5000L,
                "cloud", "cloud", "cloud", null, "ok", null);
        when(svc.queryRound("utt-1")).thenReturn(new RoundDetail(summary,
                List.of(new TelemetryEvent(TelemetryStages.UTTERANCE_START, 1000L, "info",
                        Map.of("source", "button")))));
        mvc.perform(get("/api/telemetry/rounds/utt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.deviceId").value("demo-1"))
                .andExpect(jsonPath("$.events[0].stage").value("utterance_start"));

        // 未命中：404（brief Step 6 明示 null → NOT_FOUND）
        when(svc.queryRound("missing")).thenReturn(null);
        mvc.perform(get("/api/telemetry/rounds/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamsSseOnNewRound() throws Exception {
        mvc.perform(get("/api/telemetry/stream")).andExpect(status().isOk());
        // SSE 推送内容由 service listener 回调驱动——冒烟只验证端点可连与注册 listener
        verify(svc).addListener(any());
    }
}
