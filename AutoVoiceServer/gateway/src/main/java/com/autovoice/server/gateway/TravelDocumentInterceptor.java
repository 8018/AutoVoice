package com.autovoice.server.gateway;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Turn-scoped stream interceptor after semantic arbitration and client business admission. */
final class TravelDocumentInterceptor {
    private enum State { WAITING, STREAMING, CLOSED }
    private static final int MAX_CHARS = 24_000;
    private final BooleanSupplier permitted;
    private final BiConsumer<String, String> forward;
    private State state = State.WAITING;
    private int chars;

    TravelDocumentInterceptor(BooleanSupplier permitted, BiConsumer<String, String> forward) {
        this.permitted = permitted;
        this.forward = forward;
    }

    synchronized boolean accept(String operation, String text) {
        if (!permitted.getAsBoolean() || state == State.CLOSED || operation == null) return false;
        String body = text == null ? "" : text;
        switch (operation) {
            case "start" -> {
                if (state != State.WAITING) return false;
                state = State.STREAMING;
            }
            case "delta" -> {
                if (state != State.STREAMING || body.isEmpty() || chars + body.length() > MAX_CHARS) return false;
                chars += body.length();
            }
            case "complete" -> {
                if (state != State.STREAMING) return false;
                state = State.CLOSED;
            }
            case "error" -> state = State.CLOSED;
            default -> { return false; }
        }
        forward.accept(operation, body);
        return true;
    }
}
