package com.autovoice.voicecore

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow

/**
 * 处理流水线的一个阶段：把 Flow<IN> 变换为 Flow<OUT>。
 * 端侧按 demo 固定拓扑装配（见 [PipelineFactory]），不实现通用 DAG 引擎。
 */
interface Stage<IN, OUT> {
    /** 阶段名，用于日志与决策路由。 */
    val name: String

    /** 从 [DemoConfig] 的对应子对象（[JsonObject]）读取本阶段配置。 */
    fun configure(config: JsonObject)

    /** 本阶段的处理：上游 Flow 经过变换得到下游 Flow。 */
    fun Flow<IN>.transform(): Flow<OUT>

    /** 启动阶段资源（协程、连接、模型加载等）。 */
    suspend fun start()

    /** 停止阶段并释放资源。 */
    suspend fun stop()
}
