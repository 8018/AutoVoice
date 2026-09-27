package com.autovoice.server.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueControlNluTest {

    @Test
    void recognizesExactExitAfterWhitespaceAndPunctuationNormalization() {
        Intent intent = DialogueControlNlu.understand(" 退出 当前 对话。 ").orElseThrow();
        assertEquals("conversation", intent.domain());
        assertEquals("exit_dialogue", intent.intent());
        assertEquals(DialogueControlNlu.SOURCE, intent.source());
    }

    @Test
    void doesNotCaptureARequestThatOnlyContainsExitAsPartOfAnotherCommand() {
        assertTrue(DialogueControlNlu.understand("退出导航后回家").isEmpty());
    }
}
