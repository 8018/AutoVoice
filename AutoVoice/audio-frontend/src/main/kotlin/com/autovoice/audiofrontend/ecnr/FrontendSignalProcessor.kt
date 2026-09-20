package com.autovoice.audiofrontend.ecnr

/** Atomic front-end signal processor. Implementations may provide denoise, echo control or AGC. */
interface FrontendSignalProcessor : AutoCloseable {
    fun process(frame: ShortArray): ShortArray
}
