import { useEffect, useState } from "react";

const operations = {
  plan: "路线规划", start: "开始导航", stop: "结束导航", search: "沿途搜索",
  cancel_search: "取消沿途搜索", map_visibility: "地图显隐", map_control: "地图控制",
  audio_mute: "导航静音", audio_volume: "导航音量",
} as const;

const outcomes = {
  succeeded: "成功", accepted: "已受理", unknown: "结果未知", unsupported: "能力不可用",
  missing_location: "缺少定位", failed: "失败", timeout: "超时", cancelled: "已取消",
  no_results: "无结果", rejected: "已拒绝",
} as const;

type Outcome = keyof typeof outcomes;
const outcomeColumns = Object.keys(outcomes) as Outcome[];

interface OperationStatistics {
  operation: keyof typeof operations;
  total: number;
  successRate: number;
  outcomes: Record<Outcome, number>;
  p50Ms: number;
  p95Ms: number;
}

type QueryState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; rows: OperationStatistics[] };

export default function NavigationStats({ device }: { device: string }) {
  const [hours, setHours] = useState(24);
  const [refresh, setRefresh] = useState(0);
  const [query, setQuery] = useState<QueryState>({ status: "loading" });

  useEffect(() => {
    const abort = new AbortController();
    const to = Date.now();
    const params = new URLSearchParams({
      from: String(to - hours * 3_600_000),
      to: String(to),
    });
    if (device) params.set("device", device);
    fetch(`/api/telemetry/navigation/stats?${params}`, { signal: abort.signal })
      .then(async (response) => {
        if (response.status === 401) {
          if (!abort.signal.aborted) setQuery({ status: "error", message: "查询导航统计需要遥测管理权限" });
          return;
        }
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json() as { operations: OperationStatistics[] };
        if (!abort.signal.aborted) setQuery({ status: "ready", rows: data.operations });
      })
      .catch(() => {
        if (!abort.signal.aborted) setQuery({ status: "error", message: "无法获取导航统计，请刷新重试" });
      });
    return () => abort.abort();
  }, [device, hours, refresh]);

  return (
    <section className="pane navigation-stats" aria-labelledby="navigation-stats-heading">
      <div className="navigation-stats-toolbar">
        <h2 id="navigation-stats-heading">导航质量</h2>
        <div className="navigation-stats-filters">
          <label>
            时间范围
            <select value={hours} onChange={(event) => {
              setQuery({ status: "loading" });
              setHours(Number(event.target.value));
            }}>
              <option value="1">最近 1 小时</option>
              <option value="24">最近 24 小时</option>
              <option value="168">最近 7 天</option>
            </select>
          </label>
          <button type="button" onClick={() => {
            setQuery({ status: "loading" });
            setRefresh((value) => value + 1);
          }}>刷新</button>
        </div>
      </div>
      {query.status === "loading" && <p className="hint" role="status">正在加载导航统计…</p>}
      {query.status === "error" && <p className="hint" role="alert">{query.message}</p>}
      {query.status === "ready" && (query.rows.length === 0 ? (
        <p className="hint" role="status">所选范围内暂无导航操作数据</p>
      ) : (
        <>
          <p className="hint">按操作结束时间统计。成功率为成功数除以全部终态样本数，耗时分位数涵盖该操作的所有终态样本。</p>
          <div className="navigation-stats-scroll">
            <table>
              <thead><tr>
                <th scope="col">操作</th>
                <th scope="col">样本数</th>
                <th scope="col">成功率</th>
                {outcomeColumns.map((outcome) => <th key={outcome} scope="col">{outcomes[outcome]}</th>)}
                <th scope="col">P50 耗时</th>
                <th scope="col">P95 耗时</th>
              </tr></thead>
              <tbody>{query.rows.map((row) => (
                <tr key={row.operation}>
                  <th scope="row">{operations[row.operation]}</th>
                  <td>{row.total}</td>
                  <td>{(row.successRate * 100).toFixed(1)}%</td>
                  {outcomeColumns.map((outcome) => <td key={outcome}>{row.outcomes[outcome]}</td>)}
                  <td>{row.p50Ms} ms</td>
                  <td>{row.p95Ms} ms</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </>
      ))}
    </section>
  );
}
