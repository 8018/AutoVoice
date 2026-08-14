import { useMemo } from "react";
import type { TelemetryEvent } from "../types";
import { stageLabel } from "../stages";

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

/** 事件按 tsMs 升序的时间线：阶段中文标签 + 相对首事件耗时 + payload 关键字段。 */
export default function Timeline({ events }: Props) {
  const sorted = useMemo(() => [...events].sort((a, b) => a.tsMs - b.tsMs), [events]);

  if (sorted.length === 0) {
    return <p className="hint">无事件</p>;
  }

  const first = sorted[0].tsMs;

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
