import type { RoundSummary } from "../types";
import { isFailedSummary } from "../stages";

interface Props {
  rounds: RoundSummary[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

export default function RoundList({ rounds, selectedId, onSelect }: Props) {
  if (rounds.length === 0) {
    return <p className="hint">暂无轮次（等待实时推送…）</p>;
  }
  return (
    <ul className="round-list">
      {rounds.map((r) => {
        const failed = isFailedSummary(r);
        return (
          <li
            key={r.utteranceId}
            className={r.utteranceId === selectedId ? "row sel" : "row"}
            onClick={() => onSelect(r.utteranceId)}
          >
            <div className="row-top">
              <span className="utt">{r.utteranceId}</span>
              <span className="time">{new Date(r.startMs).toLocaleTimeString()}</span>
            </div>
            <div className="row-bottom">
              <span className={failed ? "fd err" : "fd"}>{r.finalDecision ?? "—"}</span>
              {r.ttsCacheHit === true && <span className="badge cache">缓存命中</span>}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
