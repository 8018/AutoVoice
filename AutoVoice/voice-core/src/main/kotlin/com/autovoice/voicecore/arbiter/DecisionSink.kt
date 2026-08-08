package com.autovoice.voicecore.arbiter

import com.autovoice.voicecore.DecisionEntry

/**
 * 端侧仲裁器决策日志出口：每次竞速收敛写一条 [DecisionEntry]。
 */
fun interface DecisionSink {
    fun onDecision(entry: DecisionEntry)
}
