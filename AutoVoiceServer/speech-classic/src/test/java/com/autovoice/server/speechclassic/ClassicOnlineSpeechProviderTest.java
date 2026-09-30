package com.autovoice.server.speechclassic;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.navigation.NavigationDialogService;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.StreamingAsrProvider;
import com.autovoice.server.contracts.StreamingAsrSession;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ClassicOnlineSpeechProviderTest {
    @Test
    void explicitTextUsesBusinessPathWithoutInvokingAsr() throws Exception {
        AtomicBoolean asrCalled = new AtomicBoolean();
        SessionContext context = new SessionContext("s1", "zh-CN", Map.of());
        ClassicOnlineSpeechProvider provider = new ClassicOnlineSpeechProvider(
                (pcm, ctx) -> {
                    asrCalled.set(true);
                    throw new AssertionError("text input must not invoke ASR");
                },
                (text, ctx) -> CompletableFuture.completedFuture(Reply.ofText("business:" + text)));

        OnlineSpeechResult result = provider.processText("今天天气", context, "u-text")
                .get(1, TimeUnit.SECONDS);

        assertFalse(asrCalled.get());
        assertEquals("business:今天天气", result.reply().text());
        assertEquals("", result.asrText());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> provider.processText("  ", context, "empty").get(1, TimeUnit.SECONDS));
    }

    @Test
    void explicitTextCancellationPropagatesToBusinessRequest() {
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Reply> pending = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        ClassicOnlineSpeechProvider provider = new ClassicOnlineSpeechProvider(
                (pcm, ctx) -> "unused", (text, ctx) -> pending);

        provider.processText("导航去机场", new SessionContext("s1", "zh-CN", Map.of()), "u-text")
                .cancel(true);

        assertTrue(cancelled.get());
    }

    @Test
    void asrEstablishesTurnIndependentlyBeforePublishingTranscript() throws Exception {
        List<String> events = new ArrayList<>();
        ClassicOnlineSpeechProvider provider = new ClassicOnlineSpeechProvider(
                (pcm, ctx) -> "今天天气",
                (text, ctx) -> CompletableFuture.completedFuture(Reply.ofText("晴天")));
        OnlineAsrSink sink = new OnlineAsrSink() {
            @Override public void onTurnEstablished() { events.add("established"); }
            @Override public void onResult(String text, boolean isFinal) {
                events.add("transcript:" + text + ":" + isFinal);
            }
        };

        provider.process(new byte[]{1}, new SessionContext("s1", "zh-CN", Map.of()), "u1",
                OnlineAudioSink.NOOP, sink).get(1, TimeUnit.SECONDS);

        assertEquals(List.of("established", "transcript:今天天气:true"), events);
    }

    @Test
    void explicitSecondTurnSelectionBypassesLlm() throws Exception {
        SessionContext context = new SessionContext("s1", "zh-CN", Map.of());
        NavigationDialogService dialog = new NavigationDialogService();
        Intent choose = Intent.of("1.0", "navigation", "choose_destination", Map.of(
                "candidates", SlotValue.stringValue("""
                        [{"poiname":"东店","lat":30.1,"lon":120.1},
                         {"poiname":"西店","lat":30.2,"lon":120.2}]
                        """)
        ), 1.0, "test", null);
        dialog.remember(context, Reply.ofAction(choose, "请选择"));
        AtomicBoolean llmCalled = new AtomicBoolean();
        ClassicOnlineSpeechProvider provider = new ClassicOnlineSpeechProvider(
                (pcm, ctx) -> "选第二个",
                (text, ctx) -> {
                    llmCalled.set(true);
                    return CompletableFuture.completedFuture(Reply.ofText("不应调用"));
                }, dialog);

        OnlineSpeechResult result = provider.process(new byte[]{1}, context, "u1")
                .get(1, TimeUnit.SECONDS);

        assertFalse(llmCalled.get());
        assertEquals("navigate", result.reply().intent().intent());
        assertEquals("西店", result.reply().intent().slots().get("poiname").value());
        assertEquals("选第二个", result.asrText());
    }

    @Test
    void cloudNluExitBypassesBusinessLlm() throws Exception {
        AtomicBoolean llmCalled = new AtomicBoolean();
        ClassicOnlineSpeechProvider provider = new ClassicOnlineSpeechProvider(
                (pcm, ctx) -> "退出当前对话。",
                (text, ctx) -> {
                    llmCalled.set(true);
                    return CompletableFuture.completedFuture(Reply.ofText("不应调用"));
                });

        OnlineSpeechResult result = provider.process(new byte[]{1},
                new SessionContext("s1", "zh-CN", Map.of()), "u-exit")
                .get(1, TimeUnit.SECONDS);

        assertFalse(llmCalled.get());
        assertEquals("conversation", result.reply().intent().domain());
        assertEquals("exit_dialogue", result.reply().intent().intent());
        assertEquals("退出当前对话。", result.asrText());
    }

    @Test
    void streamingFinishUsesProviderDeadlineAndReleasesSession() {
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
        ClassicOnlineSpeechProvider provider = new ClassicOnlineSpeechProvider(
                asr, (text, ctx) -> CompletableFuture.completedFuture(Reply.ofText("unused")));

        var stream = provider.openStream(new SessionContext("s1", "zh-CN", Map.of()),
                "u-timeout", OnlineAudioSink.NOOP, OnlineAsrSink.NOOP);

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> stream.finish().get(1, TimeUnit.SECONDS));
        assertTrue(cancelled.get());
    }
}
