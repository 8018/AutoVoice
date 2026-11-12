package com.autovoice.adapterfirered

import com.autovoice.voicecore.*
import com.autovoice.voicecore.health.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Facts belong to the request owner. Deadline uses monotonic milliseconds; callbacks must be nonblocking.
 * Finalization is explicit: invoking recognize/choosing a turnId never authorizes native inference. */
data class FireRedAdmission(val deadlineMs: Long, val finalized: () -> Boolean, val originalPermitValid: () -> Boolean) {
    internal fun valid(now: Long) = now < deadlineMs && (finalized() || originalPermitValid())
}
fun interface FireRedAdmissionProvider { fun capture(turnId: String): FireRedAdmission? }
internal interface FireRedDecoder : AutoCloseable { fun decode(samples: FloatArray): String }

/** One owner serializes model construction, decode and release. Bounded requests include active work.
 * Control work is only one initialization/cleanup per live model; JNI never runs under the state lock. */
internal class FireRedRuntime(
    private val load: () -> ValidatedFireRedModel,
    private val create: (ValidatedFireRedModel) -> FireRedDecoder,
    private val bootstrap: () -> Unit,
) {
    private val owner = Executors.newSingleThreadExecutor { r -> Thread(r,"firered-owner").apply { isDaemon = true } }
    private val lock = Any()
    private var ready: CompletableFuture<ValidatedFireRedModel>? = null
    private var decoder: FireRedDecoder? = null // owner thread only
    private val listeners = linkedMapOf<Any,(String) -> Unit>()
    private var cleanupPending = false
    private var releaseFailed = false
    private val requests = AtomicInteger()
    val pendingRequests get() = requests.get()
    inner class Lease(val key: Any, val ready: CompletableFuture<ValidatedFireRedModel>, val reused: Boolean) : AutoCloseable {
        override fun close() { synchronized(lock) {
            if (listeners.remove(key) == null) return
            if (listeners.isEmpty() && !cleanupPending) {
                cleanupPending = true
                owner.execute { cleanup() }
            }
        } }
    }
    fun acquire(stage: (String) -> Unit): Lease = synchronized(lock) {
        val key = Any();listeners[key] = stage
        val reused = ready != null
        if (ready == null) {
            val future = CompletableFuture<ValidatedFireRedModel>();ready = future
            owner.execute {
                try {
                    check(!synchronized(lock) { releaseFailed }) { "native release failed" }
                    emit("native_load");bootstrap()
                    emit("resource_validate");val model = load()
                    if (synchronized(lock) { listeners.isEmpty() }) throw CancellationException("no live model owner")
                    emit("model_create");decoder = create(model)
                    future.complete(model)
                } catch (error: Throwable) { future.completeExceptionally(error);error.rethrowCancellationOrFatal() }
            }
        } else stage("reuse_existing")
        Lease(key,requireNotNull(ready),reused)
    }
    private fun emit(stage: String) { synchronized(lock) { listeners.values.toList() }.forEach { it(stage) } }
    private fun cleanup() {
        synchronized(lock) {
            cleanupPending = false
            if (listeners.isNotEmpty()) return
            // A later acquire queues initialization after release on this same owner, never concurrently.
            ready = null
        }
        val old = decoder;decoder = null
        try { old?.close() } catch (error: Throwable) { synchronized(lock) { releaseFailed = true };error.rethrowCancellationOrFatal() }
    }
    fun submit(valid: () -> Boolean, pcm: ByteArray): CompletableFuture<String?> {
        if (requests.incrementAndGet() > 3) { requests.decrementAndGet();throw RejectedExecutionException("FireRed request queue is full") }
        val result = CompletableFuture<String?>()
        try {
            owner.execute {
                try {
                    if (!result.isCancelled && valid()) {
                        val samples = FloatArray(pcm.size / 2) { i ->
                            (((pcm[i*2].toInt() and 255) or (pcm[i*2+1].toInt() shl 8)).toShort().toInt()) / 32768f
                        }
                        // Admission is rechecked immediately before JNI, after conversion/queue wait.
                        result.complete(if (valid()) requireNotNull(decoder).decode(samples) else null)
                    } else result.complete(null)
                } catch (error: Throwable) { result.completeExceptionally(error);error.rethrowCancellationOrFatal() }
                finally { requests.decrementAndGet() }
            }
        } catch (error: Throwable) { requests.decrementAndGet();throw error }
        return result
    }
}

/** Constructor returns immediately. All hash/native work belongs to the shared worker; close never waits for JNI.
 * Synchronous recognize must be invoked on a candidate worker, as required by the existing AsrEngine contract. */
