package com.autovoice.app.channel

import android.content.Context
import com.autovoice.adapteriflytek.IflytekWakeWordObserver
import com.autovoice.adapteriflytek.IflytekRuntimeStatus
import com.autovoice.app.BuildConfig
import com.autovoice.app.WakeWordPort
import com.autovoice.app.health.ChannelComponentReporting
import com.autovoice.voicecore.health.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class WakeWordCredentials(val appId: String, val apiKey: String, val apiSecret: String)

/** Both flavors observe the same SDK runtime; each operation keeps its original report identity. */
internal object WakeWordFactory {
    private data class Attempt(val reporting: ChannelComponentReporting?, val reporter: ComponentReporter, val token: ComponentToken,
        val initialized: AtomicBoolean = AtomicBoolean(), val failed: AtomicBoolean = AtomicBoolean())
    fun create(context: Context, onWake: (String) -> Unit, onError: (Throwable) -> Unit,
        reporting: (() -> ChannelComponentReporting)? = null,
        credentials: WakeWordCredentials = WakeWordCredentials(BuildConfig.XFYUN_APPID, BuildConfig.XFYUN_API_KEY, BuildConfig.XFYUN_API_SECRET),
    ): WakeWordPort {
        reporting?.invoke()?.let { IflytekRuntimeStatus.attach(it.reporter("iflytek.runtime")) }
        val active = AtomicReference<Attempt?>()
        fun failure(error: Throwable, attempt: Attempt) {
            error.rethrowCancellationOrFatal()
            attempt.failed.set(true)
            val h = attempt.reporting
            if (h != null) h.failure(attempt.reporter, attempt.token, error, ErrorCode.RESOURCE_MISSING, runtimeDependent = true)
            else attempt.reporter.report(attempt.token, ComponentUpdate.failed(initializationError(error, ErrorCode.RESOURCE_MISSING)))
        }
        val observer = try {
            IflytekWakeWordObserver(appId = credentials.appId, apiKey = credentials.apiKey, apiSecret = credentials.apiSecret,
                onWake = onWake, onError = { error ->
                    active.get()?.let { attempt ->
                        if (attempt.initialized.get()) attempt.reporter.report(attempt.token,
                            ComponentUpdate.health(Health.UNAVAILABLE, initializationError(error)))
                        else failure(error, attempt)
                    }
                    onError(error)
                })
        } catch (error: Throwable) {
            error.rethrowCancellationOrFatal()
            reporting?.invoke()?.unavailable("wake", initializationError(error).code, "create")
            return object : WakeWordPort {
                override val keyword = IflytekWakeWordObserver.DEFAULT_KEYWORD
                override fun initialize(): Unit = throw IllegalStateException("语音唤醒组件尚未就绪")
                override fun arm(): Unit = throw IllegalStateException("语音唤醒组件尚未就绪")
                override fun accept(pcm: ByteArray) = Unit
                override fun pause() = Unit
                override fun disarm() = Unit
                override fun close() = Unit
            }
        }
        return object : WakeWordPort {
            private val closed = AtomicBoolean()
            private fun current(): Attempt {
                check(!closed.get()) { "wake port released" }
                val h = reporting?.invoke()
                val old = active.get()
                if (old != null && old.reporting?.generation == h?.generation && !old.failed.get()) return old
                val reporter = h?.reporter("wake") ?: ComponentReporter.none()
                return Attempt(h, reporter, reporter.start("resource_load")).also(active::set)
            }
            override val keyword get() = observer.keyword
            override fun initialize() {
                val attempt = current()
                try { observer.init(context.applicationContext ?: context) }
                catch (error: Throwable) { failure(error, attempt);throw error }
            }
            override fun arm() {
                val attempt = current()
                try {
                    observer.arm()
                    attempt.reporter.report(attempt.token, if (attempt.initialized.get()) ComponentUpdate.health(Health.HEALTHY) else ComponentUpdate.ready())
                    attempt.initialized.set(true)
                } catch (error: Throwable) { failure(error, attempt);throw error }
            }
            override fun accept(pcm: ByteArray) { if (!closed.get() && reporting?.invoke()?.runtimeBlocked() != true) observer.accept(pcm) }
            override fun pause() = observer.pause()
            override fun disarm() = observer.disarm()
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                observer.close()
                active.getAndSet(null)?.let { it.reporter.close(it.token) }
            }
        }
    }
}
