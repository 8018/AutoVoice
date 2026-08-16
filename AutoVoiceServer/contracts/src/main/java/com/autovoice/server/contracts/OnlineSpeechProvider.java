package com.autovoice.server.contracts;

import java.util.concurrent.CompletableFuture;

/**
 * 编译时选择的在线语音后端。
 *
 * <p>一份完整 PCM 同时交给云端离线命令识别和本接口；云端仲裁器负责拦截输出：空调
 * 离线命中时取消在线候选，否则放行在线结果。Classic 实现是 ASR → LLM，Omni 实现是
 * speech-to-speech。一个部署产物中只能装配一个实现。</p>
 */
public interface OnlineSpeechProvider {

    /** 启动在线候选；返回的 future 必须支持取消，并尽可能向底层网络调用传播取消。 */
    CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId);

    /** 支持增量音频的入口；Classic 默认退化为完整结果。 */
    default CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId, OnlineAudioSink audioSink) {
        return process(pcm16k, context, utteranceId);
    }

    /**
     * ASR/NLU 解耦入口：asrSink 独立产出用户原话；audioSink 承载回答文本/音频。
     * 旧实现保持兼容，未覆写时没有独立 ASR 事件。
     */
    default CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId,
            OnlineAudioSink audioSink, OnlineAsrSink asrSink) {
        return process(pcm16k, context, utteranceId, audioSink);
    }

    /** 用于 ready、日志和遥测的稳定后端标识（classic / qwen-omni）。 */
    String id();

    /** 后端要求的最小整轮超时；公共仲裁配置低于该值时装配层自动抬高。 */
    default long minimumTurnTimeoutMs() {
        return 0;
    }

    /** 端侧高优先级候选胜出时按话语取消；不支持的后端可依赖 future 取消。 */
    default void cancel(String utteranceId) {}
}
