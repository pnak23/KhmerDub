<script setup lang="ts">
import type { ComponentHealth } from '~/stores/system'

interface WorkerBeat {
  hostname?: string
  os?: string
  pool?: string
  concurrency?: number
  queues?: string[]
  storageRoot?: string
  storageWritable?: boolean
  ffmpeg?: string | null
  apiReachable?: boolean
  lastSeen?: string
}

const system = useSystemStore()

const components = computed(() => system.health?.components ?? {})
const database = computed(() => components.value.database)
const storage = computed(() => components.value.storage)
const ai = computed(() => components.value.aiService)

/** Sub-components reported by the Python service (redis, ffmpeg, storage, workers). */
const aiParts = computed(() => (ai.value?.details?.components ?? {}) as Record<string, ComponentHealth>)
const workers = computed(() => (aiParts.value.workers?.details?.workers ?? []) as WorkerBeat[])
const ffmpeg = computed(() => aiParts.value.ffmpeg?.details as Record<string, { path?: string; version?: string }> | undefined)

const detail = (c: ComponentHealth | undefined, key: string) => c?.details?.[key] as string | number | boolean | undefined
</script>

<template>
  <div>
    <div class="d-flex align-items-center gap-3 mb-3">
      <h1 class="h4 mb-0">System Status</h1>
      <StatusBadge :status="system.overall" />
      <span class="small text-body-secondary">
        Updated {{ fromNow(system.lastUpdated) }}
        <template v-if="system.source"> via {{ system.source === 'websocket' ? 'live update' : 'refresh' }}</template>
      </span>
      <BButton size="sm" variant="outline-secondary" class="ms-auto" :disabled="system.loading" @click="system.fetchHealth()">
        <Icon name="tabler:refresh" /> Refresh
      </BButton>
    </div>

    <BAlert :model-value="!!system.error" variant="danger">{{ system.error }}</BAlert>

    <div class="row g-3">
      <!-- Database -->
      <div class="col-xl-4 col-lg-6">
        <BCard class="h-100">
          <div class="d-flex justify-content-between mb-2">
            <h2 class="h6 mb-0"><Icon name="tabler:database" /> PostgreSQL</h2>
            <StatusBadge :status="database?.status" />
          </div>
          <dl class="kd-dl small">
            <dt>Version</dt><dd>{{ detail(database, 'postgresVersion') ?? '—' }}</dd>
            <dt>Schema</dt><dd>V{{ detail(database, 'schemaVersion') ?? '?' }}</dd>
            <dt v-if="detail(database, 'error')">Error</dt><dd v-if="detail(database, 'error')" class="text-danger">{{ detail(database, 'error') }}</dd>
          </dl>
        </BCard>
      </div>

      <!-- Storage -->
      <div class="col-xl-4 col-lg-6">
        <BCard class="h-100">
          <div class="d-flex justify-content-between mb-2">
            <h2 class="h6 mb-0"><Icon name="tabler:folder" /> Storage</h2>
            <StatusBadge :status="storage?.status" />
          </div>
          <dl class="kd-dl small">
            <dt>Root</dt><dd class="kd-mono">{{ detail(storage, 'root') ?? '—' }}</dd>
            <dt>Free</dt><dd>{{ formatBytes(detail(storage, 'freeBytes') as number) }} of {{ formatBytes(detail(storage, 'totalBytes') as number) }}</dd>
            <dt>Writable</dt><dd>{{ detail(storage, 'writable') ? 'Yes' : 'No' }}</dd>
            <dt v-if="detail(storage, 'warning')">Warning</dt><dd v-if="detail(storage, 'warning')" class="text-warning-emphasis">{{ detail(storage, 'warning') }}</dd>
          </dl>
        </BCard>
      </div>

      <!-- AI service -->
      <div class="col-xl-4 col-lg-12">
        <BCard class="h-100">
          <div class="d-flex justify-content-between mb-2">
            <h2 class="h6 mb-0"><Icon name="tabler:cpu" /> AI service</h2>
            <StatusBadge :status="ai?.status" />
          </div>
          <p v-if="detail(ai, 'error')" class="small text-danger mb-2">{{ detail(ai, 'error') }}</p>
          <dl v-else class="kd-dl small">
            <dt>Redis queue</dt><dd><StatusBadge :status="aiParts.redis?.status" /> {{ aiParts.redis?.details?.version ?? '' }}</dd>
            <dt>FFmpeg</dt><dd><StatusBadge :status="aiParts.ffmpeg?.status" /></dd>
            <dt>Shared storage</dt><dd><StatusBadge :status="aiParts.storage?.status" /></dd>
            <dt>Workers</dt><dd><StatusBadge :status="aiParts.workers?.status" /> {{ workers.length }} running</dd>
            <dt>Cloud</dt><dd>{{ ai?.details?.cloudEnabled ? 'Enabled' : 'Disabled (local only)' }}</dd>
          </dl>
        </BCard>
      </div>

      <!-- Workers -->
      <div class="col-12">
        <BCard>
          <div class="d-flex align-items-center gap-2 mb-2">
            <h2 class="h6 mb-0"><Icon name="tabler:server-cog" /> Workers</h2>
            <BButton size="sm" variant="primary" class="ms-auto" :disabled="system.roundTripRunning" @click="system.runRoundTrip()">
              <BSpinner v-if="system.roundTripRunning" small /> Run worker round trip
            </BButton>
          </div>
          <p v-if="!workers.length" class="small text-body-secondary">
            No worker heartbeat. Start the worker to enable processing.
          </p>
          <div v-else class="table-responsive">
            <table class="table table-sm small align-middle mb-2">
              <thead>
                <tr><th>Worker</th><th>OS</th><th>Pool</th><th>Queues</th><th>Storage</th><th>FFmpeg</th><th>Calls API</th><th>Last seen</th></tr>
              </thead>
              <tbody>
                <tr v-for="w in workers" :key="w.hostname">
                  <td class="kd-mono">{{ w.hostname }}</td>
                  <td>{{ w.os }}</td>
                  <td>{{ w.pool }} × {{ w.concurrency }}</td>
                  <td>{{ w.queues?.join(', ') }}</td>
                  <td class="kd-mono">{{ w.storageRoot }} <BBadge :variant="w.storageWritable ? 'success' : 'danger'">{{ w.storageWritable ? 'writable' : 'read-only' }}</BBadge></td>
                  <td class="small">{{ w.ffmpeg?.split(' Copyright')[0] ?? 'missing' }}</td>
                  <td><BBadge :variant="w.apiReachable ? 'success' : 'warning'">{{ w.apiReachable ? 'yes' : 'no' }}</BBadge></td>
                  <td>{{ fromNow(w.lastSeen) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <BAlert v-if="system.roundTrip" :model-value="true" :variant="system.roundTrip.ok ? 'success' : 'danger'" class="small mb-0">
            <template v-if="system.roundTrip.ok">
              Round trip OK in {{ system.roundTrip.elapsedMs }} ms: a task went through Redis to
              <span class="kd-mono">{{ system.roundTrip.worker?.worker }}</span> ({{ system.roundTrip.worker?.os }}),
              which wrote a file the API service could read back.
            </template>
            <template v-else>Round trip failed: {{ system.roundTrip.error ?? system.roundTrip.sharedStorage?.error }}</template>
          </BAlert>
        </BCard>
      </div>

      <!-- FFmpeg paths -->
      <div v-if="ffmpeg" class="col-12">
        <BCard>
          <h2 class="h6"><Icon name="tabler:video" /> FFmpeg (API host)</h2>
          <dl class="kd-dl small mb-0">
            <template v-for="(info, name) in ffmpeg" :key="name">
              <dt>{{ name }}</dt>
              <dd><span class="kd-mono">{{ info.path ?? 'not found' }}</span> — {{ info.version?.split(' Copyright')[0] ?? 'not working' }}</dd>
            </template>
          </dl>
        </BCard>
      </div>
    </div>
  </div>
</template>
