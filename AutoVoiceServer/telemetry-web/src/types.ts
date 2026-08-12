export interface RoundSummary {
  utteranceId: string;
  deviceId: string;
  source: string;
  startMs: number;
  endMs: number;
  localDecision: string | null;
  cloudDecision: string | null;
  finalDecision: string | null;
  ttsCacheHit: boolean | null;
  playbackResult: string | null;
  audioPath: string | null;
}

export interface TelemetryEvent {
  stage: string;
  tsMs: number;
  level: string;
  payload: Record<string, unknown>;
}

export interface RoundDetail {
  summary: RoundSummary;
  events: TelemetryEvent[];
}
