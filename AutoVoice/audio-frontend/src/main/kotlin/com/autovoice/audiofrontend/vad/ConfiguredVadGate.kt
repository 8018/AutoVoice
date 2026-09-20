package com.autovoice.audiofrontend.vad

import com.autovoice.voicecore.VadConfig

/** Maps the demo's segmentation VAD settings to the 32 ms Silero gate. */
fun VadConfig.createSegmentationGate(): VoiceActivityGate =
    VoiceActivityGate(
        threshold = threshold.toFloat(),
        minSpeechMs = minSpeechMs.toInt(),
        minSilenceMs = minSilenceMs.toInt(),
    )
