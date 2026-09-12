package com.autovoice.server.contracts;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D10a 接入配额:每设备(认证主体)并发连接上限。
 *
 * <p>全局连接上限只挡住"总量",挡不住单个设备占满名额——本类按主体计数,
 * 超过配额时拒绝新连接(fail-closed),释放时归还。未握手连接同样计入,
 * 因此上层配合 hello 截止时间使用(见 VoiceGatewayHandler)。</p>
 */
public final class ConnectionQuota {

    private final int perSubjectLimit;
    private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();

    public ConnectionQuota(int perSubjectLimit) {
        this.perSubjectLimit = Math.max(1, perSubjectLimit);
    }

    /** 尝试占用一个名额;超过配额返回 false(调用方应拒绝该连接)。 */
    public boolean tryAcquire(String subject) {
        String key = subject == null || subject.isBlank() ? "" : subject;
        AtomicInteger counter = active.computeIfAbsent(key, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= perSubjectLimit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 归还名额(连接关闭/被拒);幂等保护:不会降到 0 以下。 */
    public void release(String subject) {
        String key = subject == null || subject.isBlank() ? "" : subject;
        active.computeIfPresent(key, (k, counter) -> {
            counter.updateAndGet(value -> Math.max(0, value - 1));
            return counter.get() == 0 ? null : counter;
        });
    }

    public int activeFor(String subject) {
        String key = subject == null || subject.isBlank() ? "" : subject;
        AtomicInteger counter = active.get(key);
        return counter == null ? 0 : counter.get();
    }
}
