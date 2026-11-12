import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { describeError, useSystemStore, type SystemHealth } from '~/stores/system'

const healthy: SystemHealth = {
  status: 'UP',
  version: '0.1.0',
  checkedAt: '2026-11-12T00:00:00Z',
  components: { database: { status: 'UP', details: { schemaVersion: '1' } } },
}

describe('system store', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => vi.unstubAllGlobals())

  it('loads health over HTTP', async () => {
    const fetchMock = vi.fn().mockResolvedValue(healthy)
    vi.stubGlobal('$fetch', fetchMock)
    const store = useSystemStore()
    await store.fetchHealth()
    expect(fetchMock).toHaveBeenCalledWith('/api/health')
    expect(store.overall).toBe('UP')
    expect(store.source).toBe('http')
    expect(store.error).toBeNull()
    expect(store.loading).toBe(false)
  })

  it('reports the API as down when the request fails', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockRejectedValue(Object.assign(new Error('502'), { statusCode: 502 })))
    const store = useSystemStore()
    await store.fetchHealth()
    expect(store.overall).toBe('DOWN')
    expect(store.error).toContain('Cannot reach the KhmerDub API')
  })

  it('applies live WebSocket updates and clears earlier errors', () => {
    const store = useSystemStore()
    store.error = 'old error'
    store.applyHealth({ ...healthy, status: 'WARN' }, 'websocket')
    expect(store.overall).toBe('WARN')
    expect(store.source).toBe('websocket')
    expect(store.error).toBeNull()
  })

  it('records a failed worker round trip', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockResolvedValue({ ok: false, error: 'No worker finished the task within 5 s' }))
    const store = useSystemStore()
    await store.runRoundTrip()
    expect(store.roundTrip?.ok).toBe(false)
    expect(store.roundTripRunning).toBe(false)
  })
})

describe('describeError', () => {
  it('keeps client-side error messages', () => {
    expect(describeError(Object.assign(new Error('Bad request'), { statusCode: 400 }))).toBe('Bad request')
  })
})
