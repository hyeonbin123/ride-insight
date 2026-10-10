/** Small formatting helpers shared by the pages. */

export function formatDuration(secs: number | undefined | null): string {
  if (secs == null || !Number.isFinite(secs)) return "-";
  const s = Math.round(secs);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}` : `${m}:${String(r).padStart(2, "0")}`;
}

export function formatWatts(w: number | undefined | null, digits = 0): string {
  return w == null || !Number.isFinite(w) ? "-" : `${w.toFixed(digits)} W`;
}

export function formatNumber(v: number | undefined | null, digits = 1): string {
  return v == null || !Number.isFinite(v) ? "-" : v.toFixed(digits);
}

export function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Window key like "20m_critical_power" -> "20m". */
export function windowLabel(key: string): string {
  return key.replace("_critical_power", "");
}

/** Seconds -> compact label for curve axes: 1s, 30s, 5m, 1h. */
export function secsLabel(secs: number): string {
  if (secs < 60) return `${secs}s`;
  if (secs < 3600) return `${Math.round(secs / 60)}m`;
  const h = Math.floor(secs / 3600);
  const m = Math.round((secs % 3600) / 60);
  return m ? `${h}h${m}m` : `${h}h`;
}

export function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}
