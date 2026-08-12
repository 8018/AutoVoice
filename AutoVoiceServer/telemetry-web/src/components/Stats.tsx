import { useMemo } from "react";
import type { RoundSummary } from "../types";
import { isFailedSummary } from "../stages";

interface Props {
  rounds: RoundSummary[];
}

function fmtMs(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)} s`;
  return `${ms.toFixed(0)} ms`;
}

/** 顶部统计条：总数 / 失败 / 平均端到端耗时 / TTS 缓存命中率 / 云端决策分布。 */
export default function Stats({ rounds }: Props) {
  const s = useMemo(() => {
    const total = rounds.length;
    const failed = rounds.filter(isFailedSummary).length;

    const withE2E = rounds.filter((r) => r.endMs > r.startMs);
    const avgE2E =
      withE2E.length > 0
        ? withE2E.reduce((acc, r) => acc + (r.endMs - r.startMs), 0) / withE2E.length
        : null;

    const withTts = rounds.filter((r) => r.ttsCacheHit !== null);
    const cacheHit = withTts.filter((r) => r.ttsCacheHit === true).length;
    const cacheRate = withTts.length > 0 ? (cacheHit / withTts.length) * 100 : null;

    // cloud_decision 三值分布（llm / nlu-traditional / cloud），其余归入其他
    const dist: Record<string, number> = {};
    for (const r of rounds) {
      const k = r.cloudDecision ?? "null";
      dist[k] = (dist[k] ?? 0) + 1;
    }
    const distText = ["llm", "nlu-traditional", "cloud"]
      .map((k) => `${k} ${dist[k] ?? 0}`)
      .concat(Object.keys(dist).filter((k) => !["llm", "nlu-traditional", "cloud"].includes(k)).map((k) => `${k} ${dist[k]}`))
      .join(" · ");

    return { total, failed, avgE2E, cacheRate, distText };
  }, [rounds]);

  return (
    <div className="stats">
      <div className="stat-card">
        <div className="label">总轮次</div>
        <div className="value">{s.total}</div>
      </div>
      <div className="stat-card">
        <div className="label">失败轮</div>
        <div className={s.failed > 0 ? "value err" : "value ok"}>{s.failed}</div>
      </div>
      <div className="stat-card">
        <div className="label">平均端到端耗时</div>
        <div className="value">{s.avgE2E === null ? "—" : fmtMs(s.avgE2E)}</div>
      </div>
      <div className="stat-card">
        <div className="label">TTS 缓存命中率</div>
        <div className="value">{s.cacheRate === null ? "—" : `${s.cacheRate.toFixed(0)}%`}</div>
        <div className="value sub">有 TTS 轮次 {s.cacheRate === null ? 0 : rounds.filter((r) => r.ttsCacheHit !== null).length}</div>
      </div>
      <div className="stat-card" style={{ flexBasis: "260px" }}>
        <div className="label">云端决策分布</div>
        <div className="value sub">{s.distText || "—"}</div>
      </div>
    </div>
  );
}
