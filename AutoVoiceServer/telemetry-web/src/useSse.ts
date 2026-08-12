import { useEffect, useState } from "react";
import type { RoundSummary } from "./types";

/**
 * SSE 实时轮次订阅：EventSource 监听 /api/telemetry/stream 的 round 事件，
 * 前插列表并按 utteranceId 去重；initial 变化（设备筛选切换重拉历史）时重置列表；
 * 组件卸载时 close；断线重连由浏览器原生处理。
 */
export function useRounds(initial: RoundSummary[]) {
  const [rounds, setRounds] = useState<RoundSummary[]>(initial);

  useEffect(() => {
    const es = new EventSource("/api/telemetry/stream");
    es.addEventListener("round", (e) => {
      try {
        const s = JSON.parse((e as MessageEvent).data) as RoundSummary;
        setRounds((prev) => [s, ...prev.filter((r) => r.utteranceId !== s.utteranceId)]);
      } catch {
        /* 忽略坏帧 */
      }
    });
    return () => es.close();
  }, []);

  // 派生 state 重置：initial 引用变化（设备切换重拉历史）时同步替换列表
  const [base, setBase] = useState(initial);
  if (base !== initial) {
    setBase(initial);
    setRounds(initial);
  }

  return rounds;
}
