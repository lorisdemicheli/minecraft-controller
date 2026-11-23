/** Display formatters shared by the pages (Italian locale). */

const number = new Intl.NumberFormat('it', { maximumFractionDigits: 1 });
const compactNumber = new Intl.NumberFormat('it', { notation: 'compact' });

/** Megabytes, e.g. `512 MB`, `1,5 GB`. */
export function formatMemory(mb: number | null | undefined): string {
  if (mb == null) return '—';
  return mb >= 1024 ? `${number.format(mb / 1024)} GB` : `${Math.round(mb)} MB`;
}

/** Milli-CPU as vCPU, e.g. `0,4 vCPU`. */
export function formatCpu(milli: number | null | undefined): string {
  return milli == null ? '—' : `${number.format(milli / 1000)} vCPU`;
}

/** Bytes, e.g. `12 KB`, `3,4 GB`. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${unit === 0 ? value : number.format(value)} ${units[unit]}`;
}

export function formatCompact(n: number | null | undefined): string {
  return compactNumber.format(n ?? 0);
}

/** Share of `max` taken by `value`, clamped to 0–100; null when it cannot be computed. */
export function percent(value: number | null | undefined, max: number | null | undefined): number | null {
  if (value == null || !max) return null;
  return Math.min(100, Math.max(0, (value / max) * 100));
}
