import { describe, expect, it } from 'vitest'
import { formatBytes, fromNow, statusLabel, statusVariant } from '~/utils/format'

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
