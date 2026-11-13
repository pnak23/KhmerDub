import { describe, expect, it } from 'vitest'
import { formatBytes, formatDuration, fromNow, humanize, jobVariant, statusLabel, statusVariant } from '~/utils/format'

describe('formatDuration / jobVariant / humanize', () => {
  it('formats movie durations', () => {
    expect(formatDuration(65_000)).toBe('1:05')
    expect(formatDuration(10_800_000)).toBe('3:00:00')
    expect(formatDuration(5_025_400)).toBe('1:23:45')
    expect(formatDuration(null)).toBe('—')
  })
  it('maps job states to colours and labels', () => {
    expect(jobVariant('FAILED')).toBe('danger')
    expect(jobVariant('PAUSED')).toBe('warning')
    expect(jobVariant('QUEUED')).toBe('secondary')
    expect(humanize('EXTRACT_AUDIO')).toBe('Extract audio')
  })
})

describe('statusVariant / statusLabel', () => {
  it('maps health states to Bootstrap variants and labels', () => {
    expect(statusVariant('UP')).toBe('success')
    expect(statusVariant('WARN')).toBe('warning')
    expect(statusVariant('DOWN')).toBe('danger')
    expect(statusVariant(null)).toBe('secondary')
    expect(statusLabel('WARN')).toBe('Needs attention')
    expect(statusLabel(undefined)).toBe('Unknown')
  })
})

describe('formatBytes', () => {
  it('formats with binary units', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(102.3 * 1024 ** 3)).toBe('102.3 GB')
  })
  it('handles missing values', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
  })
})

describe('fromNow', () => {
  it('handles missing values', () => {
    expect(fromNow(null)).toBe('—')
  })
  it('describes a recent time', () => {
    expect(fromNow(new Date().toISOString())).toBe('a few seconds ago')
  })
})
