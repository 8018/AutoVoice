package com.autovoice.server.speechclassic;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ClassicOnlineSpeechProviderTest {
    @Test
    void explicitSecondTurnSelectionBypassesLlm() throws Exception {
        SessionContext context = new SessionContext("s1", "zh-CN", Map.of());
        NavigationDialogState dialog = new NavigationDialogState();
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
}
