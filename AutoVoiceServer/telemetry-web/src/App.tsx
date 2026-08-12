import { useEffect, useMemo, useState } from "react";
import { fetchRounds } from "./api";
import { useRounds } from "./useSse";
import type { RoundSummary } from "./types";
import RoundList from "./components/RoundList";
import RoundDetail from "./components/RoundDetail";
import Stats from "./components/Stats";

export default function App() {
  const [device, setDevice] = useState("");
  const [history, setHistory] = useState<RoundSummary[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // 设备筛选切换 → 重拉历史（SSE 后续实时追加）
  useEffect(() => {
    let alive = true;
    setLoadError(null);
    fetchRounds(device || undefined)
      .then((rs) => {
        if (alive) setHistory(rs);
      })
      .catch(() => {
        if (alive) {
          setHistory([]);
          setLoadError("历史数据拉取失败（服务未启动？）——SSE 到达后仍会实时展示");
        }
      });
    return () => {
      alive = false;
    };
  }, [device]);

  // SSE 实时合并（前插去重；device 重拉历史时重置）
  const rounds = useRounds(history);

  const visible = useMemo(
    () => (device ? rounds.filter((r) => r.deviceId === device) : rounds),
    [rounds, device]
  );

  const devices = useMemo(() => {
    const s = new Set<string>();
    for (const r of rounds) {
      if (r.deviceId) s.add(r.deviceId);
    }
    return [...s].sort();
  }, [rounds]);

  return (
    <div className="app">
      <header className="topbar">
        <h1>AutoVoice 链路数据平台</h1>
        <label className="device-filter">
          设备：
          <select value={device} onChange={(e) => setDevice(e.target.value)}>
            <option value="">全部设备</option>
            {devices.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
      </header>

      {loadError && <div className="banner warn">{loadError}</div>}

      <Stats rounds={visible} />

      <main className="layout">
        <section className="pane list-pane">
          <h2>实时轮次</h2>
          <RoundList rounds={visible} selectedId={selectedId} onSelect={setSelectedId} />
        </section>
        <section className="pane detail-pane">
          <h2>轮次明细</h2>
          {selectedId ? (
            <RoundDetail utteranceId={selectedId} />
          ) : (
            <p className="hint">← 点击左侧轮次查看明细</p>
          )}
        </section>
      </main>
    </div>
  );
}
