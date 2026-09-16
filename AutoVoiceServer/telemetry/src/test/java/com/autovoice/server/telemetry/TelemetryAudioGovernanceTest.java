package com.autovoice.server.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D14b 音频治理:默认不落盘;全局开关或临时诊断窗口才采集;容量上限生效。
 */
class TelemetryAudioGovernanceTest {

    @TempDir
    Path dir;

    private TelemetryProperties props(boolean persist, long maxBytes) {
        return new TelemetryProperties(true, dir.resolve("t.db").toString(),
                dir.resolve("audio").toString(), 7, "a", "b", persist, maxBytes);
    }

    private TelemetryService service(TelemetryProperties props) {
        return new TelemetryService(props, System::currentTimeMillis);
    }

    @Test
    void audioIsNotPersistedByDefault() throws Exception {
        try (TelemetryService service = service(props(false, 1_000_000))) {
            service.saveAudio("u-1", new byte[]{1, 2, 3}, "device-a");
            service.close();
            Path audioDir = dir.resolve("audio");
            assertFalse(Files.isDirectory(audioDir) && Files.list(audioDir).findAny().isPresent(),
                    "默认(全局关+无诊断窗口)不得落盘原始音频");
        }
    }

    @Test
    void diagnosticWindowEnablesCaptureForThatDeviceOnly() throws Exception {
        var window = new com.autovoice.server.contracts.DiagnosticAudioWindow(
                System::currentTimeMillis, 60_000);
        try (TelemetryService service = service(props(false, 1_000_000))) {
            service.setDiagnosticAudioWindow(window);
            service.openDiagnosticAudio("device-a", 60_000);

            service.saveAudio("u-1", new byte[]{1, 2, 3}, "device-a");
            service.saveAudio("u-2", new byte[]{4, 5, 6}, "device-b"); // 未开启采集的设备
            service.close();

            Path audioDir = dir.resolve("audio");
            assertTrue(Files.exists(audioDir.resolve("u-1.wav")), "诊断窗口内的设备应落盘");
            assertFalse(Files.exists(audioDir.resolve("u-2.wav")), "未开启的设备不得落盘");
        }
    }

    @Test
    void capacityLimitDropsAudioWithoutFailing() throws Exception {
        try (TelemetryService service = service(props(true, 10))) { // 上限 10 字节
            service.saveAudio("u-1", new byte[1024], "device-a");
            service.close();
            Path audioDir = dir.resolve("audio");
            assertFalse(Files.exists(audioDir.resolve("u-1.wav")),
                    "超过容量上限应丢弃(不抛异常、不影响链路)");
        }
    }

    @Test
    void globalSwitchAllowsCaptureForCompatibility() throws Exception {
        try (TelemetryService service = service(props(true, 1_000_000))) {
            service.saveAudio("u-1", new byte[]{1, 2, 3}, "device-a");
            service.close();
            assertTrue(Files.exists(dir.resolve("audio").resolve("u-1.wav")),
                    "显式开启全局采集时应落盘(开发/调试场景)");
        }
    }
}
