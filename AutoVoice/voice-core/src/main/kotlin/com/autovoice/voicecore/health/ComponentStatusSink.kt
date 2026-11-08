package com.autovoice.voicecore.health

fun interface ComponentStatusSink {
    fun report(event: ComponentStatusEvent)
    companion object { val NONE = ComponentStatusSink {} }
}
