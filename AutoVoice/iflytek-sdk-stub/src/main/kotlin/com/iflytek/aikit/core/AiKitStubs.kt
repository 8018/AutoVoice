@file:Suppress("UNUSED_PARAMETER")

package com.iflytek.aikit.core

import android.content.Context

/**
 * Compile-only shape of the proprietary AIKit API for CI.
 *
 * This module contains no recognition implementation and must never be packaged in a production APK.
 */
enum class AiStatus { BEGIN, CONTINUE, END, ONCE }
enum class ErrType { AUTH, HTTP, UNKNOWN }
enum class LogLvl { ERROR, WARN, INFO, DEBUG }

data class AiHandle(val code: Int = STUB_ERROR) {
    val isSuccess: Boolean get() = code == 0

    companion object { private const val STUB_ERROR = -1 }
}

class AiResponse(
    val key: String? = null,
    val value: ByteArray? = null,
    val status: Int = 0,
)

interface AiListener {
    fun onResult(handleID: Int, outputData: List<AiResponse>?, usrContext: Any?)
    fun onEvent(i: Int, i1: Int, list: List<AiResponse>?, o: Any?)
    fun onError(i: Int, i1: Int, s: String?, o: Any?)
}

fun interface CoreListener {
    fun onAuthStateChange(type: ErrType, code: Int)
}

class AiAudio private constructor() {
    fun data(value: ByteArray) = this
    fun status(value: AiStatus) = this
    fun valid() = this

    companion object { fun get(key: String) = AiAudio() }
}

class AiRequest private constructor() {
    class Builder {
        fun param(key: String, value: Any) = this
        fun customText(type: String, path: String, index: Int) = this
        fun payload(value: AiAudio) = this
        fun build() = AiRequest()
    }

    companion object { fun builder() = Builder() }
}

object BaseLibrary {
    class Params private constructor() {
        class Builder {
            fun appId(value: String) = this
            fun apiKey(value: String) = this
            fun apiSecret(value: String) = this
            fun workDir(value: String) = this
            fun build() = Params()
        }

        companion object { fun builder() = Builder() }
    }
}

class AiHelper private constructor() {
    fun registerListener(ability: String, listener: AiListener) = Unit
    fun registerListener(listener: CoreListener) = Unit
    fun setLogInfo(level: LogLvl, mode: Int, path: String) = Unit
    fun initEntry(context: Context, params: BaseLibrary.Params): Int = STUB_ERROR
    fun engineInit(ability: String, request: AiRequest): Int = STUB_ERROR
    fun engineUnInit(ability: String): Int = STUB_ERROR
    fun loadData(ability: String, request: AiRequest): Int = STUB_ERROR
    fun unLoadData(ability: String, type: String, index: Int): Int = STUB_ERROR
    fun specifyDataSet(ability: String, type: String, indices: IntArray): Int = STUB_ERROR
    fun start(ability: String, request: AiRequest, context: Any?): AiHandle = AiHandle()
    fun write(request: AiRequest, handle: AiHandle): Int = STUB_ERROR
    fun read(ability: String, handle: AiHandle): Int = STUB_ERROR
    fun end(handle: AiHandle): Int = STUB_ERROR

    companion object {
        private const val STUB_ERROR = -1
        private val INSTANCE = AiHelper()
        fun getInst(): AiHelper = INSTANCE
    }
}
