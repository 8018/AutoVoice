package com.autovoice.adapteriflytek

import com.autovoice.voicecore.health.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class RuntimeInitializationException(val componentError: ComponentError) : IllegalStateException("讯飞 AIKit 初始化失败")

/** One process attempt. No reset/retry or SDK ownership is introduced here. */
internal class IflytekRuntimeInitialization(
    private val timeoutMs: Long = 20_000,
    private val awaitResult: (CountDownLatch, Long) -> Boolean = { latch, timeout -> latch.await(timeout, TimeUnit.MILLISECONDS) },
) {
    private enum class State { NEW, INITIALIZING, READY, FAILED }
    private val lock = Any()
    private var state = State.NEW
    private var key = ""
    private var error: ComponentError? = null
    private var health = Health.UNKNOWN
    private val ready = CountDownLatch(1)
    private var reporter = ComponentReporter.none()
    private var token: ComponentToken? = null

    fun bindReporter(reporter: ComponentReporter) = synchronized(lock) {
        if (this.reporter === reporter) return@synchronized
        this.reporter = reporter
        if (state != State.NEW) {
            token = reporter.start("authorize")
            when (state) {
                State.READY -> {
                    reporter.report(requireNotNull(token), ComponentUpdate.ready())
                    if (health != Health.HEALTHY) reporter.report(requireNotNull(token), ComponentUpdate.health(health, error))
                }
                State.FAILED -> reporter.report(requireNotNull(token), ComponentUpdate.failed(requireNotNull(error)))
                else -> Unit
            }
        }
    }

    fun ensure(key: String, credentialsPresent: Boolean, beforeAuth: () -> Unit = {},
        startBackend: (onAuth: (Int) -> Unit, onFailure: (Throwable) -> Unit) -> Unit) {
        val owner = synchronized(lock) {
            if (this.key.isNotEmpty() && this.key != key) throw RuntimeInitializationException(ComponentError(ErrorCode.SDK_AUTH_FAILED))
            if (state == State.FAILED) throw RuntimeInitializationException(requireNotNull(error))
            if (state == State.READY && health == Health.UNAVAILABLE) throw RuntimeInitializationException(requireNotNull(error))
            if (state == State.NEW) {
                this.key = key;state = State.INITIALIZING;token = reporter.start("authorize")
                if (!credentialsPresent) {
                    failLocked(ComponentError(ErrorCode.SDK_CREDENTIALS_MISSING))
                    throw RuntimeInitializationException(requireNotNull(error))
                }
                true
            } else false
        }
        try {
            beforeAuth()
            if (owner) startBackend(::onAuth, ::onFailure)
        } catch (failure: Throwable) {
            failure.rethrowCancellationOrFatal()
            onFailure(failure)
        }
        val completed = try { awaitResult(ready, timeoutMs) } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("AIKit initialization wait cancelled")
        }
        synchronized(lock) {
            if (!completed && state == State.INITIALIZING) failLocked(ComponentError(ErrorCode.INIT_TIMEOUT))
            if (state != State.READY || health == Health.UNAVAILABLE) throw RuntimeInitializationException(error ?: ComponentError(ErrorCode.SDK_AUTH_FAILED))
        }
    }
    private fun onAuth(code: Int) = synchronized(lock) {
        when (state) {
            State.INITIALIZING -> if (code == 0) {
                state = State.READY;health = Health.HEALTHY;error = null
                reporter.report(requireNotNull(token), ComponentUpdate.ready());ready.countDown()
            } else failLocked(ComponentError(ErrorCode.SDK_AUTH_FAILED, vendorCode = code))
            State.READY -> {
                val next = if (code == 0) Health.HEALTHY else Health.UNAVAILABLE
                val nextError = if (code == 0) null else ComponentError(ErrorCode.SDK_AUTH_FAILED, vendorCode = code)
                if (next != health || nextError != error) {
                    health = next;error = nextError
                    reporter.report(requireNotNull(token), ComponentUpdate.health(health, error))
                }
            }
            else -> Unit // A terminal failed/timeout initialization cannot be revived by AUTH.
        }
    }
    private fun onFailure(failure: Throwable) = synchronized(lock) {
        failure.rethrowCancellationOrFatal()
        if (state == State.INITIALIZING) failLocked(initializationError(failure, ErrorCode.SDK_AUTH_FAILED))
    }
    private fun failLocked(error: ComponentError) {
        state = State.FAILED;health = Health.UNAVAILABLE;this.error = error
        reporter.report(requireNotNull(token), ComponentUpdate.failed(error));ready.countDown()
    }
}