class FireRedAsrEngine internal constructor(
    private val runtime: FireRedRuntime,
    admission: FireRedAdmissionProvider,
    private val reporter: ComponentReporter,
    initializationTimeoutMs: Long = 60_000,
) : AsrEngine, AutoCloseable {
    private val lock = Any()
    private var admission: FireRedAdmissionProvider? = admission
    private enum class State { INITIALIZING, READY, FAILED, CLOSED }
    private var state = State.INITIALIZING
    private val token = reporter.start("native_load")
    private val settled = CompletableFuture<Boolean>()
    private val inFlight = mutableSetOf<CompletableFuture<String?>>()
    private val lease = runtime.acquire { stage -> synchronized(lock) {
        if (state == State.INITIALIZING) reporter.report(token,ComponentUpdate.stage(stage))
    } }
    private val timeout = timer.schedule({ fail(ComponentError(ErrorCode.INIT_TIMEOUT)) },initializationTimeoutMs,TimeUnit.MILLISECONDS)
    init {
        lease.ready.whenComplete { model,error -> synchronized(lock) {
            if (state != State.INITIALIZING) return@whenComplete
            timeout.cancel(false)
            if (error == null) {
                state = State.READY
                reporter.report(token,ComponentUpdate(Lifecycle.READY,Health.HEALTHY,if (lease.reused) "reuse_existing" else "model_ready",versions = model.metadata))
                settled.complete(true)
            } else fail(if (error is ModelResourceException) error.error else initializationError(error,ErrorCode.MODEL_LOAD_FAILED))
        } }
    }
    private fun fail(error: ComponentError) { synchronized(lock) {
        if (state != State.INITIALIZING) return
        state = State.FAILED;admission = null
        reporter.report(token,ComponentUpdate.failed(error));settled.complete(false)
        inFlight.toList().forEach { it.cancel(false) };inFlight.clear();lease.close()
    } }
    fun awaitReady(timeoutMs: Long): Boolean = try { settled.get(timeoutMs,TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { false }
    private fun valid(ticket: FireRedAdmission): Boolean = synchronized(lock) { state == State.READY && ticket.valid(now()) }
    override fun recognize(turnId: String, segment: ByteArray, sink: AsrSink): AsrResult? {
        val provider = synchronized(lock) { if (state in setOf(State.FAILED,State.CLOSED)) null else admission } ?: return null
        require(segment.isNotEmpty() && segment.size % 2 == 0 && segment.size <= 960000) { "FireRed expects at most 30 seconds of PCM16 mono at 16kHz" }
        val ticket = provider.capture(turnId) ?: return null
        if (!ticket.valid(now())) return null
        val request = try { runtime.submit({ valid(ticket) },segment.copyOf()) }
        catch (error: RejectedExecutionException) {
            synchronized(lock) { if (state == State.READY) reporter.report(token,ComponentUpdate.health(Health.DEGRADED,ComponentError(ErrorCode.RESOURCE_EXHAUSTED,retryable = true))) }
            throw error
        }
        synchronized(lock) {
            if (state in setOf(State.FAILED,State.CLOSED)) { request.cancel(false);return null }
            inFlight.add(request)
        }
        val text = try {
            request.get((ticket.deadlineMs-now()).coerceAtLeast(1),TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) { request.cancel(false);return null }
        catch (error: InterruptedException) { request.cancel(false);Thread.currentThread().interrupt();throw CancellationException("FireRed caller interrupted") }
        catch (error: CancellationException) { if (synchronized(lock) { state in setOf(State.FAILED,State.CLOSED) }) return null;throw error }
        catch (error: ExecutionException) {
            val cause = error.cause ?: error;cause.rethrowCancellationOrFatal()
            synchronized(lock) { if (state == State.READY) reporter.report(token,ComponentUpdate.health(Health.DEGRADED,initializationError(cause))) }
            throw cause
        } finally { synchronized(lock) { inFlight.remove(request) } }
        // FireRed can expose its silence token in the decoded text, even on a quiet segment.
        val transcript = text?.replace("<sil>", "")?.trim()
        return synchronized(lock) {
            if (!valid(ticket) || transcript.isNullOrBlank()) return null
            val result = AsrResult(transcript,isFinal = true)
            reporter.report(token,ComponentUpdate.health(Health.HEALTHY))
            sink.onTranscript(result)
            if (!valid(ticket)) return null
            sink.onTurnEstablished()
            if (valid(ticket)) result else null
        }
    }
    override fun close() { synchronized(lock) {
        if (state == State.CLOSED) return
        state = State.CLOSED;admission = null;timeout.cancel(false);settled.complete(false)
        inFlight.toList().forEach { it.cancel(false) };inFlight.clear()
        reporter.close(token);lease.close()
    } }
    companion object {
        private val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r,"firered-timeout").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
        private var root: File? = null
        private var shared: FireRedRuntime? = null
        private fun now() = System.nanoTime()/1_000_000
        /** One fixed resource root per process; no Activity/VM is retained by the native owner. */
        @Synchronized fun create(modelRoot: File, admission: FireRedAdmissionProvider, reporter: ComponentReporter = ComponentReporter.none()): FireRedAsrEngine {
            val canonical = modelRoot.canonicalFile
            require(root == null || root == canonical) { "FireRed model root cannot change within a process" }
            if (shared == null) {
                root = canonical
                shared = FireRedRuntime(ModelResourceLoader(canonical)::load,SherpaDecoder::create,SherpaDecoder::bootstrap)
            }
            return FireRedAsrEngine(requireNotNull(shared),admission,reporter)
        }
    }
}
