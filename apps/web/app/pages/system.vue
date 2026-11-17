<script setup lang="ts">
import type { ComponentHealth } from '~/stores/system'
import type { MlStatus } from '~/types/api'

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

/** Speech stack (M3b): allow-listed fields only — no paths, tokens or voice fingerprints. */
const ml = ref<MlStatus | null>(null)
const mlError = ref<string | null>(null)
async function loadMl() {
  try {
    ml.value = await api<MlStatus>('/system/ml')
    mlError.value = null
  } catch (e) {
    mlError.value = toApiError(e).message
  }
}
onMounted(loadMl)

function yesNo(v: boolean | null | undefined): { text: string; variant: 'success' | 'danger' | 'secondary' } {
  return v === true ? { text: 'Yes', variant: 'success' } : v === false ? { text: 'No', variant: 'danger' } : { text: 'Unknown', variant: 'secondary' }
}
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

      <!-- Speech stack (M3b) -->
      <div class="col-12">
        <BCard class="kd-speech-stack">
          <div class="d-flex align-items-center gap-2 mb-2">
            <h2 class="h6 mb-0"><Icon name="tabler:brain" /> Speech stack</h2>
            <BButton size="sm" variant="outline-secondary" class="ms-auto" @click="loadMl"><Icon name="tabler:refresh" /> Refresh</BButton>
          </div>
          <BAlert :model-value="!!mlError" variant="danger" class="small py-2">{{ mlError }}</BAlert>
          <div v-if="ml" class="row g-3 small">
            <div class="col-lg-6">
              <div class="fw-medium mb-1">Verified models</div>
              <p v-if="ml.models.manifest === 'missing'" class="text-warning-emphasis">No verified model manifest found.</p>
              <table v-else class="table table-sm mb-0 kd-models">
                <thead><tr><th>Model</th><th>Revision</th><th>Files</th><th>Size</th><th>Verified</th></tr></thead>
                <tbody>
                  <tr v-for="(m, name) in ml.models.list" :key="name">
                    <td class="kd-mono">{{ name }}</td>
                    <td class="kd-mono">{{ m.revision?.slice(0, 10) ?? '—' }}</td>
                    <td>
                      {{ m.filesPresent ?? '?' }} / {{ m.files ?? '?' }}
                      <BBadge v-if="m.filesPresent !== null && m.files !== null && m.filesPresent < m.files" variant="danger">missing</BBadge>
                    </td>
                    <td>{{ formatBytes(m.bytes) }}</td>
                    <td>{{ m.verifiedAt ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="col-lg-6">
              <div class="fw-medium mb-1">ML worker self-check</div>
              <p v-if="!ml.worker.reported" class="text-body-secondary kd-worker-unreported">
                Not reported yet: the worker reports when it starts.
              </p>
              <dl v-else class="kd-dl mb-2 kd-worker-report">
                <dt>Reported</dt><dd>{{ fromNow(ml.worker.reportedAt) }} <BBadge v-if="ml.worker.stale" variant="warning">old</BBadge> · {{ ml.worker.platform ?? '' }}</dd>
                <dt>Packages</dt><dd><BBadge :variant="yesNo(ml.worker.ml?.versionsOk).variant">{{ yesNo(ml.worker.ml?.versionsOk).text }}</BBadge></dd>
                <dt>Silero VAD</dt><dd><BBadge :variant="yesNo(ml.worker.ml?.sileroVadOk).variant">{{ yesNo(ml.worker.ml?.sileroVadOk).text }}</BBadge></dd>
                <dt>Offline</dt><dd>HF_HUB_OFFLINE={{ ml.worker.offline?.HF_HUB_OFFLINE ?? '?' }} · telemetry off: {{ ml.worker.offline?.HF_HUB_DISABLE_TELEMETRY === '1' && ml.worker.offline?.PYANNOTE_METRICS_ENABLED === '0' ? 'yes' : 'no' }}</dd>
                <dt>Internet blocked</dt><dd><BBadge :variant="yesNo(ml.worker.network?.internetBlocked).variant">{{ yesNo(ml.worker.network?.internetBlocked).text }}</BBadge></dd>
                <dt>Isolated + connected</dt><dd><BBadge :variant="yesNo(ml.worker.network?.isolatedAndConnected).variant">{{ yesNo(ml.worker.network?.isolatedAndConnected).text }}</BBadge>
                  <span class="text-body-secondary"> Redis {{ ml.worker.network?.redis ?? '?' }} · API {{ ml.worker.network?.spring ?? '?' }}</span></dd>
              </dl>
              <div class="fw-medium mb-1">Keep-awake</div>
              <dl class="kd-dl mb-0 kd-keep-awake">
                <dt>Holding</dt><dd><BBadge :variant="ml.keepAwake.holding ? 'primary' : 'secondary'">{{ ml.keepAwake.holding ? 'PC kept awake' : 'Not holding' }}</BBadge>
                  <span v-if="ml.keepAwake.enabled === false" class="text-body-secondary"> (disabled)</span></dd>
                <dt>Reason</dt><dd>{{ ml.keepAwake.reason ?? '—' }}</dd>
              </dl>
            </div>
          </div>
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
