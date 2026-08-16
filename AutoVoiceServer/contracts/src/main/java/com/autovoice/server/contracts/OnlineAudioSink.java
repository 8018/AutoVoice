package com.autovoice.server.contracts;

/** 增量 S2S 音频出口。音频固定为 24kHz、单声道、PCM s16le。 */
public interface OnlineAudioSink {

    OnlineAudioSink NOOP = new OnlineAudioSink() {};

    default void onStart(int sampleRate, int channels, String encoding) {}

    default void onChunk(byte[] pcm) {}

    default void onComplete(String speakText, Intent intent) {}

    default void onError(Throwable error) {}
}
