package com.autovoice.server.contracts;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * D14b 临时诊断音频窗口:原始音频**默认不持久化**;仅在运维显式开启时按设备、
 * 限定时长采集,到期自动关闭(不需要人工记得关闭)。
 *
 * <p>窗口只存于进程内(重启即失效,符合"临时诊断"语义);开启动作应经管理权限
 * (见 telemetry 的管理令牌),关闭与到期都有明确记录。</p>
 */
public final class DiagnosticAudioWindow {

    /** 允许的最长开启时长(上限,防止忘记关闭导致长期采集)。 */
    public static final long MAX_WINDOW_MS = 10 * 60_000L; // 10 分钟

    private final Map<String, Long> enabledUntil = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long maxWindowMs;

    public DiagnosticAudioWindow(LongSupplier clock) {
        this(clock, MAX_WINDOW_MS);
    }

    public DiagnosticAudioWindow(LongSupplier clock, long maxWindowMs) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.maxWindowMs = Math.max(1, maxWindowMs);
    }

    /** 按设备开启诊断音频采集;时长受 {@link #MAX_WINDOW_MS} 约束。 */
    public void open(String deviceId, long durationMs) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        long bounded = Math.min(Math.max(1, durationMs), maxWindowMs);
        enabledUntil.put(deviceId, clock.getAsLong() + bounded);
    }

    /** 关闭指定设备的诊断采集。 */
    public void close(String deviceId) {
        if (deviceId == null) {
            return;
        }
        enabledUntil.remove(deviceId);
    }

    /** 该设备当前是否采集(过期即自动关闭)。 */
    public boolean isEnabledFor(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        Long until = enabledUntil.get(deviceId);
        if (until == null) {
            return false;
        }
        if (clock.getAsLong() >= until) {
            enabledUntil.remove(deviceId, until); // 到期自动关闭
            return false;
        }
        return true;
    }

    /** 当前采集中的设备(诊断/审计用)。 */
    public Set<String> activeDevices() {
        enabledUntil.entrySet().removeIf(entry -> clock.getAsLong() >= entry.getValue());
        return Set.copyOf(enabledUntil.keySet());
    }
}
