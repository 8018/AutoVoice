package com.autovoice.messaging

import com.autovoice.voicecore.GatewayMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

fun interface MessageListener {
    fun onMessage(message: GatewayMessage)
}

fun interface ListenerRegistration {
    fun unregister()
}

/**
 * Type-indexed fan-out for gateway events. Registration declares the consumed message types and
 * one message may be observed by multiple independent listeners. Dispatch uses a snapshot so a
 * listener can safely unregister itself from its callback.
 */
class MessageDispatcher {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<MessageListener>>()

    fun register(types: Set<String>, listener: MessageListener): ListenerRegistration {
        require(types.isNotEmpty()) { "at least one message type is required" }
        types.forEach { type ->
            require(type.isNotBlank()) { "message type must not be blank" }
            listeners.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(listener)
        }
        return ListenerRegistration {
            types.forEach { type ->
                listeners[type]?.let { bucket ->
                    bucket.remove(listener)
                    if (bucket.isEmpty()) listeners.remove(type, bucket)
                }
            }
        }
    }

    fun dispatch(message: GatewayMessage) {
        listeners[message.type]?.forEach { it.onMessage(message) }
    }
}
