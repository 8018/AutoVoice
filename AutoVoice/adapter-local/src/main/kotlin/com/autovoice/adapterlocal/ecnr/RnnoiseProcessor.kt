package com.autovoice.adapterlocal.ecnr

/**
 * RNNoise（xiph/rnnoise v0.2）JNI 桥。
 *
 * JNI 符号名按包名 `com.autovoice.adapterlocal.ecnr.RnnoiseNative` 声明，
 * 对应 C 侧 `Java_com_autovoice_adapterlocal_ecnr_RnnoiseNative_*`（见 src/main/cpp/）。
 */
object RnnoiseNative {
    init {
        System.loadLibrary("rnnoise_jni")
    }

    /** 创建 DenoiseState，返回 native 句柄。 */
    external fun create(): Long

    /** 释放 DenoiseState。 */
    external fun destroy(handle: Long)

    /**
     * 处理一帧 480 samples（16k）PCM16，返回降噪后的 480 samples PCM16。
     */
    external fun processFrame(handle: Long, frame: ShortArray): ShortArray
}

/**
 * RNNoise 降噪处理器（16k，480 samples/帧 = 30ms）。
 *
 * [process] 处理单帧；[chunk] 把任意长 PCM 按 480 对齐分帧（不足 480 的尾帧丢弃——
 * 段尾最多丢 30ms，demo 可接受）。
 *
 * native 句柄懒加载：JVM 单测里只测 [chunk] 不会触发 System.loadLibrary。
 */
class RnnoiseProcessor : AutoCloseable {

    /** 每帧样本数（rnnoise_get_frame_size）。 */
    companion object {
        const val FRAME_SIZE = 480
    }

    private var handle: Long? = null

    private fun nativeHandle(): Long = handle ?: RnnoiseNative.create().also { handle = it }

    /** 处理一帧 480 samples PCM16，返回降噪后的 480 samples PCM16。 */
    fun process(frame480: ShortArray): ShortArray {
        require(frame480.size == FRAME_SIZE) {
            "rnnoise expects exactly $FRAME_SIZE samples per frame, got ${frame480.size}"
        }
        return RnnoiseNative.processFrame(nativeHandle(), frame480)
    }

    /**
     * 把 PCM 按 480 samples 分帧；最后不足 480 的尾帧丢弃（<30ms，demo 可接受）。
     */
    fun chunk(pcm: ShortArray): List<ShortArray> {
        val frames = pcm.size / FRAME_SIZE
        val out = ArrayList<ShortArray>(frames)
        var offset = 0
        repeat(frames) {
            val frame = ShortArray(FRAME_SIZE)
            System.arraycopy(pcm, offset, frame, 0, FRAME_SIZE)
            offset += FRAME_SIZE
            out.add(frame)
        }
        return out
    }

    override fun close() {
        handle?.let { RnnoiseNative.destroy(it) }
        handle = null
    }
}
