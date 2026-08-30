package com.autovoice.voicecore.dialog

/** 能把临时 VAD capture 晋升为真实对话轮的证据。 */
enum class AdmissionEvidence {
    LOCAL_ASR,
    CLOUD_ASR,
    LOCAL_SEMANTIC,
    CLOUD_FINAL_SEMANTIC,
}

data class AdmittedTurn(val turnId: String, val evidence: AdmissionEvidence)

/**
 * VAD 误报隔离层。每次只保留一个待确认 capture，确认操作幂等。
 * partial/final ASR 均可确认，但空文本不能确认；没有 ASR 的链路可由有效最终语义兜底。
 */
class TurnAdmissionGate {
    private var pendingCaptureId: String? = null
    private var admitted: AdmittedTurn? = null

    @Synchronized
    fun open(captureId: String) {
        pendingCaptureId = captureId
        admitted = null
    }

    @Synchronized
    fun confirmText(captureId: String, text: String, source: AdmissionEvidence): AdmittedTurn? {
        if (text.isBlank()) return null
        return confirm(captureId, source)
    }

    @Synchronized
    fun confirmSemantic(captureId: String, source: AdmissionEvidence): AdmittedTurn? {
        require(source == AdmissionEvidence.LOCAL_SEMANTIC || source == AdmissionEvidence.CLOUD_FINAL_SEMANTIC)
        return confirm(captureId, source)
    }

    @Synchronized
    fun reject(captureId: String): Boolean {
        if (pendingCaptureId != captureId || admitted != null) return false
        pendingCaptureId = null
        return true
    }

    @Synchronized
    fun current(): AdmittedTurn? = admitted

    private fun confirm(captureId: String, source: AdmissionEvidence): AdmittedTurn? {
        if (pendingCaptureId != captureId) return null
        admitted?.let { return it }
        return AdmittedTurn(captureId, source).also { admitted = it }
    }
}
