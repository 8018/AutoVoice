import { useEffect, useState } from "react";
import { fetchRound } from "../api";
import type { RoundDetail as RoundDetailData } from "../types";
import { isFailedSummary } from "../stages";
import Timeline from "./Timeline";
import AudioPlayer from "./AudioPlayer";

interface Props {
  utteranceId: string;
}

export default function RoundDetail({ utteranceId }: Props) {
  const [detail, setDetail] = useState<RoundDetailData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;
    setDetail(null);
    setError(null);
    setLoaded(false);
    fetchRound(utteranceId)
      .then((d) => {
        if (alive) {
          setDetail(d);
          setLoaded(true);
        }
      })
      .catch(() => {
        if (alive) {
          setError("明细拉取失败");
          setLoaded(true);
        }
      });
    return () => {
      alive = false;
    };
  }, [utteranceId]);

  if (error) return <p className="hint err">{error}</p>;
  if (!loaded) return <p className="hint">加载中…</p>;
  if (!detail) return <p className="hint err">该轮次无明细（404）</p>;

  const s = detail.summary;
  const failed =
    isFailedSummary(s) || detail.events.some((e) => e.level === "error");

  return (
    <div className="detail">
      <div className="detail-head">
        <code>{s.utteranceId}</code>
        <span className={failed ? "badge err" : "badge ok"}>{failed ? "失败" : "正常"}</span>
      </div>

      <dl className="kv">
        <div>
          <dt>设备</dt>
          <dd>{s.deviceId || "—"}</dd>
        </div>
        <div>
          <dt>来源</dt>
          <dd>{s.source || "—"}</dd>
        </div>
        <div>
          <dt>本地决策</dt>
          <dd>{s.localDecision ?? "—"}</dd>
        </div>
        <div>
          <dt>云端决策</dt>
          <dd>{s.cloudDecision ?? "—"}</dd>
        </div>
        <div>
          <dt>最终决策</dt>
          <dd>{s.finalDecision ?? "—"}</dd>
        </div>
        <div>
          <dt>播放结果</dt>
          <dd>{s.playbackResult ?? "—"}</dd>
        </div>
        <div>
          <dt>缓存</dt>
          <dd>{s.ttsCacheHit === null ? "—" : s.ttsCacheHit ? "命中" : "未命中"}</dd>
        </div>
        <div>
          <dt>耗时</dt>
          <dd>
            {s.endMs > s.startMs ? `${(s.endMs - s.startMs).toFixed(0)} ms` : "—"}
          </dd>
        </div>
      </dl>

      <h3>时间线</h3>
      <Timeline events={detail.events} />

      {s.audioPath && (
        <AudioPlayer
          src={`/api/telemetry/audio/${s.audioPath}`}
          fileName={s.audioPath}
        />
      )}
    </div>
  );
}
