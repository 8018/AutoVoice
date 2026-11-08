package com.autovoice.voiceengine.local

import com.autovoice.localcommandrules.LocalCommandRules
import com.autovoice.voicecore.*

/** NLU consumes the existing real ASR final; no audio recognition, transcript callback or upload. */
class TextRuleNluEngine : NluEngine<NluResult> {
    override suspend fun understand(turnId: String,segment: ByteArray,asr: AsrResult?): NluResult =
        NluResult(if (asr?.isFinal == true && asr.text.isNotBlank()) LocalCommandRules.strict(asr.text) else Intent.unknown("rule.text"))
}
