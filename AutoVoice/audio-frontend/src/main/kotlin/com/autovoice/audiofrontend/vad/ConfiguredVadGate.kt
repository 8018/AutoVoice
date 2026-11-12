package com.autovoice.audiofrontend.vad

import com.autovoice.voicecore.VadConfig

/** Maps the demo's segmentation VAD settings to the legacy 32 ms probability gate. */
fun VadConfig.createSegmentationGate(): VoiceActivityGate =
    VoiceActivityGate(
        threshold = threshold.toFloat(),
        minSpeechMs = minSpeechMs.toInt(),
        minSilenceMs = minSilenceMs.toInt(),
    )
