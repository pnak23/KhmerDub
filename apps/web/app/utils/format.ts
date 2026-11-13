import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'

dayjs.extend(relativeTime)

export type HealthStatus = 'UP' | 'WARN' | 'DOWN'
export type Variant = 'success' | 'warning' | 'danger' | 'secondary'

export function statusVariant(status: string | null | undefined): Variant {
  switch (status) {
    case 'UP':
      return 'success'
    case 'WARN':
      return 'warning'
    case 'DOWN':
      return 'danger'
    default:
      return 'secondary'
  }
}

export function statusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'UP':
      return 'Healthy'
    case 'WARN':
      return 'Needs attention'
    case 'DOWN':
      return 'Down'
    default:
      return 'Unknown'
  }
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

export function fromNow(iso: string | null | undefined): string {
  return iso ? dayjs(iso).fromNow() : '—'
}

/** 5025000 -> "1:23:45"; 65000 -> "1:05". */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms < 0) return '—'
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

export type JobVariant = 'secondary' | 'primary' | 'warning' | 'success' | 'danger' | 'dark'

export function jobVariant(status: string | null | undefined): JobVariant {
  switch (status) {
    case 'RUNNING': return 'primary'
    case 'PAUSING':
    case 'PAUSED': return 'warning'
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    case 'CANCELLING':
    case 'CANCELLED': return 'dark'
    default: return 'secondary'
  }
}

export function chunkVariant(status: string): string {
  switch (status) {
    case 'COMPLETED':
    case 'SKIPPED': return 'success'
    case 'RUNNING': return 'primary'
    case 'DISPATCHED': return 'info'
    case 'FAILED': return 'danger'
    case 'CANCELLED': return 'dark'
    default: return 'secondary'
  }
}

export function humanize(value: string | null | undefined): string {
  if (!value) return '—'
  const s = value.replace(/_/g, ' ').toLowerCase()
  return s.charAt(0).toUpperCase() + s.slice(1)
}
