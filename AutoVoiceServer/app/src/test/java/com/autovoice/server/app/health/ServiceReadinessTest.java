package com.autovoice.server.app.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.testing.TestClock;
import org.junit.jupiter.api.Test;

/**
 * D12a 就绪判定与排空闸门:
 * 就绪 = 关键组件已登记且未失败(不调用付费模型);
 * 可降级组件失败不摘除整体业务;排空期间停止接入并等待在途结束。
 */
class ServiceReadinessTest {

    private final TestClock clock = new TestClock(0);
    private final ServiceReadiness readiness = new ServiceReadiness(clock, 5_000);

    // ---------- D16 修复:必需组件缺失/未就绪不得 UP ----------

    @Test
    void readyOnlyWhenAllRequiredComponentsAreReady() {
        readiness.registerCritical("config");
        readiness.registerCritical("gateway");
        assertFalse(readiness.isReady(), "任一必需组件未就绪 → 不就绪");

        readiness.markReady("config");
        assertFalse(readiness.isReady(), "gateway 仍未就绪 → 不就绪");

        readiness.markReady("gateway");
        assertTrue(readiness.isReady(), "全部必需组件就绪 → UP");
    }

    @Test
    void requiredComponentInitializationFailureBlocksReadiness() {
        readiness.registerCritical("config");
        readiness.registerCritical("gateway");
        readiness.markReady("gateway");
        readiness.markFailed("config", "invalid production config");

        assertFalse(readiness.isReady(), "必需组件初始化失败 → 不得 UP(false 不能误报部署成功)");
    }

    @Test
    void duplicateRegistrationAndMarkAreIdempotent() {
        readiness.registerCritical("gateway");
        readiness.registerCritical("gateway"); // 幂等
        readiness.markReady("gateway");
        readiness.markReady("gateway");
        assertTrue(readiness.isReady());
        assertEquals(1, readiness.componentCount());
    }

    @Test
    void missingRequiredComponentDeclarationIsNotReady() {
        // D16 验收发现的缺陷:未声明必需组件时曾恒为 UP(接线缺陷被静默解释成"就绪")
        assertFalse(readiness.isReady(),
                "未声明任何必需组件 = 接线缺陷 → 必须不就绪(fail-closed)");
        assertTrue(readiness.isLive(), "存活与就绪独立:存活只看进程");
    }

    @Test
    void criticalComponentNotYetInitializedIsNotReady() {
        readiness.registerCritical("offline-engine");
        assertFalse(readiness.isReady(), "关键组件未就绪时不得报就绪");

        readiness.markReady("offline-engine");
        assertTrue(readiness.isReady());

        readiness.markFailed("offline-engine", "init failed");
        assertFalse(readiness.isReady(), "关键组件失败后不再就绪");
    }

    @Test
    void degradableComponentFailureDoesNotRemoveService() {
        readiness.registerCritical("gateway");
        readiness.markReady("gateway");
        readiness.registerDegradable("skill-registry");
        readiness.markFailed("skill-registry", "platform unreachable");
        assertTrue(readiness.isReady(), "可降级依赖失败不得摘除整体业务");
    }

    @Test
    void drainingStopsAdmissionAndWaitsForInflight() {
        readiness.registerCritical("gateway");
        readiness.markReady("gateway");
        readiness.beginDraining();
        assertFalse(readiness.acceptsNewWork(), "排空期间停止接入");
        assertFalse(readiness.isReady(), "排空期间不就绪(负载均衡应摘除)");

        readiness.onWorkStarted();
        readiness.onWorkFinished();
        assertTrue(readiness.drainComplete(), "无在途工作时排空立即完成");
    }

    @Test
    void drainingTimesOutAfterDeadline() {
        readiness.registerCritical("gateway");
        readiness.markReady("gateway");
        readiness.beginDraining();
        readiness.onWorkStarted(); // 在途任务不结束
        assertFalse(readiness.drainComplete(), "有在途工作且未超时:排空未完成");

        clock.advance(5_001);
        assertTrue(readiness.drainComplete(), "超过排空期限后按期限结束(不无限等待)");
    }

    @Test
    void snapshotReportsComponentStatesForDiagnosis() {
        readiness.registerCritical("gateway");
        readiness.markReady("gateway");
        readiness.registerDegradable("tts");
        readiness.markFailed("tts", "unreachable");

        var snapshot = readiness.snapshot();
        assertTrue(snapshot.contains("gateway=READY"));
        assertTrue(snapshot.contains("tts=FAILED"));
        assertEquals(2, readiness.componentCount());
    }
}
