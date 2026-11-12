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
