package com.autovoice.server.contracts.testing;

import java.util.ArrayDeque;

/**
 * D01b 慢消费者:元素入队后由测试手动取走,用于确定性复现下行背压
 * (慢客户端积压、发送超时、溢出丢弃),不依赖真实慢速 IO。
 */
public final class SlowDrain<T> {

    private final ArrayDeque<T> queue = new ArrayDeque<>();

    /** 生产者写入;容量不设限,由测试断言 pending 数量观察积压。 */
    public void accept(T item) {
        queue.add(item);
    }

    /** 测试手动消费一个;空时返回 null。 */
    public T drainOne() {
        return queue.poll();
    }

    public int pending() {
        return queue.size();
    }
}
