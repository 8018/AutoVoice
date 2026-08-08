package com.autovoice.server.arbitration;

import com.autovoice.server.contracts.DecisionEntry;

/** 决策日志出口：仲裁器把每次收敛决策写到这里（shared/protocol.md §6）。 */
public interface DecisionSink {

    void log(DecisionEntry e);
}
