package com.autovoice.server.contracts;

/**
 * 云端仲裁过程事件（需求 3 补全）：收到候选 / 仲裁胜出 / 仲裁失败。
 * 装配方（gateway）把事件映射为 telemetry 插桩（cloud_arbiter_received/won/lost）。
 *
 * <p>与端侧 {@code OnDeviceArbiterEvent}（端云仲裁）对称：收到 asr 命令词 / 收到 llm 语义 /
 * 胜出（priority 优先、llm_timeout 超时未收到 llm）/ 失败（llm_already_won 已有 llm 胜出、
 * command_already_won 已有命令词胜出、not_latest_round 非最新轮）。</p>
 */
public record CloudArbiterEvent(Kind kind, String route, Reason reason, String decisionReason) {

    /** 事件类别：收到候选 / 胜出 / 失败。 */
    public enum Kind { RECEIVED, WON, LOST }

    /**
     * 原因（wire 值 = telemetry payload 取值）：
     * 胜出——{@link #PRIORITY} 优先（先到/策略优先，与现有语义一致：命令词命中即胜出、
     * llm 到达或宽限期满胜出）、{@link #LLM_TIMEOUT} 超时未收到 llm（llm 路兜底收敛）；
     * 失败——{@link #LLM_ALREADY_WON} 已经有 llm 胜出、{@link #COMMAND_ALREADY_WON}
     * 已经有命令词胜出、{@link #NOT_LATEST_ROUND} 不是最新轮会话（枚举保留；服务器
     * 单连接串行 + BUSY 守卫已防，不实现）。
     */
    public enum Reason {
        PRIORITY("priority"),
        LLM_TIMEOUT("llm_timeout"),
        LLM_ALREADY_WON("llm_already_won"),
        COMMAND_ALREADY_WON("command_already_won"),
        NOT_LATEST_ROUND("not_latest_round");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        /** telemetry payload 取值（与端侧 device_arbiter 事件同一套命名风格）。 */
        public String wire() {
            return wire;
        }
    }

    /** 收到候选（route：nlu-traditional 收到 asr 命令词 / llm 收到 llm 语义）。 */
    public static CloudArbiterEvent received(String route) {
        return new CloudArbiterEvent(Kind.RECEIVED, route, null, null);
    }

    /**
     * 仲裁胜出。decisionReason 为现有决策 reason（offline_won / llm_reply /
     * safety_timeout / asr_failed_fallback / arbitration_failed_fallback），
     * 面板可展示细节。
     */
    public static CloudArbiterEvent won(String route, Reason reason, String decisionReason) {
        return new CloudArbiterEvent(Kind.WON, route, reason, decisionReason);
    }

    /** 仲裁失败（reason：llm_already_won / command_already_won / not_latest_round）。 */
    public static CloudArbiterEvent lost(String route, Reason reason) {
        return new CloudArbiterEvent(Kind.LOST, route, reason, null);
    }
}
