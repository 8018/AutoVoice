import type { RoundDetail, RoundSummary } from "./types";

export async function fetchRounds(device?: string): Promise<RoundSummary[]> {
  const q = device ? `?device=${encodeURIComponent(device)}` : "";
  const res = await fetch(`/api/telemetry/rounds${q}`);
  if (!res.ok) throw new Error(`http ${res.status}`);
  return res.json();
}

export async function fetchRound(id: string): Promise<RoundDetail | null> {
  const res = await fetch(`/api/telemetry/rounds/${encodeURIComponent(id)}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`http ${res.status}`);
  return res.json();
}
