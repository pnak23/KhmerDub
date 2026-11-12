import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { HealthStatus } from '~/utils/format'

export interface ComponentHealth {
  status: HealthStatus
  details: Record<string, unknown>
}

export interface SystemHealth {
  status: HealthStatus
  version: string
  checkedAt: string
  components: Record<string, ComponentHealth>
}

export interface RoundTripResult {
  ok: boolean
  elapsedMs?: number
  error?: string
  worker?: Record<string, unknown>
  sharedStorage?: { path?: string; visibleToApi?: boolean; error?: string }
}

export function describeError(e: unknown): string {
  const status = (e as { statusCode?: number } | null)?.statusCode
  if (status === undefined || status >= 500) {
    return 'Cannot reach the KhmerDub API (Spring Boot on 127.0.0.1:8180). Is it running?'
  }
  return e instanceof Error ? e.message : String(e)
}

export const useSystemStore = defineStore('system', () => {
  const health = ref<SystemHealth | null>(null)
  const error = ref<string | null>(null)
  const loading = ref(false)
  const lastUpdated = ref<string | null>(null)
  const source = ref<'http' | 'websocket' | null>(null)
  const liveConnected = ref(false)
  const roundTrip = ref<RoundTripResult | null>(null)
  const roundTripRunning = ref(false)

  const overall = computed<HealthStatus | null>(() => {
    if (error.value) return 'DOWN'
    return health.value?.status ?? null
  })

  function applyHealth(next: SystemHealth, from: 'http' | 'websocket') {
    health.value = next
    lastUpdated.value = new Date().toISOString()
    source.value = from
    error.value = null
  }

  async function fetchHealth() {
    loading.value = true
    try {
      applyHealth(await $fetch<SystemHealth>('/api/health'), 'http')
    } catch (e) {
      error.value = describeError(e)
    } finally {
      loading.value = false
    }
  }

  async function runRoundTrip() {
    roundTripRunning.value = true
    try {
      roundTrip.value = await $fetch<RoundTripResult>('/api/system/worker-roundtrip', { method: 'POST' })
    } catch (e) {
      roundTrip.value = { ok: false, error: describeError(e) }
    } finally {
      roundTripRunning.value = false
    }
  }

  return {
    health, error, loading, lastUpdated, source, liveConnected, roundTrip, roundTripRunning,
    overall, applyHealth, fetchHealth, runRoundTrip,
  }
})
