package com.autovoice.voicecore.arbiter

import java.util.LinkedHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** 按 turn 隔离 pending 信号，避免并发轮次从同一个 Channel 抢走彼此的延长窗口。 */
class PendingSignalRegistry(private val retainedTurns: Int = 64) {
    private val channels = LinkedHashMap<String, Channel<Unit>>()

    init {
        require(retainedTurns > 0)
    }

    @Synchronized
    fun channel(turnId: String): ReceiveChannel<Unit> = getOrCreate(turnId)

    @Synchronized
    fun signal(turnId: String) {
        getOrCreate(turnId).trySend(Unit)
    }

    private fun getOrCreate(turnId: String): Channel<Unit> {
        channels[turnId]?.let { return it }
        val channel = Channel<Unit>(Channel.CONFLATED)
        channels[turnId] = channel
        while (channels.size > retainedTurns) {
            channels.remove(channels.keys.first())?.close()
        }
        return channel
    }
}
