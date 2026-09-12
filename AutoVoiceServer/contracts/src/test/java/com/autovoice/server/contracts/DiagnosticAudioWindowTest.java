package com.autovoice.server.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.testing.TestClock;
import org.junit.jupiter.api.Test;

/**
 * D14b 临时诊断音频窗口:生产默认不持久化;按设备限时开启,到期自动关闭。
 */
class DiagnosticAudioWindowTest {

    private final TestClock clock = new TestClock(0);
    private final DiagnosticAudioWindow window = new DiagnosticAudioWindow(clock, 10 * 60_000L);

    @Test
    void disabledByDefaultForEveryDevice() {
        assertFalse(window.isEnabledFor("device-a"), "默认不采集(生产默认不持久化)");
        assertFalse(window.isEnabledFor(null));
    }

    @Test
    void enabledDeviceIsCapturedUntilDeadline() {
        window.open("device-a", 60_000);
        assertTrue(window.isEnabledFor("device-a"));
        assertFalse(window.isEnabledFor("device-b"), "按设备开启,不影响其他设备");

        clock.advance(59_999);
        assertTrue(window.isEnabledFor("device-a"));
        clock.advance(2);
        assertFalse(window.isEnabledFor("device-a"), "到期自动关闭");
    }

    @Test
    void durationIsCappedAtMaximum() {
        window.open("device-a", 10 * 60_000L * 100); // 请求超长
        clock.advance(10 * 60_000L + 1);
        assertFalse(window.isEnabledFor("device-a"), "开启时长受上限约束");
    }

    @Test
    void reopenExtendsWithinCap() {
        window.open("device-a", 60_000);
        window.open("device-a", 120_000); // 重新开启延长
        clock.advance(119_999);
        assertTrue(window.isEnabledFor("device-a"));
    }

    @Test
    void closeStopsCaptureImmediately() {
        window.open("device-a", 60_000);
        window.close("device-a");
        assertFalse(window.isEnabledFor("device-a"), "可立即关闭");
    }

    @Test
    void activeDevicesReportedForDiagnosis() {
        window.open("device-a", 60_000);
        window.open("device-b", 60_000);
        assertEquals(2, window.activeDevices().size());
        clock.advance(60_001);
        assertEquals(0, window.activeDevices().size(), "过期设备不再列出");
    }
}
