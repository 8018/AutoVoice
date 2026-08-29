package com.autovoice.server.contracts;

/** Realtime 模型的多轮事件出口。每个模型回答各自成对调用 onStart/onComplete。 */
public interface RealtimeChatSink extends OnlineAudioSink {

    /** 服务端语义 VAD 检测到用户开始说话；端侧仅停止当前播放，不停止录音或上行。 */
    default void onUserSpeechStarted() {}

    /** 会话因远端关闭或错误结束。 */
    default void onSessionClosed(Throwable error) {}
}
