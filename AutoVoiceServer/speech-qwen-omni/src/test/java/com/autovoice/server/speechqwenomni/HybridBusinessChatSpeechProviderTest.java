package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.navigation.NavigationDialogService;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.StreamingAsrProvider;
import com.autovoice.server.contracts.StreamingAsrSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                "陪我聊会天", "导航去机场"), llmCalls, chatCalls);

        OnlineSpeechResult entered = turn(provider, "u1");
        assertEquals("enter_chat", entered.reply().intent().intent());
        assertTrue(provider.isChatting(CTX));
        assertEquals("chat", turn(provider, "u2").reply().text());
        assertEquals("exit_chat", turn(provider, "u3").reply().intent().intent());
        assertFalse(provider.isChatting(CTX));
        assertEquals("business:导航去机场", turn(provider, "u4").reply().text());
        assertEquals(2, chatCalls.get());
        assertEquals(1, llmCalls.get());
    }

    @Test
    void businessStreamingFinishUsesAsrDeadline() {
        AtomicBoolean cancelled = new AtomicBoolean();
        StreamingAsrProvider asr = new StreamingAsrProvider() {
            @Override public StreamingAsrSession start(SessionContext context, OnlineAsrSink sink) {
                return new StreamingAsrSession() {
                    @Override public void append(byte[] pcm16k) { }
                    @Override public CompletableFuture<String> finish() { return new CompletableFuture<>(); }
                    @Override public void cancel() { cancelled.set(true); }
                };
            }
            @Override public long finishTimeoutMs() { return 30; }
        };
        OnlineSpeechProvider unusedChat = new OnlineSpeechProvider() {
            @Override public CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext context, String utteranceId) {
                return CompletableFuture.completedFuture(
                        new OnlineSpeechResult(Reply.ofText("unused"), ""));
            }
            @Override public String id() { return "unused"; }
        };
        HybridBusinessChatSpeechProvider provider = new HybridBusinessChatSpeechProvider(
                asr, (text, ctx) -> CompletableFuture.completedFuture(Reply.ofText("unused")),
                unusedChat, new NavigationDialogService());

        var stream = provider.openStream(CTX, "u-timeout", OnlineAudioSink.NOOP, OnlineAsrSink.NOOP);

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> stream.finish().get(1, TimeUnit.SECONDS));
        assertTrue(cancelled.get());
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
                if (chatCalls.get() == 2) {
                    return CompletableFuture.completedFuture(new OnlineSpeechResult(
                            Reply.ofAction(com.autovoice.server.contracts.Intent.of(
                                    "1.0", "conversation", "exit_chat", Map.of(), 1.0,
                                    "test", null), "再见"), ""));
                }
                return CompletableFuture.completedFuture(
                        new OnlineSpeechResult(Reply.ofText("chat"), ""));
            }
            @Override public String id() { return "chat"; }
        };
        return new HybridBusinessChatSpeechProvider(asr, llm, chat, new NavigationDialogService());
    }
}
