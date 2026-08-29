package com.autovoice.server.speechqwenomni;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SlotValue;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QwenOmniSpeechProviderTest {

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void close() throws Exception {
        server.close();
    }

    @Test
    void accumulatesSseTextAndAudioAndWrapsPcmAs24kWav() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        server.enqueue(sse(
                delta("content", "好"),
                audioFragment(encoded.substring(0, 3)),
                delta("content", "的"),
                audioFragment(encoded.substring(3))));

        QwenOmniSpeechProvider provider = provider((name, args) -> "unused");
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        CopyOnWriteArrayList<String> replyTextUpdates = new CopyOnWriteArrayList<>();
        OnlineAudioSink sink = new OnlineAudioSink() {
            @Override public void onStart(int rate, int channels, String encoding) {
                assertEquals(24_000, rate);
                assertEquals("pcm_s16le", encoding);
                started.set(true);
            }
            @Override public void onChunk(byte[] pcm) { streamed.writeBytes(pcm); }
            @Override public void onReplyText(String text, boolean isFinal) {
                replyTextUpdates.add(text + ":" + isFinal);
            }
            @Override public void onComplete(String text, Intent intent) { completed.set(true); }
        };
        OnlineSpeechResult result = provider.process(new byte[]{9, 8, 7, 6}, context(), "u1", sink)
                .get(2, TimeUnit.SECONDS);

        assertEquals("audio", result.reply().kind());
        assertEquals("好的", result.reply().speakText());
        assertEquals("audio/wav", result.reply().mime());
        assertEquals('R', result.reply().data()[0]);
        assertArrayEquals(new byte[]{1, 2, 3, 4},
                java.util.Arrays.copyOfRange(result.reply().data(), 44, 48));
        assertTrue(started.get());
        assertTrue(completed.get());
        assertEquals(java.util.List.of("好的:false", "好的:true"), replyTextUpdates,
                "工具可用轮应在短决策窗口后提交累积文本，并形成 final 快照");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, streamed.toByteArray());

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertEquals("qwen3.5-omni-plus", body.path("model").asText());
        JsonNode messages = body.path("messages");
        String systemPrompt = messages.path(0).path("content").asText();
        assertTrue(systemPrompt.contains("respond only in that same language"));
        assertTrue(systemPrompt.contains("tool results"));
        assertTrue(systemPrompt.contains("emit tool_calls only"),
                "提示词应要求工具轮不生成用户可见音频，服务端状态机再提供硬保障");
        JsonNode userContent = messages.path(1).path("content");
        assertEquals(1, userContent.size(),
                "用户消息只能包含原始音频，不能附加会把模型带向中文的文本或会话语言");
        assertEquals("input_audio", userContent.path(0).path("type").asText());
        assertTrue(userContent.path(0).path("input_audio").path("data").asText()
                .startsWith("data:audio/wav;base64,"));
        assertFalse(body.toString().contains("zh-CN"));
        assertFalse(body.toString().contains("理解这段语音"));
        assertTrue(body.path("parallel_tool_calls").asBoolean(),
                "多地点的独立 POI 查询应允许模型在同一轮规划");
    }

    @Test
    void omitsToolFieldsWhenChatDomainHasNoSkills() throws Exception {
        server.enqueue(sse(delta("content", "Hello")));
        QwenOmniSpeechProvider provider = new QwenOmniSpeechProvider(
                new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null, java.util.List::of,
                (name, args) -> "unused", () -> "You are a casual chat companion.");

        OnlineSpeechResult result = provider.process(new byte[]{1, 2}, context(), "u-no-tools")
                .get(2, TimeUnit.SECONDS);

        assertEquals("Hello", result.reply().speakText());
        JsonNode body = new ObjectMapper().readTree(
                server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertFalse(body.has("tools"));
        assertFalse(body.has("tool_choice"));
        assertFalse(body.has("parallel_tool_calls"));
    }

    @Test
    void exitChatControlToolProducesConversationIntentWithoutExternalExecution() throws Exception {
        server.enqueue(sse(toolCall(QwenOmniSpeechProvider.EXIT_CHAT_TOOL, "{}")));
        server.enqueue(sse(delta("content", "再见"), audio(new byte[]{1, 2})));
        AtomicInteger externalCalls = new AtomicInteger();
        QwenOmniSpeechProvider provider = new QwenOmniSpeechProvider(
                new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null, () -> java.util.List.of(QwenOmniSpeechProvider.exitChatTool()),
                (name, args) -> {
                    externalCalls.incrementAndGet();
                    return "unused";
                }, () -> QwenOmniSpeechProvider.DEFAULT_CHAT_SYSTEM_PROMPT);

        OnlineSpeechResult result = provider.process(new byte[]{1, 2}, context(), "u-exit")
                .get(2, TimeUnit.SECONDS);

        assertEquals(0, externalCalls.get());
        assertEquals("conversation", result.reply().intent().domain());
        assertEquals("exit_chat", result.reply().intent().intent());
        assertEquals("再见", result.reply().speakText());
    }

    @Test
    void toolCallWithinDecisionWindowSuppressesIntermediateAudioAndContinuesLoop() throws Exception {
        server.enqueue(sse(
                delta("content", "正在查询"),
                audio(new byte[]{1, 2, 3, 4}),
                toolCall("weather", "{\"city\":\"杭州\"}"),
                audio(new byte[]{5, 6})));
        server.enqueue(sse(delta("content", "杭州晴天"), audio(new byte[]{7, 8})));
        AtomicInteger executions = new AtomicInteger();
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        CopyOnWriteArrayList<String> replyTexts = new CopyOnWriteArrayList<>();
        AtomicBoolean errored = new AtomicBoolean();

        OnlineSpeechResult result = provider((name, args) -> {
            executions.incrementAndGet();
            return "晴天";
        }).process(new byte[]{1, 2}, context(), "u-mixed-tool-wins", new OnlineAudioSink() {
            @Override public void onChunk(byte[] pcm) { streamed.writeBytes(pcm); }
            @Override public void onReplyText(String text, boolean isFinal) {
                replyTexts.add(text + ":" + isFinal);
            }
            @Override public void onError(Throwable error) { errored.set(true); }
        }).get(2, TimeUnit.SECONDS);

        assertEquals(1, executions.get());
        assertEquals(2, server.getRequestCount());
        assertFalse(errored.get(), "混合输出必须由业务仲裁收敛，不能中断在线流");
        assertArrayEquals(new byte[]{7, 8}, streamed.toByteArray(),
                "工具通道胜出后不得泄漏该轮中间音频");
        assertFalse(replyTexts.stream().anyMatch(value -> value.contains("正在查询")),
                "工具轮中间话术不得上屏");
        assertEquals("杭州晴天", result.reply().speakText());
    }

    @Test
    void audioCommitIgnoresLateToolCallWithoutExecutingIt() throws Exception {
        byte[] committedAudio = new byte[24_000];
        for (int i = 0; i < committedAudio.length; i++) committedAudio[i] = (byte) (i % 97);
        server.enqueue(sse(
                delta("content", "这是普通回答"),
                audio(committedAudio),
                toolCall("weather", "{\"city\":\"杭州\"}")));
        AtomicInteger executions = new AtomicInteger();
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        AtomicBoolean errored = new AtomicBoolean();

        OnlineSpeechResult result = provider((name, args) -> {
            executions.incrementAndGet();
            return "不应执行";
        }).process(new byte[]{1, 2}, context(), "u-mixed-audio-wins", new OnlineAudioSink() {
            @Override public void onChunk(byte[] pcm) { streamed.writeBytes(pcm); }
            @Override public void onError(Throwable error) { errored.set(true); }
        }).get(2, TimeUnit.SECONDS);

        assertEquals(0, executions.get(), "音频提交后迟到的工具调用不得改变已提交业务结果");
        assertEquals(1, server.getRequestCount());
        assertFalse(errored.get());
        assertEquals("audio", result.reply().kind());
        assertArrayEquals(committedAudio, streamed.toByteArray());
        assertArrayEquals(committedAudio,
                java.util.Arrays.copyOfRange(result.reply().data(), 44, 44 + committedAudio.length));
    }

    @Test
    void disablesReadTimeoutForSilentSseInferenceWindow() throws Exception {
        server.enqueue(sse(delta("content", "ok"))
                .setBodyDelay(300, TimeUnit.MILLISECONDS));
        OkHttpClient shortLivedBase = new OkHttpClient.Builder()
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .build();
        QwenOmniSpeechProvider provider = new QwenOmniSpeechProvider(
                shortLivedBase, "test-key", server.url("/chat").toString(),
                null, null, QwenOmniSpeechProvider::defaultTools,
                (name, args) -> "unused", () -> "test");

        OnlineSpeechResult result = provider.process(new byte[]{1, 2}, context(), "u-slow-sse")
                .get(2, TimeUnit.SECONDS);

        assertEquals("ok", result.reply().speakText(),
                "SSE 静默超过基础 client 的 read timeout 仍应由网关整轮超时统一收敛");
    }

    @Test
    void sidecarAsrAddsUserTranscriptToStreamEndAndResult() throws Exception {
        AtomicReference<String> endTranscript = new AtomicReference<>();
        AtomicReference<String> earlyTranscript = new AtomicReference<>();
        java.util.List<String> eventOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        OnlineSpeechProvider speech = new OnlineSpeechProvider() {
            @Override public java.util.concurrent.CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) {
                return process(pcm, ctx, uid, OnlineAudioSink.NOOP);
            }
            @Override public java.util.concurrent.CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid, OnlineAudioSink sink) {
                sink.onStart(24_000, 1, "pcm_s16le");
                sink.onChunk(new byte[]{1, 2});
                sink.onComplete("Sure", null);
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new OnlineSpeechResult(com.autovoice.server.contracts.Reply.ofAudio(
                                "audio/wav", new byte[]{1, 2}, "Sure", null), ""));
            }
            @Override public String id() { return "fake-omni"; }
        };
        TranscriptEnrichedSpeechProvider provider = new TranscriptEnrichedSpeechProvider(
                speech, (pcm, ctx) -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "Could you open the window?";
                });
        OnlineSpeechResult result = provider.process(new byte[]{3, 4}, context(), "u-sidecar",
                new OnlineAudioSink() {
                    @Override public void onStart(int rate, int channels, String encoding) {
                        eventOrder.add("audio_start");
                    }
                    @Override public void onChunk(byte[] pcm) { eventOrder.add("audio_chunk"); }
                    @Override public void onComplete(String text, Intent intent, String asrText) {
                        endTranscript.set(asrText);
                    }
                }, (text, isFinal) -> {
                    eventOrder.add("asr");
                    earlyTranscript.set(text + ":" + isFinal);
                })
                .get(2, TimeUnit.SECONDS);

        assertEquals("Could you open the window?", result.asrText());
        assertEquals("Could you open the window?", endTranscript.get());
        assertEquals("Could you open the window?:true", earlyTranscript.get(),
                "sidecar ASR 应通过独立通道上屏，不等待最终语义结果");
        assertTrue(eventOrder.indexOf("asr") < eventOrder.indexOf("audio_start"),
                "旁路 ASR 应在首音频放行前下发：" + eventOrder);
    }

    @Test
    void pendingNavigationSelectionSuppressesOmniOutputWithoutCancellingIt() throws Exception {
        NavigationDialogState dialog = new NavigationDialogState();
        Intent choose = Intent.of("1.0", "navigation", "choose_destination", Map.of(
                "candidates", SlotValue.stringValue("""
                        [{"poiname":"东店","lat":30.1,"lon":120.1,"address":"中山路1号"},
                         {"poiname":"西店","lat":30.2,"lon":120.2,"address":"人民路8号"}]
                        """)
        ), 1.0, "test", null);
        dialog.remember(context(), Reply.ofAction(choose, "请选择"));
        AtomicBoolean modelCompleted = new AtomicBoolean();
        CopyOnWriteArrayList<String> audioEvents = new CopyOnWriteArrayList<>();
        OnlineSpeechProvider speech = new OnlineSpeechProvider() {
            @Override public java.util.concurrent.CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid) {
                return process(pcm, ctx, uid, OnlineAudioSink.NOOP);
            }
            @Override public java.util.concurrent.CompletableFuture<OnlineSpeechResult> process(
                    byte[] pcm, SessionContext ctx, String uid, OnlineAudioSink sink) {
                sink.onStart(24_000, 1, "pcm_s16le");
                sink.onChunk(new byte[]{9, 9});
                return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(100); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    modelCompleted.set(true);
                    return new OnlineSpeechResult(Reply.ofText("模型旧输出"), "");
                });
            }
            @Override public String id() { return "fake-omni"; }
        };
        TranscriptEnrichedSpeechProvider provider = new TranscriptEnrichedSpeechProvider(
                speech, (pcm, ctx) -> "第二个", dialog);

        OnlineSpeechResult result = provider.process(new byte[]{1}, context(), "u-select",
                new OnlineAudioSink() {
                    @Override public void onStart(int rate, int channels, String encoding) {
                        audioEvents.add("start");
                    }
                    @Override public void onChunk(byte[] pcm) { audioEvents.add("chunk"); }
                }).get(1, TimeUnit.SECONDS);

        assertEquals("navigate", result.reply().intent().intent());
        assertEquals("西店", result.reply().intent().slots().get("poiname").value());
        assertTrue(audioEvents.isEmpty(), "选择命中时模型音频应被门控拦截");
        Thread.sleep(150);
        assertTrue(modelCompleted.get(), "模型候选应自然完成而不是被取消");
    }

    @Test
    void concatenatesToolCallArgumentsReusesExecutorThenReturnsAudio() throws Exception {
        server.enqueue(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"杭州\\\"}\"}}]}}]}"));
        server.enqueue(sse(delta("content", "杭州晴天"), audio(new byte[]{5, 6})));
        AtomicReference<String> invocation = new AtomicReference<>();
        QwenOmniSpeechProvider provider = new QwenOmniSpeechProvider(
                new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null,
                () -> java.util.List.of(new com.autovoice.server.contracts.FunctionTool(
                        "weather", "天气", "{\"type\":\"object\"}")),
                (name, args) -> {
                    invocation.set(name + ":" + args);
                    return "晴天";
                }, () -> "测试");

        OnlineSpeechResult result = provider.process(new byte[]{1, 2}, context(), "u2")
                .get(2, TimeUnit.SECONDS);

        assertEquals("weather:{\"city\":\"杭州\"}", invocation.get());
        assertEquals("audio", result.reply().kind());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void executesMultipleIndependentToolCallsFromOneModelRound() throws Exception {
        server.enqueue(sse("{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call-a\",\"function\":{\"name\":\"poi_search\","
                + "\"arguments\":\"{\\\"query\\\":\\\"山姆\\\"}\"}},"
                + "{\"index\":1,\"id\":\"call-b\",\"function\":{\"name\":\"poi_search\","
                + "\"arguments\":\"{\\\"query\\\":\\\"AI创新中心\\\"}\"}}]}}]}"));
        server.enqueue(sse(delta("content", "已开始导航")));
        CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();
        QwenOmniSpeechProvider provider = new QwenOmniSpeechProvider(
                new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null,
                () -> java.util.List.of(new com.autovoice.server.contracts.FunctionTool(
                        "poi_search", "地点搜索", "{\"type\":\"object\"}")),
                (name, args) -> {
                    calls.add(name + ":" + args);
                    return "resolved:" + args;
                }, () -> "测试");

        provider.process(new byte[]{1, 2}, context(), "u-parallel").get(2, TimeUnit.SECONDS);

        assertEquals(2, calls.size());
        RecordedRequest second = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(second);
        second = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(second);
        JsonNode messages = new ObjectMapper().readTree(second.getBody().readUtf8()).path("messages");
        assertEquals(2, messages.findValues("tool_call_id").size(),
                "同一模型轮返回的每个查询结果都必须按 call id 回填");
    }

    @Test
    void reusesIdenticalToolResultAcrossRounds() throws Exception {
        String sameCall = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"%s\",\"function\":{\"name\":\"poi_search\","
                + "\"arguments\":\"{\\\"query\\\":\\\"山姆\\\"}\"}}]}}]}";
        server.enqueue(sse(sameCall.formatted("call-1")));
        server.enqueue(sse(sameCall.formatted("call-2")));
        server.enqueue(sse(delta("content", "已找到")));
        AtomicInteger executions = new AtomicInteger();
        QwenOmniSpeechProvider provider = new QwenOmniSpeechProvider(
                new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null,
                () -> java.util.List.of(new com.autovoice.server.contracts.FunctionTool(
                        "poi_search", "地点搜索", "{\"type\":\"object\"}")),
                (name, args) -> {
                    executions.incrementAndGet();
                    return "resolved";
                }, () -> "测试");

        provider.process(new byte[]{1, 2}, context(), "u-dedup").get(2, TimeUnit.SECONDS);

        assertEquals(1, executions.get(), "同名同参的重复工具调用不应再次请求高德");
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void injectsCurrentLocationAndUnambiguousMultiStopNavigationContract() throws Exception {
        server.enqueue(sse(delta("content", "ok")));
        QwenOmniSpeechProvider provider = provider((name, args) -> "unused");
        SessionContext located = new SessionContext("s1", "zh-CN",
                Map.of("latitude", 30.2741, "longitude", 120.1551));

        provider.process(new byte[]{1, 2}, located, "u-location").get(2, TimeUnit.SECONDS);

        JsonNode body = new ObjectMapper().readTree(
                server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        String prompt = body.path("messages").path(0).path("content").asText();
        assertTrue(prompt.contains("车辆坐标(lon,lat)=120.1551,30.2741"));
        assertTrue(prompt.contains("Prefer nearby candidates"));
        JsonNode navigate = body.path("tools").findValues("function").stream()
                .filter(fn -> "navigate".equals(fn.path("name").asText())).findFirst().orElseThrow();
        assertTrue(navigate.path("description").asText().contains("最终目的地"));
        assertEquals(java.util.List.of("poiname", "lat", "lon"),
                new ObjectMapper().convertValue(
                        navigate.path("parameters").path("properties").path("waypoints")
                                .path("items").path("required"), java.util.List.class));
    }

    private QwenOmniSpeechProvider provider(com.autovoice.server.contracts.ToolExecutor executor) {
        return new QwenOmniSpeechProvider(new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null, QwenOmniSpeechProvider::defaultTools, executor, () -> "测试");
    }

    // ------------------------------------------------------------ 工具循环上限与优雅降级

    @Test
    void toolChainBeyondSevenRoundsCompletes() throws Exception {
        // 多步工具链（selector 注入下每个真实动作 2 轮）超过旧上限 7 轮仍应完成：
        // 7 轮工具调用 + 1 轮最终回答 = 8 次 HTTP
        for (int i = 0; i < 7; i++) {
            server.enqueue(sse(toolCall("weather", "{\"city\":\"杭州\"}")));
        }
        server.enqueue(sse(delta("content", "杭州晴天")));

        OnlineSpeechResult result = provider((name, args) -> "晴天")
                .process(new byte[]{1, 2}, context(), "u-tool-chain")
                .get(2, TimeUnit.SECONDS);

        assertEquals("text", result.reply().kind());
        assertEquals("杭州晴天", result.reply().speakText());
        assertEquals(8, server.getRequestCount());
    }

    @Test
    void toolLoopExhaustionReturnsLastAccumulatedText() throws Exception {
        // 循环耗尽（12 轮工具调用未收敛）→ 优雅降级：回最近一轮累积的助手文本，不再抛异常
        for (int i = 0; i < 12; i++) {
            String event = i == 10
                    ? "{\"choices\":[{\"delta\":{\"content\":\"正在为您规划\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}]}}]}"
                    : toolCall("weather", "{\"city\":\"杭州\"}");
            server.enqueue(sse(event));
        }
        AtomicBoolean errored = new AtomicBoolean();
        OnlineSpeechResult result = provider((name, args) -> "晴天")
                .process(new byte[]{1, 2}, context(), "u-loop-text",
                        new OnlineAudioSink() {
                            @Override public void onError(Throwable error) { errored.set(true); }
                        })
                .get(2, TimeUnit.SECONDS);

        assertEquals("text", result.reply().kind());
        assertEquals("正在为您规划", result.reply().speakText());
        assertFalse(errored.get(), "循环耗尽是优雅降级，不得向流报错");
        assertEquals(12, server.getRequestCount());
    }

    @Test
    void toolLoopExhaustionWithoutTextReturnsFallbackText() throws Exception {
        // 循环耗尽且无任何助手文本 → 兜底话术（正常回复收敛，不 abort 流）
        for (int i = 0; i < 12; i++) {
            server.enqueue(sse(toolCall("weather", "{\"city\":\"杭州\"}")));
        }
        AtomicBoolean errored = new AtomicBoolean();
        OnlineSpeechResult result = provider((name, args) -> "晴天")
                .process(new byte[]{1, 2}, context(), "u-loop-fallback",
                        new OnlineAudioSink() {
                            @Override public void onError(Throwable error) { errored.set(true); }
                        })
                .get(2, TimeUnit.SECONDS);

        assertEquals("text", result.reply().kind());
        assertEquals(QwenOmniSpeechProvider.TOOL_LOOP_FALLBACK_TEXT, result.reply().speakText());
        assertFalse(errored.get(), "循环耗尽是优雅降级，不得向流报错");
        assertEquals(12, server.getRequestCount());
    }

    @Test
    void toolLoopExhaustionWithValidatedCommandReturnsAction() throws Exception {
        // 循环耗尽但车控命令已通过工具校验 → 回 action（意图 + 模板话术），命令照常执行
        for (int i = 0; i < 12; i++) {
            String event = i == 3
                    ? toolCall("car_control", "{\"domain\":\"climate\",\"action\":\"power_on\"}")
                    : toolCall("weather", "{\"city\":\"杭州\"}");
            server.enqueue(sse(event));
        }
        OnlineSpeechResult result = provider((name, args) -> "unused")
                .process(new byte[]{1, 2}, context(), "u-loop-action")
                .get(2, TimeUnit.SECONDS);

        assertEquals("action", result.reply().kind());
        assertEquals("climate", result.reply().intent().domain());
        assertEquals("power_on", result.reply().intent().intent());
        assertEquals("好的，空调已打开", result.reply().speakText());
        assertEquals(12, server.getRequestCount());
    }

    private static SessionContext context() {
        return new SessionContext("s1", "zh-CN", Map.of());
    }

    private static MockResponse sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) body.append("data: ").append(event).append("\n\n");
        body.append("data: [DONE]\n\n");
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body.toString());
    }

    private static String delta(String field, String value) {
        return "{\"choices\":[{\"delta\":{\"" + field + "\":\"" + value + "\"}}]}";
    }

    private static String toolCall(String name, String arguments) {
        return "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":\"" + arguments.replace("\"", "\\\"") + "\"}}]}}]}";
    }

    private static String audio(byte[] pcm) {
        return audioFragment(Base64.getEncoder().encodeToString(pcm));
    }

    private static String audioFragment(String base64) {
        return "{\"choices\":[{\"delta\":{\"audio\":{\"data\":\""
                + base64 + "\"}}}]}";
    }
}
