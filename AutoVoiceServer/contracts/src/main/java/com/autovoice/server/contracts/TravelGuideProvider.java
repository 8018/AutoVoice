package com.autovoice.server.contracts;

/** Prepares an LLM-classified travel task; generation starts only after client business admission. */
public interface TravelGuideProvider {
    TravelGuideProvider NONE = new TravelGuideProvider() {};

    default Reply prepare(Reply reply, String text, SessionContext context,
                          String utteranceId, OnlineAudioSink sink) { return reply; }

    default void admit(SessionContext context, String utteranceId) {}

    default void cancel(SessionContext context, String utteranceId) {}
}
