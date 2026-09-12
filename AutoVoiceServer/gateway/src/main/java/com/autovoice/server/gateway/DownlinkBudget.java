package com.autovoice.server.gateway;

import java.util.concurrent.atomic.AtomicLong;

/**
 * D10b 每连接下行字节预算:慢客户端不得让下行数据无界堆积。
 *
 * <p>发送前 {@link #tryReserve} 预留字节数,发送完成(或失败)后 {@link #release} 归还。
 * 预算不足时明确拒绝(调用方可丢弃该段或降级),不静默丢弃片段假装成功。</p>
 */
final class DownlinkBudget {

    private final long limit;
    private final AtomicLong used = new AtomicLong();

    DownlinkBudget(long limitBytes) {
        this.limit = Math.max(1, limitBytes);
    }

    /** 预留字节数;超出预算返回 false(不改变已用量)。 */
    boolean tryReserve(long bytes) {
        if (bytes < 0 || bytes > limit) {
            return false; // 单条超预算:直接拒绝,不得突破上界
        }
        while (true) {
            long current = used.get();
            if (current + bytes > limit) {
                return false;
            }
            if (used.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /** 归还字节数(发送完成/失败/丢弃后调用);幂等,不会下溢。 */
    void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        used.updateAndGet(current -> Math.max(0, current - bytes));
    }

    long remaining() {
        return limit - used.get();
    }
}
