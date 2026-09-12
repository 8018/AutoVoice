package com.autovoice.server.offlinecommand.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.testing.TestClock;
import org.junit.jupiter.api.Test;

/**
 * D09a 原生引擎进程监督器(用可编排的假 worker 验证状态机,不依赖真实 SDK):
 * 崩溃检测、指数退避重启、重启风暴熔断、世代隔离(旧结果不污染)。
 */
class OfflineEngineSupervisorTest {

    private static final long RESTART_BASE_MS = 100;
    private static final int RESTART_MAX = 3;

    private final TestClock clock = new TestClock(0);
    private final FakeWorkerProcess worker = new FakeWorkerProcess();
    private final OfflineEngineSupervisor supervisor =
            new OfflineEngineSupervisor(worker, clock, RESTART_BASE_MS, RESTART_MAX);

    @Test
    void startsWorkerAndReportsHealthy() {
        assertTrue(supervisor.start(), "首次启动应成功");
        assertTrue(supervisor.isHealthy());
        assertEquals(1, worker.startCount());
    }

    @Test
    void crashIsDetectedAndWorkerRestartsWithBackoff() {
        supervisor.start();
        long generationBefore = supervisor.generation();

        worker.crash(); // 模拟 native 崩溃导致子进程退出
        supervisor.onCrashDetected();

        assertFalse(supervisor.isHealthy(), "崩溃后应判定不健康");
        assertFalse(supervisor.tryRestart(), "退避期内不得立即重启");
        clock.advance(RESTART_BASE_MS + 1);
        assertTrue(supervisor.tryRestart(), "退避到期应重启");
        assertTrue(supervisor.isHealthy());
        assertEquals(2, worker.startCount());
        assertTrue(supervisor.generation() > generationBefore, "重启必须推进世代");
    }

    @Test
    void restartBackoffGrowsExponentially() {
        supervisor.start();
        worker.crash();
        supervisor.onCrashDetected();
        clock.advance(RESTART_BASE_MS + 1);
        assertTrue(supervisor.tryRestart());
        worker.crash();
        supervisor.onCrashDetected();

        // 第二次退避应为 2×base(第一次退避 base)
        assertFalse(supervisor.tryRestart(), "退避期内不得重启");
        clock.advance(RESTART_BASE_MS * 2 + 1);
        assertTrue(supervisor.tryRestart(), "指数退避到期后应重启");
    }

    @Test
    void repeatedCrashesTripCircuitToAvoidRestartStorm() {
        supervisor.start();
        for (int i = 0; i < RESTART_MAX; i++) {
            worker.crash();
            supervisor.onCrashDetected();
            clock.advance(RESTART_BASE_MS * 100); // 跳过退避
            supervisor.tryRestart();
        }
        worker.crash();
        supervisor.onCrashDetected();
        clock.advance(RESTART_BASE_MS * 1_000);
        assertFalse(supervisor.tryRestart(), "连续崩溃达上限应熔断,不再无限重启");
        assertTrue(supervisor.isCircuitOpen());
    }

    @Test
    void stableRunResetsBackoffAndCircuit() {
        supervisor.start();
        worker.crash();
        supervisor.onCrashDetected();
        clock.advance(RESTART_BASE_MS + 1);
        assertTrue(supervisor.tryRestart());

        supervisor.onStable(); // 稳定运行窗口后复位

        assertFalse(supervisor.isCircuitOpen(), "稳定运行应复位熔断计数");
        assertTrue(supervisor.isHealthy());
    }

    @Test
    void staleResultsFromPreviousGenerationAreRejected() {
        supervisor.start();
        long staleGeneration = supervisor.generation();
        worker.crash();
        supervisor.onCrashDetected();
        clock.advance(RESTART_BASE_MS + 1);
        supervisor.tryRestart();

        assertFalse(supervisor.acceptsResult(staleGeneration), "旧世代结果必须丢弃");
        assertTrue(supervisor.acceptsResult(supervisor.generation()), "当前世代结果应接受");
    }
}
