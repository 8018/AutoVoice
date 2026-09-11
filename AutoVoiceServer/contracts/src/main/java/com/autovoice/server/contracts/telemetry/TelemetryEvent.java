package com.autovoice.server.contracts.telemetry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 链路单阶段事件(D01b 扩展):stage + 时刻 + 级别 + 自由 payload,加上统一上下文字段
 * subject/session/turn/request/configVersion/result/reason。
 *
 * <p>上下文字段缺省为空串;现有存储仅序列化 payload,JSON 形状不变。
 * 字段约定见 {@link TelemetryFields}。</p>
 */
public record TelemetryEvent(
        @JsonProperty("stage") String stage,
        @JsonProperty("tsMs") long tsMs,
        @JsonProperty("level") String level,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("subject") String subject,
        @JsonProperty("session") String session,
        @JsonProperty("turn") String turn,
        @JsonProperty("request") String request,
        @JsonProperty("configVersion") String configVersion,
        @JsonProperty("result") String result,
        @JsonProperty("reason") String reason) {

    @JsonCreator
    public TelemetryEvent {
        payload = payload == null ? Map.of() : payload;
        subject = subject == null ? "" : subject;
        session = session == null ? "" : session;
        turn = turn == null ? "" : turn;
        request = request == null ? "" : request;
        configVersion = configVersion == null ? "" : configVersion;
        result = result == null ? "" : result;
        reason = reason == null ? "" : reason;
    }

    /** 兼容构造:历史调用方仅携带 stage/时刻/级别/payload。 */
    public TelemetryEvent(String stage, long tsMs, String level, Map<String, Object> payload) {
        this(stage, tsMs, level, payload, "", "", "", "", "", "", "");
    }
}
