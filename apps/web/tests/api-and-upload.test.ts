import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, toApiError } from '~/utils/api'
import { uploadMovie } from '~/utils/upload'

afterEach(() => vi.unstubAllGlobals())

describe('toApiError', () => {
  it('keeps problem details and field errors from the API', () => {
    const e = toApiError({ statusCode: 400, data: { status: 400, detail: 'Some fields are invalid.', code: 'VALIDATION_FAILED',
      errors: [{ field: 'name', message: 'Enter a project name' }] } })
    expect(e.message).toBe('Some fields are invalid.')
    expect(e.code).toBe('VALIDATION_FAILED')
    expect(e.fieldErrors).toEqual({ name: 'Enter a project name' })
  })

  it('explains an unreachable API', () => {
    expect(toApiError({ statusCode: 502 }).message).toContain('Cannot reach the KhmerDub API')
    expect(toApiError(new TypeError('fetch failed')).code).toBe('API_UNREACHABLE')
  })

  it('api() prefixes /api and converts errors', async () => {
    const fetchMock = vi.fn().mockRejectedValue({ statusCode: 409, data: { detail: 'A job is already active', code: 'JOB_ALREADY_ACTIVE' } })
    vi.stubGlobal('$fetch', fetchMock)
    await expect(api('/projects/x/extract-audio', { method: 'POST', body: {} })).rejects.toMatchObject({ code: 'JOB_ALREADY_ACTIVE' })
    expect(fetchMock).toHaveBeenCalledWith('/api/projects/x/extract-audio', { method: 'POST', body: {} })
  })
})

class FakeXhr {
  static last: FakeXhr
  headers: Record<string, string> = {}
  upload: { onprogress?: (e: { loaded: number; total: number; lengthComputable: boolean }) => void } = {}
  onload?: () => void
  onerror?: () => void
  onabort?: () => void
  status = 0
  responseText = ''
  method = ''
  url = ''
  body: unknown
  constructor() { FakeXhr.last = this }
  open(method: string, url: string) { this.method = method; this.url = url }
  setRequestHeader(k: string, v: string) { this.headers[k] = v }
  send(body: unknown) { this.body = body }
  abort() { this.onabort?.() }
}

describe('uploadMovie', () => {
  const file = new File(['movie-bytes'], 'Phim ថ្មី.mp4')

  it('streams the file with its encoded name and reports progress', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXhr)
    const progress: number[] = []
    const h = uploadMovie('http://127.0.0.1:8180/api/projects/p/upload', file, (loaded) => progress.push(loaded))
    const xhr = FakeXhr.last
    expect(xhr.method).toBe('POST')
    expect(xhr.headers['X-File-Name']).toBe(encodeURIComponent('Phim ថ្មី.mp4'))
    expect(xhr.headers['Content-Type']).toBe('application/octet-stream')
    expect(xhr.body).toBe(file)
    xhr.upload.onprogress?.({ loaded: 5, total: 11, lengthComputable: true })
    xhr.status = 201
    xhr.responseText = JSON.stringify({ original: { id: 'a' }, importJob: { id: 'j' } })
    xhr.onload?.()
    await expect(h.promise).resolves.toMatchObject({ importJob: { id: 'j' } })
    expect(progress).toEqual([5])
  })

  it('surfaces the API problem on failure and supports cancel', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXhr)
    const failed = uploadMovie('u', file, () => {})
    FakeXhr.last.status = 507
    FakeXhr.last.responseText = JSON.stringify({ detail: 'Not enough free disk space', code: 'INSUFFICIENT_STORAGE' })
    FakeXhr.last.onload?.()
    await expect(failed.promise).rejects.toBeInstanceOf(ApiError)
    await expect(failed.promise).rejects.toMatchObject({ code: 'INSUFFICIENT_STORAGE' })

    const cancelled = uploadMovie('u', file, () => {})
    cancelled.abort()
    await expect(cancelled.promise).rejects.toMatchObject({ code: 'ABORTED' })
  })
})
