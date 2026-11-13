/** RFC 9457 problem details as returned by the API. */
export interface Problem {
  status: number
  title?: string
  detail?: string
  code?: string
  errors?: { field: string; message: string }[]
}

export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail || problem.title || (problem.status ? `Request failed (HTTP ${problem.status})` : 'Request failed'))
    this.name = 'ApiError'
  }

  get code(): string | undefined {
    return this.problem.code
  }

  /** Field errors keyed by field name, ready for vee-validate's setErrors(). */
  get fieldErrors(): Record<string, string> {
    return Object.fromEntries((this.problem.errors ?? []).map((e) => [e.field, e.message]))
  }
}

/** Normalise anything thrown by $fetch (or the network) into an ApiError with a readable message. */
export function toApiError(e: unknown): ApiError {
  if (e instanceof ApiError) return e
  const err = e as { statusCode?: number; data?: unknown; message?: string } | null
  const data = err?.data as Partial<Problem> | undefined
  if (data && typeof data === 'object' && (data.detail || data.title)) {
    return new ApiError({ status: err?.statusCode ?? data.status ?? 0, ...data } as Problem)
  }
  const status = err?.statusCode ?? 0
  if (!status || status >= 502) {
    return new ApiError({ status, code: 'API_UNREACHABLE', detail: 'Cannot reach the KhmerDub API (Spring Boot on 127.0.0.1:8180). Is it running?' })
  }
  return new ApiError({ status, detail: err?.message ?? 'Request failed' })
}

type Method = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'

/** Same-origin call through the Nuxt proxy (/api/** -> Spring Boot). */
export async function api<T>(path: string, options: { method?: Method; body?: unknown; query?: Record<string, unknown> } = {}): Promise<T> {
  try {
    return await $fetch<T>(`/api${path}`, options as Parameters<typeof $fetch>[1])
  } catch (e) {
    throw toApiError(e)
  }
}
