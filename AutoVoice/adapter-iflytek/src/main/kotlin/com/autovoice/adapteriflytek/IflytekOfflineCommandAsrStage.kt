package com.autovoice.adapteriflytek

import android.content.Context
import com.iflytek.aikit.core.AiAudio
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiStatus
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.CoreListener
import com.iflytek.aikit.core.ErrType
import com.iflytek.aikit.core.LogLvl
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 讯飞离线命令词识别（AIKit AEE API，能力 ID [ABILITY_ID]），
 * 按 SDK demo（CNENEsrActivity）主流程逐段适配：
 *
 * ```
 * initEntry(授权) → engineInit → loadData(FSA 命令词) → specifyDataSet
 * → start → write(BEGIN/CONTINUE/END) → read → end → engineUnInit
 * ```
 *
 * 与 [FakeCommandAsrProvider] 同一边界：`recognize(pcm: ByteArray): String?`，
 * 供端侧本地链（local.asr=iflytek 或 iflytek.fake-cmd）装配。
 *
 * 凭据（appid/apiKey/apiSecret）由外部注入（构造参数），不写死在代码：
 * 未配置/授权未就绪时 [recognize] 抛 [IllegalStateException]，消息见 [NOT_CONFIGURED_MSG]
 * （brief 明文"讯飞离线命令词 SDK 未配置，请切换 local.asr=iflytek.fake-cmd"）。
 *
 * 词表（[COMMAND_WORDS]）= 车控命令集合：打开空调/关闭空调/空调调到X度/打开车窗/关闭车窗，
 * 以 FSA 命令词文件（GBK 编码，与 SDK 归档 resource/CNENESR/fsa/cn_fsa.txt 格式一致）
 * 形式加载到引擎，缺文件时按词表自动生成。
 *
 * 注意：SDK 依赖 native so + Android Context，只能在真机运行；
 * 无真机/无授权时链路应切 local.asr=iflytek.fake-cmd。
 */
