package com.autovoice.server.offlinecommand.supervisor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * D09a 测试用的可编排假 worker:模拟子进程的启动/崩溃/存活判定,
 * 不依赖真实 SDK 与真实进程。
 */
final class FakeWorkerProcess implements OfflineEngineSupervisor.WorkerProcess {

    private final AtomicInteger starts = new AtomicInteger();
    private volatile boolean alive;

    @Override
    public boolean start() {
        starts.incrementAndGet();
        alive = true;
        return true;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void destroy() {
        alive = false;
    }

    /** 模拟 worker 崩溃(进程退出/无响应)。 */
    void crash() {
        alive = false;
    }

    int startCount() {
        return starts.get();
    }
}
