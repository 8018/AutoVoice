package com.autovoice.server.contracts;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic cloud NLU for dialogue lifecycle commands, evaluated before the business LLM. */
public final class DialogueControlNlu {

    public static final String EXIT_DIALOGUE = "exit_dialogue";
    public static final String SOURCE = "cloud.nlu.dialogue-control";

    private static final Set<String> EXIT_PHRASES = Set.of(
            "退出", "退出对话", "结束对话", "退出当前对话", "结束当前对话",
            "退出会话", "结束会话", "退出当前会话", "结束当前会话");

    private DialogueControlNlu() {
    }

    /** Exact-after-normalization matching avoids treating requests such as “退出导航后回家” as exit. */
    public static Optional<Intent> understand(String text) {
        if (!EXIT_PHRASES.contains(normalize(text))) return Optional.empty();
        return Optional.of(Intent.of("1.0", "conversation", EXIT_DIALOGUE, Map.of(), 1.0,
                SOURCE, null));
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？、,.!?;；:：]", "");
    }
}
