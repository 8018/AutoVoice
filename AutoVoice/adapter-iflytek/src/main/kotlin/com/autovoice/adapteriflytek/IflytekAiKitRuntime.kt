package com.autovoice.adapteriflytek

import android.content.Context
import com.autovoice.voicecore.health.ComponentReporter
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.CoreListener
import com.iflytek.aikit.core.ErrType
import com.iflytek.aikit.core.LogLvl
import java.security.MessageDigest

/** Existing process SDK owner; only its initialization state and diagnostic port are extracted. */
internal object IflytekAiKitRuntime {
    private val initialization = IflytekRuntimeInitialization()
    fun bindReporter(reporter: ComponentReporter) = initialization.bindReporter(reporter)

    fun ensureInitialized(context: Context, appId: String, apiKey: String, apiSecret: String, workDir: String,
        beforeAuth: () -> Unit = {}) {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(listOf(appId, apiKey, apiSecret, workDir).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val appContext = context.applicationContext
        initialization.ensure(fingerprint, appId.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank(), beforeAuth) { onAuth, onFailure ->
            AiHelper.getInst().registerListener(object : CoreListener {
                override fun onAuthStateChange(type: ErrType, code: Int) { if (type == ErrType.AUTH) onAuth(code) }
            })
            AiHelper.getInst().setLogInfo(LogLvl.ERROR, 1, "${workDir.trimEnd('/')}/aikit/aeeLog.txt")
            val params = BaseLibrary.Params.builder().appId(appId).apiKey(apiKey).apiSecret(apiSecret).workDir(workDir).build()
            Thread({
                try { AiHelper.getInst().initEntry(appContext, params) }
                catch (failure: Throwable) { onFailure(failure) }
            }, "iflytek-aikit-init").apply { isDaemon = true }.start()
        }
    }
}

/** App injects a PROCESS reporter; this bridge has no Activity/ViewModel lifecycle ownership. */
object IflytekRuntimeStatus { fun attach(reporter: ComponentReporter) = IflytekAiKitRuntime.bindReporter(reporter) }
