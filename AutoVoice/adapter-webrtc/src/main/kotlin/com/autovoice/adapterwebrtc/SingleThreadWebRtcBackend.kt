package com.autovoice.adapterwebrtc

import com.autovoice.audiofrontend.VadPurpose
import java.util.concurrent.*

/** APM creation, capture, reverse stream, VAD reset and destruction share one owned thread. */
internal class SingleThreadWebRtcBackend(create: () -> WebRtcBackend) : WebRtcBackend {
    private val admission = Any()
    private val executor = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(8),
        { task -> Thread(task,"webrtc-audio-owner").apply { isDaemon = true } },ThreadPoolExecutor.AbortPolicy())
    private var closed = false
    private val delegate: WebRtcBackend
    init {
        val created = executor.submit(Callable(create))
        try { delegate = await(created) }
        catch (error: Throwable) {
            // A caller interrupted while creation runs cannot orphan a subsequently created handle.
            executor.execute {
                try { created.get().close() } catch (_: ExecutionException) { /* creation already failed */ }
                finally { executor.shutdown() }
            }
            throw error
        }
    }
    private fun <T> await(future: Future<T>): T = try { future.get() }
        catch (e: ExecutionException) { throw requireNotNull(e.cause) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt();throw CancellationException("WebRTC caller interrupted") }
    private fun <T> invoke(action: () -> T): T {
        val future = synchronized(admission) {
            check(!closed) { "WebRTC backend released" }
            // Reserve one bounded slot for teardown, so overflow cannot orphan the handle.
            if (executor.queue.remainingCapacity() <= 1) throw RejectedExecutionException("WebRTC capture queue overflow")
            executor.submit(Callable(action))
        }
        return await(future)
    }
    override fun processCapture(input: ShortArray, delayMs: Int, validSamples: Int) = invoke { delegate.processCapture(input,delayMs,validSamples) }
    override fun processRender(input: ShortArray) = invoke { delegate.processRender(input) }
    override fun resetVad(purpose: VadPurpose) = invoke { delegate.resetVad(purpose) }
    override fun close() {
        val finished = synchronized(admission) {
            if (closed) return
            closed = true
            executor.submit { delegate.close() }.also { executor.shutdown() }
        }
        await(finished)
    }
}
