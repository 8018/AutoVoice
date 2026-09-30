package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;

/**
 * Explicit text input for the business domain. This bypasses ASR and never synthesizes PCM or
 * emits ASR events; the transport endpoint is responsible for validation and request admission.
 * Implementations return an empty {@link OnlineSpeechResult#asrText()}.
 */
public interface OnlineTextProvider {
    CompletableFuture<OnlineSpeechResult> processText(
            String text, SessionContext context, String utteranceId);
}
