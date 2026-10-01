package com.autovoice.voiceengine.api

/** Local admission outcome; cloud completion is delivered separately as a semantic candidate. */
enum class TextSubmission { ACCEPTED, DUPLICATE_REQUEST, BUSY, NO_ROUTE }

/** Business requests explicit text through the engine, never through GatewayClient. */
interface TextInputPort {
    fun submitText(requestId: String, text: String): TextSubmission
    fun finalizeTextInput(requestId: String): Boolean
}
