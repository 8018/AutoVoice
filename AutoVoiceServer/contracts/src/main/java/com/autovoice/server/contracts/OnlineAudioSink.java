package com.autovoice.server.contracts;

/** 增量 S2S 音频出口。音频固定为 24kHz、单声道、PCM s16le。 */
public interface OnlineAudioSink {

    OnlineAudioSink NOOP = new OnlineAudioSink() {};

    default void onStart(int sampleRate, int channels, String encoding) {}

    default void onChunk(byte[] pcm) {}

    default void onComplete(String speakText, Intent intent) {}

    /**
     * 音频流结束，并携带用户输入语音的最终识别文本。
     * 旧 sink 只实现二参版本时保持兼容。
     */
    default void onComplete(String speakText, Intent intent, String asrText) {
        onComplete(speakText, intent);
    }

    default void onError(Throwable error) {}
}
