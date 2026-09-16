package com.autovoice.server.contracts.testing;

import java.util.function.LongSupplier;

/** D01b 可控时钟:测试显式推进/设定时间,替代 System.currentTimeMillis 注入时间源。 */
public final class TestClock implements LongSupplier {

    private volatile long nowMs;

    public TestClock(long startMs) {
        this.nowMs = startMs;
    }

    /** 向前推进指定毫秒数。 */
    public synchronized void advance(long ms) {
        nowMs += ms;
    }

    /** 直接设定当前时间。 */
    public synchronized void set(long nowMs) {
        this.nowMs = nowMs;
    }

    public long now() {
        return nowMs;
    }

    @Override
    public long getAsLong() {
        return nowMs;
    }
}
