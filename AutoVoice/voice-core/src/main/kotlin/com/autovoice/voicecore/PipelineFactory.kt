package com.autovoice.voicecore

/**
 * 端侧流水线装配器。demo 直接装配固定拓扑（[DemoConfig.mode] == "offline" 时走本地链路），
 * 不实现通用 DAG 引擎。
 */
object PipelineFactory {
    /**
     * 构建本地（offline）链路：asr -> nlu -> arbiter -> tts 的固定拓扑。
     * 占位实现，Task 16/17 填充。
     */
    fun buildLocalChain(cfg: DemoConfig): List<Stage<*, *>> = emptyList()
}
