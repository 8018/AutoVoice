package com.autovoice.adapteriflytek

import android.content.Context
import com.iflytek.aikit.core.AiAudio
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 讯飞离线唤醒 PCM 观察者（IVW，能力 [ABILITY_ID]）。本类不创建 AudioRecord，只消费
 * App 唯一录音流分发出的 16k/16bit/mono PCM；因此可与 VAD、降噪和 ASR 安全共享麦克风。
 */
class IflytekWakeWordObserver(
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
    val keyword: String = DEFAULT_KEYWORD,
    private val workDir: String = DEFAULT_WORK_DIR,
    private val onWake: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    @Volatile
    private var initialized = false

    @Volatile
    private var handle: AiHandle? = null

    private var firstFrame = true
    private var dataLoaded = false
    private val armed = AtomicBoolean(false)
    private val wakeDelivered = AtomicBoolean(false)

    fun init(context: Context) {
        if (initialized) return
        checkCredentials()
        AiHelper.getInst().registerListener(ABILITY_ID, listener)
        IflytekAiKitRuntime.ensureInitialized(context, appId, apiKey, apiSecret, workDir)
        initialized = true
    }

    /** 装载唤醒词并启用观察；可在每轮结束后重复调用。 */
    @Synchronized
    fun arm() {
        check(initialized) { "$NOT_CONFIGURED_MSG（尚未初始化）" }
        // IVW 是持续会话：命中一次后官方 demo 仍复用同一个 handle。业务轮次期间只暂停
        // 喂帧，重新布防时恢复该会话，避免频繁 end/start 触发 SDK 10005 状态冲突。
        if (handle != null) {
            wakeDelivered.set(false)
            armed.set(true)
            return
        }
        ensureResources()
        ensureKeywordFile()
        if (dataLoaded) {
            AiHelper.getInst().unLoadData(ABILITY_ID, DATA_TYPE, 0)
            dataLoaded = false
        }
        val custom = AiRequest.builder()
            .customText(DATA_TYPE, keywordPath(), 0)
            .build()
        var result = AiHelper.getInst().loadData(ABILITY_ID, custom)
        if (result != 0) throw IllegalStateException("讯飞离线唤醒 loadData 失败 code=$result")
        result = AiHelper.getInst().specifyDataSet(ABILITY_ID, DATA_TYPE, intArrayOf(0))
        if (result != 0) throw IllegalStateException("讯飞离线唤醒 specifyDataSet 失败 code=$result")
        dataLoaded = true

        val request = AiRequest.builder()
            .param("wdec_param_nCmThreshold", "0 0:800")
            .param("gramLoad", true)
            .build()
        val newHandle = AiHelper.getInst().start(ABILITY_ID, request, null)
        if (!newHandle.isSuccess) {
            throw IllegalStateException("讯飞离线唤醒 start 失败 code=${newHandle.code}")
        }
        firstFrame = true
        wakeDelivered.set(false)
        handle = newHandle
        armed.set(true)
    }

    /** 接收共享录音流的一块 PCM；未 arm 或已经命中时为 no-op。 */
    @Synchronized
    fun accept(pcm: ByteArray) {
        val current = handle ?: return
        if (pcm.isEmpty() || !armed.get() || wakeDelivered.get()) return
        val audio = AiAudio.get("wav")
            .data(pcm)
            .status(if (firstFrame) AiStatus.BEGIN else AiStatus.CONTINUE)
            .valid()
        firstFrame = false
        val result = AiHelper.getInst().write(AiRequest.builder().payload(audio).build(), current)
        if (result != 0) throw IllegalStateException("讯飞离线唤醒 write 失败 code=$result")
    }

    /** 业务语音轮次期间暂停喂帧；保留 IVW 原生会话供下一轮直接恢复。 */
    fun pause() {
        armed.set(false)
    }

    /** 完整结束 IVW 会话；仅用于退后台、错误恢复或组件销毁。 */
    @Synchronized
    fun disarm() {
        armed.set(false)
        val current = handle
        handle = null
        if (current != null) runCatching { AiHelper.getInst().end(current) }
        if (dataLoaded) {
            runCatching { AiHelper.getInst().unLoadData(ABILITY_ID, DATA_TYPE, 0) }
            dataLoaded = false
        }
        firstFrame = true
        wakeDelivered.set(false)
    }

    override fun close() = disarm()

    /** 写入 SDK 需要的 UTF-8 唤醒词文件；资源模型仍按官方布局放在 workDir/ivw。 */
    fun ensureKeywordFile() {
        val file = File(keywordPath())
        file.parentFile?.mkdirs()
        val expected = "$keyword;\n"
        if (runCatching { file.readText(Charsets.UTF_8) }.getOrNull() != expected) {
            // SDK 会生成 keyword.bin；文本变化时必须删除旧缓存，否则仍可能匹配旧词。
            File(file.parentFile, KEYWORD_CACHE_FILE).delete()
            file.writeText(expected, Charsets.UTF_8)
        }
    }

    private fun ensureResources() {
        val resourceDir = File(workDir, RESOURCE_DIR)
        val missing = REQUIRED_MODELS.filter { !File(resourceDir, it).isFile }
        if (!resourceDir.isDirectory || missing.isNotEmpty()) {
            throw IllegalStateException(
                "$NOT_CONFIGURED_MSG，缺少资源：${missing.joinToString()}。" +
                    "请把 SDK resource/ivw 推送到 ${workDir.trimEnd('/')}/ivw/",
            )
        }
    }

    private fun keywordPath(): String =
        "${workDir.trimEnd('/')}/$RESOURCE_DIR/$KEYWORD_FILE"

    private fun checkCredentials() {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw IllegalStateException(NOT_CONFIGURED_MSG)
        }
    }

    private val listener = object : AiListener {
        override fun onResult(handleID: Int, outputData: List<AiResponse>?, usrContext: Any?) {
            val wake = outputData.orEmpty().any {
                it.key == WAKE_RESULT_KEY && it.value?.isNotEmpty() == true
            }
            if (wake && armed.compareAndSet(true, false) && wakeDelivered.compareAndSet(false, true)) {
                onWake(keyword)
            }
        }

        override fun onEvent(i: Int, i1: Int, list: List<AiResponse>?, o: Any?) = Unit

        override fun onError(i: Int, i1: Int, s: String?, o: Any?) {
            armed.set(false)
            wakeDelivered.set(true)
            handle = null
            onError(IllegalStateException(s ?: "讯飞离线唤醒失败 code=$i1"))
        }
    }

    companion object {
        const val ABILITY_ID = "e867a88f2"
        const val DEFAULT_KEYWORD = "你好飞飞"
        const val DEFAULT_WORK_DIR = "/sdcard/iflytek/"
        const val NOT_CONFIGURED_MSG = "讯飞离线唤醒 SDK 未配置"

        private const val DATA_TYPE = "key_word"
        private const val RESOURCE_DIR = "ivw"
        private const val KEYWORD_FILE = "keyword.txt"
        private const val KEYWORD_CACHE_FILE = "keyword.bin"
        private const val WAKE_RESULT_KEY = "func_wake_up"
        val REQUIRED_MODELS = listOf("IVW_GRAM_1", "IVW_KEYWORD_1", "IVW_MLP_1", "IVW_FILLER_1")
    }
}
