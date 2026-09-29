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
 * 本层不读取或判断 ASR 文本；ASR 模块明确发出“话语成立”后才确认。没有 ASR 的链路可由
 * 有效最终语义兜底。
 */
class TurnAdmissionGate {
    private var pendingCaptureId: String? = null
    private var admitted: AdmittedTurn? = null
    private var finalizedCaptureId: String? = null
    private val retired = ArrayDeque<String>()

    @Synchronized
    fun open(captureId: String) {
        if (captureId in retired) return
        if (pendingCaptureId == captureId) return
        pendingCaptureId = captureId
        admitted = null
        finalizedCaptureId = null
    }

    @Synchronized
    fun confirmAsr(captureId: String, source: AdmissionEvidence): AdmittedTurn? {
        require(source == AdmissionEvidence.LOCAL_ASR || source == AdmissionEvidence.CLOUD_ASR)
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
        finalizedCaptureId = null
        return true
    }

    /** The endpoint can precede ASR admission. Retain it until the candidate is admitted. */
    @Synchronized
    fun finalizeInput(captureId: String): Boolean {
        if (pendingCaptureId != captureId) return false
        if (finalizedCaptureId == captureId) return false
        finalizedCaptureId = captureId
        return true
    }

    @Synchronized
    fun isInputFinalized(captureId: String): Boolean = finalizedCaptureId == captureId

    /** A settled response cannot be re-admitted by a delayed ASR or semantic callback. */
    @Synchronized
    fun retire(captureId: String) {
        if (captureId.isBlank()) return
        if (captureId !in retired) {
            retired.addLast(captureId)
            if (retired.size > 64) retired.removeFirst()
        }
        if (pendingCaptureId == captureId) {
            pendingCaptureId = null
            admitted = null
            finalizedCaptureId = null
        }
    }

    @Synchronized
    fun current(): AdmittedTurn? = admitted

    @Synchronized
    fun owns(captureId: String): Boolean = pendingCaptureId == captureId

    @Synchronized
    fun reset() {
        pendingCaptureId = null
        admitted = null
        finalizedCaptureId = null
    }

    private fun confirm(captureId: String, source: AdmissionEvidence): AdmittedTurn? {
        if (pendingCaptureId != captureId) return null
        admitted?.let { return it }
        return AdmittedTurn(captureId, source).also { admitted = it }
    }
}
