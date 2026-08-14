import { useMemo } from "react";
import type { TelemetryEvent } from "../types";
import { stageLabel, stageOrder } from "../stages";

interface Props {
  events: TelemetryEvent[];
}

/** payload 摘要优先展示的关键字段，其余按原始顺序补齐。 */
const PREFERRED_KEYS = [
  "text",
  "reason",
  "decision",
  "result",
  "route",
  "source",
  "bytes",
  "durationMs",
];
const MAX_KEYS = 3;
const MAX_VALUE_LEN = 50;

function truncate(v: string): string {
  return v.length > MAX_VALUE_LEN ? `${v.slice(0, MAX_VALUE_LEN)}…` : v;
}

function payloadPreview(payload: Record<string, unknown>): string {
  const keys = [
    ...PREFERRED_KEYS.filter((k) => k in payload),
    ...Object.keys(payload).filter((k) => !PREFERRED_KEYS.includes(k)),
  ].slice(0, MAX_KEYS);
  return keys
    .map((k) => {
      const raw = payload[k];
      let v: string;
      if (typeof raw === "object" && raw !== null) {
        try {
          v = JSON.stringify(raw);
        } catch {
          v = "[?]";
        }
      } else {
        v = String(raw ?? "");
      }
      return `${k}=${truncate(v)}`;
    })
    .join("  ");
}

/**
 * 时间线：按**语义链顺序**（stageOrder）为主键、tsMs 为次键排序。
 * 端侧/服务器时钟存在偏差（旧 APK 未换算时可达数百 ms），纯按 tsMs 会把
 * 服务器 TTS 请求排到端云仲裁之前；语义顺序兜底保证展示顺序正确，
 * +X ms 相对耗时取事件最小 tsMs（跨端换算后端侧/服务器事件可对齐比较）。
 */
export default function Timeline({ events }: Props) {
  const sorted = useMemo(
    () =>
      [...events].sort(
        (a, b) => stageOrder(a.stage) - stageOrder(b.stage) || a.tsMs - b.tsMs,
      ),
    [events],
  );

  if (sorted.length === 0) {
    return <p className="hint">无事件</p>;
  }

  const first = Math.min(...events.map((e) => e.tsMs));

  return (
    <ol className="timeline">
      {sorted.map((e, i) => {
        const cls = e.level === "error" ? "err" : e.level === "warn" ? "warn" : "";
        return (
          <li key={`${e.stage}-${i}`} className={`tl-row ${cls}`}>
            <span className="tl-stage">{stageLabel(e.stage)}</span>
            <span className="tl-dt">+{(e.tsMs - first).toFixed(0)} ms</span>
            <span className="tl-payload">{payloadPreview(e.payload)}</span>
          </li>
        );
      })}
    </ol>
  );
}
