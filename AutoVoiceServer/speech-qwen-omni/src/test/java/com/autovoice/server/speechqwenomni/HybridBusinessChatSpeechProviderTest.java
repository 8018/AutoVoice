package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridBusinessChatSpeechProviderTest {

    private static final SessionContext CTX = new SessionContext("s1", "zh-CN", Map.of());

    @Test
    void defaultsToBusinessLlmAndKeepsS2sIdle() throws Exception {
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicInteger chatCalls = new AtomicInteger();
        HybridBusinessChatSpeechProvider provider = provider(
                List.of("导航去机场"), llmCalls, chatCalls);
        List<String> asrEvents = new ArrayList<>();

        OnlineSpeechResult result = provider.process(new byte[]{1}, CTX, "u1",
                OnlineAudioSink.NOOP, (text, isFinal) -> asrEvents.add(text))
                .get(2, TimeUnit.SECONDS);

        assertEquals("business:导航去机场", result.reply().text());
        assertEquals(List.of("导航去机场"), asrEvents);
        assertEquals(1, llmCalls.get());
        assertEquals(0, chatCalls.get());
        assertFalse(provider.isChatting(CTX));
    }

    @Test
    void explicitPhraseEntersPersistentChatAndExitReturnsToBusiness() throws Exception {
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicInteger chatCalls = new AtomicInteger();
        HybridBusinessChatSpeechProvider provider = provider(List.of(
                "陪我聊会天", "最近有点累", "先不聊了", "导航去机场"), llmCalls, chatCalls);

        assertEquals("chat", turn(provider, "u1").reply().text());
        assertTrue(provider.isChatting(CTX));
        assertEquals("chat", turn(provider, "u2").reply().text());
        assertEquals(HybridBusinessChatSpeechProvider.EXIT_CHAT_REPLY, turn(provider, "u3").reply().text());
        assertFalse(provider.isChatting(CTX));
        assertEquals("business:导航去机场", turn(provider, "u4").reply().text());
        assertEquals(2, chatCalls.get());
        assertEquals(1, llmCalls.get());
    }

    private static OnlineSpeechResult turn(HybridBusinessChatSpeechProvider provider, String id)
            throws Exception {
        return provider.process(new byte[]{1}, CTX, id).get(2, TimeUnit.SECONDS);
    }

    private static HybridBusinessChatSpeechProvider provider(
            List<String> transcripts, AtomicInteger llmCalls, AtomicInteger chatCalls) {
        Queue<String> queue = new ArrayDeque<>(transcripts);
        AsrProvider asr = (pcm, ctx) -> queue.remove();
        LlmProvider llm = (text, ctx) -> {
            llmCalls.incrementAndGet();
            return CompletableFuture.completedFuture(Reply.ofText("business:" + text));
        };
        OnlineSpeechProvider chat = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm16k, SessionContext context, String utteranceId) {
                chatCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new OnlineSpeechResult(Reply.ofText("chat"), ""));
            }
            @Override public String id() { return "chat"; }
        };
        return new HybridBusinessChatSpeechProvider(asr, llm, chat, new NavigationDialogState());
    }
}
