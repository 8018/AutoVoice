package com.autovoice.server.ttsserver;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import com.autovoice.server.ttsserver.TtsController.TtsRequest;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TTS 服务端点：正常合成 / 合成失败 500 / 缺 text 400 / 空音频 500。 */
@WebMvcTest(TtsController.class)
class TtsControllerTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final byte[] WAV = {0x52, 0x49, 0x46, 0x46, 0x00}; // "RIFF\0" 假音频

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TtsProvider tts;

    @MockBean
    private TelemetryRecorder recorder;

    @Test
    void recordsTtsRequestEventAndForwardsUtteranceId() throws Exception {
        when(tts.synthesize(anyString(), any(), anyString())).thenReturn(Reply.ofAudio("audio/wav", WAV));

        mvc.perform(post("/tts").contentType(JSON)
                        .content("{\"text\":\"好的\",\"sessionId\":\"s1\",\"utteranceId\":\"utt-5\"}"))
                .andExpect(status().isOk());

        verify(tts).synthesize(eq("好的"), any(), eq("utt-5"));
        verify(recorder).record(eq("utt-5"), eq(TelemetryStages.TTS_PLAY_REQUEST), eq("info"), any());
    }

    @Test
    void recordsTtsRequestErrorOnSynthesizeFailure() throws Exception {
        when(tts.synthesize(any(), any(), any())).thenThrow(new RuntimeException("aliyun tts timeout"));

        mvc.perform(post("/tts")
                        .contentType(JSON)
                        .content("{\"text\":\"好的\",\"sessionId\":\"s1\",\"utteranceId\":\"utt-6\"}"))
                .andExpect(status().isInternalServerError());

        verify(recorder).record(eq("utt-6"), eq(TelemetryStages.TTS_PLAY_REQUEST), eq("error"), any());
    }

    @Test
    void synthesizesAndReturnsBase64Audio() throws Exception {
        when(tts.synthesize(eq("打开空调"), any(), any())).thenReturn(Reply.ofAudio("audio/wav", WAV));

        mvc.perform(post("/tts")
                        .contentType(JSON)
                        .content("{\"text\":\"打开空调\",\"sessionId\":\"demo-1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mime").value("audio/wav"))
                .andExpect(jsonPath("$.dataBase64").value(Base64.getEncoder().encodeToString(WAV)));
    }

    @Test
    void synthesizeFailureYields500() throws Exception {
        when(tts.synthesize(any(), any(), any())).thenThrow(new RuntimeException("aliyun tts timeout"));

        mvc.perform(post("/tts")
                        .contentType(JSON)
                        .content("{\"text\":\"打开空调\",\"sessionId\":\"demo-1\"}"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void missingTextYields400() throws Exception {
        mvc.perform(post("/tts")
                        .contentType(JSON)
                        .content("{\"sessionId\":\"demo-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyAudioYields500() throws Exception {
        when(tts.synthesize(any(), any(), any())).thenReturn(Reply.ofAudio("audio/wav", new byte[0]));

        mvc.perform(post("/tts")
                        .contentType(JSON)
                        .content("{\"text\":\"打开空调\",\"sessionId\":\"demo-1\"}"))
                .andExpect(status().isInternalServerError());
    }
}