class IflytekOfflineCommandAsrStage(
    /** 讯飞开放平台 appid（体验版离线命令词授权），由用户侧提供。 */
    val appId: String,
    /** 讯飞开放平台 apiKey，由用户侧提供。 */
    val apiKey: String,
    /** 讯飞开放平台 apiSecret，由用户侧提供。 */
    val apiSecret: String,
    /** SDK 工作目录，存放离线资源（CNENESR 模型）与日志，需有读写权限。 */
    val workDir: String = DEFAULT_WORK_DIR,
    /** FSA 命令词文件路径；不存在时按 [COMMAND_WORDS] 自动生成。 */
    val fsaPath: String = DEFAULT_WORK_DIR + "fsa/cn_fsa.txt",
    /** 0=中文（demo 默认），1=英文。 */
    val languageType: Int = LANGUAGE_CN,
) {

    /** 阶段名，供本地链装配与日志使用。 */
    val name: String = "iflytek.offline-cmd"

    @Volatile
    private var initialized = false

    @Volatile
    private var authCode = -1

    private val authLatch = CountDownLatch(1)
    private val resultLatch = CountDownLatch(1)
    private val pendingResult = AtomicReference<String?>(null)
    private val lastError = AtomicReference<String?>(null)

    /** 引擎调用单线程串行执行（与 demo 的 Handler 线程模型一致），会话之间互不干扰。 */
    private val engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "iflytek-esr-engine").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ 初始化

    /** 授权（initEntry）+ 引擎初始化 + 命令词加载。调用一次即可，幂等。 */
    fun init(context: Context) {
        checkCredentials()
        if (initialized) return
        // 能力结果回调与授权回调都注册在 initEntry 之前，避免丢失事件（与 demo 一致）
        AiHelper.getInst().registerListener(ABILITY_ID, abilityListener)
        AiHelper.getInst().registerListener(coreListener)
        AiHelper.getInst().setLogInfo(LogLvl.ERROR, 1, "$workDir/aikit/aeeLog.txt")
        val params = BaseLibrary.Params.builder()
            .appId(appId)
            .apiKey(apiKey)
            .apiSecret(apiSecret)
            .workDir(workDir)
            .build()
        // 初始化（含首次联网鉴权）放后台线程，与 demo 一致
        Thread {
            try {
                AiHelper.getInst().initEntry(context, params)
            } catch (t: Throwable) {
                lastError.set(t.message ?: t.javaClass.simpleName)
                authLatch.countDown()
            }
        }.start()
        val authed = authLatch.await(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!authed) {
            throw IllegalStateException("$NOT_CONFIGURED_MSG（SDK 授权超时：检查 appid/apiKey/apiSecret 与网络，并确认离线命令词能力已开通）")
        }
        if (authCode != 0) {
            throw IllegalStateException("$NOT_CONFIGURED_MSG（SDK 授权失败 code=$authCode，授权凭据在用户侧，请确认体验版授权已绑定该 appid）")
        }
        val initRet = runOnEngine { initEngine() }
        if (initRet != 0) {
            throw IllegalStateException("$NOT_CONFIGURED_MSG（引擎初始化失败 code=$initRet，请确认离线资源已推送到 $workDir（SDK 归档 resource/CNENESR））")
        }
        initialized = true
    }

    private fun initEngine(): Int {
        val resourceDir = File(workDir, "CNENESR")
        if (!resourceDir.isDirectory) {
            throw IllegalStateException(
                "讯飞离线命令词离线资源缺失：请将 SDK 归档 resource/CNENESR 推送到 $workDir（含 e75f07b62_*.bin 模型），" +
                    NOT_CONFIGURED_MSG,
            )
        }
        val engineBuilder = AiRequest.builder()
        engineBuilder.param("decNetType", "fsa")
        engineBuilder.param("punishCoefficient", 0.0)
        engineBuilder.param("wfst_addType", languageType) // 0 中文，1 英文
        var ret = AiHelper.getInst().engineInit(ABILITY_ID, engineBuilder.build())
        if (ret != 0) return ret
        // FSA 命令词文件不存在则按词表自动生成（GBK 编码，与 SDK 样例格式一致）
        ensureCommandWordFile()
        val customBuilder = AiRequest.builder()
        customBuilder.customText("FSA", fsaPath, 0)
        ret = AiHelper.getInst().loadData(ABILITY_ID, customBuilder.build())
        if (ret != 0) return ret
        val dataSetIndex = intArrayOf(0)
        return AiHelper.getInst().specifyDataSet(ABILITY_ID, "FSA", dataSetIndex)
    }

    // ------------------------------------------------------------------ 识别

    /**
     * 识别一段 16bit/16K 单声道 PCM：返回命令词文本，未检出语音返回 null。
     * 未配置（凭据缺失/未初始化/授权失败）时抛 [IllegalStateException]，消息见 [NOT_CONFIGURED_MSG]。
     */
    @Synchronized
    fun recognize(pcm: ByteArray): String? {
        checkReady()
        if (pcm.isEmpty()) return null
        return runOnEngine { runSession(pcm) }
    }

    /** 单次识别会话：start → write(BEGIN/CONTINUE/END) → read → 等结果 → end。 */
    private fun runSession(pcm: ByteArray): String? {
        resultLatch.reset()
        pendingResult.set(null)
        lastError.set(null)

        val handle = startSession()
        try {
            feedPcm(handle, pcm)
            val done = resultLatch.await(RECOGNIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!done) {
                throw IllegalStateException("讯飞离线命令词识别超时")
            }
            lastError.get()?.let { throw IllegalStateException("讯飞离线命令词识别失败：$it") }
            return pendingResult.get()
        } finally {
            endSession(handle)
        }
    }

    private fun startSession(): AiHandle {
        val paramBuilder = AiRequest.builder()
        // 参数取自 demo CNENEsrActivity.start()，与 demo 逐字对齐
        paramBuilder.param("languageType", languageType) // 0 中文，1 英文
        paramBuilder.param("vadEndGap", 60) // 子句分割时间间隔，中文建议 60
        paramBuilder.param("vadOn", true) // vad 开关
        paramBuilder.param("beamThreshold", 20) // 中文建议 20
        paramBuilder.param("hisGramThreshold", 3000) // 建议值 3000
        paramBuilder.param("vadLinkOn", false) // vad 子句连接开关
        paramBuilder.param("vadSpeechEnd", 80) // vad 后端点
        paramBuilder.param("vadResponsetime", 1000) // vad 前端点
        paramBuilder.param("postprocOn", false) // 后处理开关
        paramBuilder.param("vadEnergyThreshold", 9) // vad 能量阈值
        paramBuilder.param("vadThreshold", 0.1332) // vad 阈值
        val handle = AiHelper.getInst().start(ABILITY_ID, paramBuilder.build(), null)
        if (!handle.isSuccess) {
            throw IllegalStateException("讯飞引擎 start 失败：code=${handle.code}")
        }
        return handle
    }

    /**
     * 分片写入 PCM（与 demo WRITE_BY_READFILE 一致：320 字节/片，
     * 首帧 BEGIN、末帧 END、其余 CONTINUE，每片 write 后 read 取结果）。
     */
    private fun feedPcm(handle: AiHandle, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val chunkEnd = minOf(offset + CHUNK_SIZE, pcm.size)
            val status = when {
                offset == 0 && chunkEnd < pcm.size -> AiStatus.BEGIN
                chunkEnd >= pcm.size -> AiStatus.END
                else -> AiStatus.CONTINUE
            }
            val audio = AiAudio.get("audio")
                .data(pcm.copyOfRange(offset, chunkEnd))
                .status(status)
                .valid()
            val dataBuilder = AiRequest.builder()
            dataBuilder.payload(audio)
            val writeRet = AiHelper.getInst().write(dataBuilder.build(), handle)
            if (writeRet != 0) {
                throw IllegalStateException("讯飞引擎 write 失败：$writeRet")
            }
            val readRet = AiHelper.getInst().read(ABILITY_ID, handle)
            if (readRet != 0) {
                throw IllegalStateException("讯飞引擎 read 失败：$readRet")
            }
            offset = chunkEnd
        }
    }

    private fun endSession(handle: AiHandle) {
        try {
            AiHelper.getInst().end(handle)
        } catch (t: Throwable) {
            // 结束会话失败不影响已识别结果
        }
    }

    /** 释放引擎与线程资源。 */
    fun release() {
        if (initialized) {
            try {
                runOnEngine {
                    AiHelper.getInst().unLoadData(ABILITY_ID, "FSA", 0)
                    AiHelper.getInst().engineUnInit(ABILITY_ID)
                }
            } catch (t: Throwable) {
                // 释放失败可忽略
            }
            initialized = false
        }
        engineExecutor.shutdownNow()
    }

    // ------------------------------------------------------------------ 回调

    private val coreListener = object : CoreListener {
        override fun onAuthStateChange(type: ErrType, code: Int) {
            if (type == ErrType.AUTH) {
                authCode = code
                authLatch.countDown()
            }
        }
    }

    private val abilityListener = object : AiListener {
        override fun onResult(handleID: Int, outputData: List<AiResponse>, usrContext: Any?) {
            if (outputData == null || outputData.isEmpty()) return
            for (response in outputData) {
                val key = response.key
                val value = response.value
                // plain：一句话的最终结果（demo 注释：plain 是每段话的最终结果）
                if (key?.contains("plain") == true && value != null && value.isNotEmpty()) {
                    pendingResult.set(String(value, GBK).trim())
                }
                // status == 2 表示结果结束
                if (response.status == RESULT_END_STATUS) {
                    resultLatch.countDown()
                }
            }
        }

        override fun onEvent(i: Int, i1: Int, list: List<AiResponse>?, o: Any?) {
            // demo 仅打日志；未用
        }

        override fun onError(i: Int, i1: Int, s: String?, o: Any?) {
            lastError.set(s ?: "err code=$i1")
            resultLatch.countDown()
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 未配置/未就绪检查（fake 与真实引擎共用的明确报错）。 */
    private fun checkReady() {
        checkCredentials()
        if (!initialized) {
            throw IllegalStateException("$NOT_CONFIGURED_MSG（SDK 尚未初始化，请先调用 init 并完成授权）")
        }
    }

    private fun checkCredentials() {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw IllegalStateException(NOT_CONFIGURED_MSG)
        }
    }

    /** 在引擎单线程上执行引擎调用，并解包业务异常。 */
    private fun <T> runOnEngine(block: () -> T): T {
        try {
            return engineExecutor.submit(Callable { block() }).get(ENGINE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IllegalStateException("讯飞离线命令词引擎调用超时")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("讯飞离线命令词引擎调用被中断")
        }
    }

    /** 写 FSA 命令词文件（GBK 编码）；已存在则跳过。 */
    fun ensureCommandWordFile() {
        val file = File(fsaPath)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeBytes(fsaContent().toByteArray(GBK))
        }
    }

    companion object {
        /** 离线中英命令词能力 ID（SDK 归档 resource/CNENESR/e75f07b62_*.bin 同名）。 */
        const val ABILITY_ID = "e75f07b62"

        /** brief 明文：未配置/授权未就绪时的报错消息。 */
        const val NOT_CONFIGURED_MSG = "讯飞离线命令词 SDK 未配置，请切换 local.asr=iflytek.fake-cmd"

        const val DEFAULT_WORK_DIR = "/sdcard/iflytek/"
        const val LANGUAGE_CN = 0
        const val LANGUAGE_EN = 1

        private const val AUTH_TIMEOUT_MS = 20_000L
        private const val RECOGNIZE_TIMEOUT_MS = 15_000L
        private const val ENGINE_CALL_TIMEOUT_MS = 30_000L

        /** 结果结束状态（demo 中 AiStatus 值 2）。 */
        private const val RESULT_END_STATUS = 2

        /** 音频写入分片大小（demo 常量 320，16bit/16K 单声道）。 */
        private const val CHUNK_SIZE = 320

        /** 引擎侧 FSA 命令词文件编码（与 SDK 归档样例 cn_fsa.txt 一致）。 */
        val GBK: Charset = Charset.forName("GBK")

        /** 车控命令词表：新增命令只加表项（温度按 16..30 度枚举，FSA 为闭合词表）。 */
        val COMMAND_WORDS: List<String> = buildList {
            add("打开空调")
            add("关闭空调")
            for (t in 16..30) add("空调调到${t}度")
            add("打开车窗")
            add("关闭车窗")
        }

        /** 生成 FSA 命令词文件内容（格式与 SDK 归档 resource/CNENESR/fsa/cn_fsa.txt 逐字一致）。 */
        fun fsaContent(): String = buildString {
            append("#FSA 1.0;\r\n")
            append("0\t1\t<esr>\r\n")
            append(";\r\n")
            append("<esr>:")
            append(COMMAND_WORDS.joinToString("|"))
            append(";\r\n")
        }
    }
}

/** [CountDownLatch] 复用前复位。 */
private fun CountDownLatch.reset() {
    while (count > 0) countDown()
}
