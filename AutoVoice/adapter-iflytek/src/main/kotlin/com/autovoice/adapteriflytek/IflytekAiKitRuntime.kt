package com.autovoice.adapteriflytek

import android.content.Context
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.CoreListener
import com.iflytek.aikit.core.ErrType
import com.iflytek.aikit.core.LogLvl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 进程级 AIKit 运行时。离线命令词与离线唤醒共用同一个 SDK 单例和鉴权结果，避免两个
 * observer 分别调用 initEntry、覆盖 CoreListener 或在初始化竞态中得到错误状态。
 */
internal object IflytekAiKitRuntime {
    private enum class State { NEW, INITIALIZING, READY, FAILED }

    private val lock = Any()

    @Volatile
    private var state = State.NEW

    private var credentialKey = ""
    private var authCode = -1
    private var failure: Throwable? = null
    private var ready = CountDownLatch(1)

    fun ensureInitialized(
        context: Context,
        appId: String,
        apiKey: String,
        apiSecret: String,
        workDir: String,
    ) {
        require(appId.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()) {
            "讯飞 AIKit 凭据未配置"
        }
        val key = listOf(appId, apiKey, apiSecret, workDir).joinToString("\u0000")
        var initialize = false
        val latch: CountDownLatch
        synchronized(lock) {
            if (credentialKey.isNotEmpty() && credentialKey != key) {
                throw IllegalStateException("同一进程不能用不同凭据或 workDir 重复初始化讯飞 AIKit")
            }
            credentialKey = key
            when (state) {
                State.READY -> return
                State.FAILED -> throw IllegalStateException("讯飞 AIKit 初始化失败", failure)
                State.NEW -> {
                    state = State.INITIALIZING
                    initialize = true
                }
                State.INITIALIZING -> Unit
            }
            latch = ready
        }

        if (initialize) {
            AiHelper.getInst().registerListener(object : CoreListener {
                override fun onAuthStateChange(type: ErrType, code: Int) {
                    if (type != ErrType.AUTH) return
                    synchronized(lock) {
                        authCode = code
                        state = if (code == 0) State.READY else State.FAILED
                        if (code != 0) failure = IllegalStateException("SDK 授权失败 code=$code")
                        ready.countDown()
                    }
                }
            })
            AiHelper.getInst().setLogInfo(LogLvl.ERROR, 1, "${workDir.trimEnd('/')}/aikit/aeeLog.txt")
            val params = BaseLibrary.Params.builder()
                .appId(appId)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .workDir(workDir)
                .build()
            Thread({
                try {
                    // 不读取返回值：不同交付包的 Java 签名分别为 void/int，授权结果统一
                    // 以 CoreListener.AUTH 回调为准（官方 demo 也是该语义）。
                    AiHelper.getInst().initEntry(context.applicationContext, params)
                } catch (t: Throwable) {
                    fail(t)
                }
            }, "iflytek-aikit-init").apply { isDaemon = true }.start()
        }

        if (!latch.await(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail(IllegalStateException("SDK 授权超时"))
        }
        synchronized(lock) {
            if (state != State.READY || authCode != 0) {
                throw IllegalStateException("讯飞 AIKit 初始化失败", failure)
            }
        }
    }

    private fun fail(error: Throwable) {
        synchronized(lock) {
            if (state == State.READY) return
            failure = error
            state = State.FAILED
            ready.countDown()
        }
    }

    private const val AUTH_TIMEOUT_MS = 20_000L
}
