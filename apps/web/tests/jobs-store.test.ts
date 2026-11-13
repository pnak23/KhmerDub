import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useJobsStore } from '~/stores/jobs'
import { useProjectsStore } from '~/stores/projects'
import type { JobView, ProjectSummary } from '~/types/api'

const job = (version: number, status = 'RUNNING', progress = 10): JobView => ({
  id: 'j1', projectId: 'p1', type: 'EXTRACT_AUDIO', status: status as JobView['status'], currentStage: 'EXTRACT_AUDIO',
  stages: ['EXTRACT_AUDIO', 'MERGE_AUDIO'], progress, maxAttempts: 3, retryCount: 0, errorMessage: null, resultAssetId: null,
  parameters: {}, createdAt: '', startedAt: null, finishedAt: null, updatedAt: '', version, chunks: [],
})

const summary = (id: string, updatedAt: string, archived = false): ProjectSummary => ({
  id, name: id, status: 'IMPORTED', durationMs: 1, sourceLanguage: 'auto', archived, originalFilename: null,
  originalSizeBytes: null, activeJob: null, lastJob: null, createdAt: '', updatedAt,
})

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.unstubAllGlobals())

describe('jobs store', () => {
  it('keeps the newest snapshot when updates arrive out of order', () => {
    const store = useJobsStore()
    expect(store.apply(job(5, 'RUNNING', 50))).toBe(true)
    expect(store.apply(job(3, 'RUNNING', 30))).toBe(false) // late HTTP response
    expect(store.jobs.j1?.progress).toBe(50)
    expect(store.apply(job(6, 'PAUSED', 50))).toBe(true)
    expect(store.jobs.j1?.status).toBe('PAUSED')
  })

  it('posts job actions and applies the returned state', async () => {
    const fetchMock = vi.fn().mockResolvedValue(job(9, 'PAUSING'))
    vi.stubGlobal('$fetch', fetchMock)
    const store = useJobsStore()
    await store.act('j1', 'pause')
    expect(fetchMock).toHaveBeenCalledWith('/api/jobs/j1/pause', { method: 'POST' })
    expect(store.jobs.j1?.status).toBe('PAUSING')
  })
})

describe('projects store', () => {
  it('upserts live summaries, newest first, and hides archived projects', () => {
    const store = useProjectsStore()
    store.applySummary(summary('a', '2026-11-12T08:00:00Z'))
    store.applySummary(summary('b', '2026-11-12T09:00:00Z'))
    store.applySummary(summary('a', '2026-11-12T10:00:00Z'))
    expect(store.list.map((p) => p.id)).toEqual(['a', 'b'])
    store.applySummary(summary('b', '2026-11-12T11:00:00Z', true))
    expect(store.list.map((p) => p.id)).toEqual(['a'])
  })
})
