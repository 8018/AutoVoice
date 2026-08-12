package com.autovoice.server.ttsserver;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.ttsserver.TtsController.TtsRequest;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void synthesizesAndReturnsBase64Audio() throws Exception {
        when(tts.synthesize(eq("打开空调"), any())).thenReturn(Reply.ofAudio("audio/wav", WAV));

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
        when(tts.synthesize(any(), any())).thenThrow(new RuntimeException("aliyun tts timeout"));

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
        when(tts.synthesize(any(), any())).thenReturn(Reply.ofAudio("audio/wav", new byte[0]));

        mvc.perform(post("/tts")
                        .contentType(JSON)
                        .content("{\"text\":\"打开空调\",\"sessionId\":\"demo-1\"}"))
                .andExpect(status().isInternalServerError());
    }
}
