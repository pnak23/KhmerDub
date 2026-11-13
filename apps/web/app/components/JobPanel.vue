<script setup lang="ts">
import type { JobView } from '~/types/api'
import type { JobAction } from '~/stores/jobs'

const props = defineProps<{ jobId: string }>()
const emit = defineEmits<{ changed: [JobView] }>()

const jobs = useJobsStore()
const { $live } = useNuxtApp()
const actionError = ref<string | null>(null)
const busy = ref(false)

const job = computed(() => jobs.jobs[props.jobId])
const events = computed(() => jobs.events[props.jobId] ?? [])
const live = computed(() => ['RUNNING', 'PAUSING', 'CANCELLING'].includes(job.value?.status ?? ''))
const attemptsUsed = computed(() => job.value?.chunks.reduce((n, c) => n + c.attempts.length, 0) ?? 0)

let unsubscribe: (() => void) | null = null
let eventsTimer: ReturnType<typeof setTimeout> | null = null

function refreshEventsSoon() {
  if (eventsTimer) return
  eventsTimer = setTimeout(async () => {
    eventsTimer = null
    await jobs.fetchEvents(props.jobId).catch(() => {})
  }, 1500)
}

async function start(id: string) {
  unsubscribe?.()
  await Promise.all([jobs.fetch(id), jobs.fetchEvents(id)]).catch((e) => (actionError.value = (e as Error).message))
  unsubscribe = $live.subscribe(`/topic/jobs/${id}`, (body) => {
    if (jobs.apply(body as JobView)) {
      emit('changed', body as JobView)
      refreshEventsSoon()
    }
  })
}

watch(() => props.jobId, (id) => start(id), { immediate: true })
onBeforeUnmount(() => {
  unsubscribe?.()
  if (eventsTimer) clearTimeout(eventsTimer)
})

async function act(action: JobAction) {
  if (action === 'cancel' && !confirm('Cancel this job? Running work is stopped; finished chunks are kept.')) return
  busy.value = true
  actionError.value = null
  try {
    await jobs.act(props.jobId, action)
    await jobs.fetchEvents(props.jobId)
  } catch (e) {
    actionError.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="job">
    <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
      <strong>{{ humanize(job.type) }}</strong>
      <JobStatusBadge :status="job.status" />
      <span v-if="job.currentStage" class="small text-body-secondary">Stage: {{ humanize(job.currentStage) }}</span>
      <span class="small text-body-secondary">· {{ attemptsUsed }} attempt(s) · started {{ fromNow(job.startedAt) }}</span>
      <div class="ms-auto d-flex gap-1">
        <BButton v-if="job.status === 'RUNNING' || job.status === 'QUEUED'" size="sm" variant="outline-warning"
                 :disabled="busy" @click="act('pause')"><Icon name="tabler:player-pause" /> Pause</BButton>
        <BButton v-if="job.status === 'PAUSED' || job.status === 'PAUSING'" size="sm" variant="outline-primary"
                 :disabled="busy" @click="act('resume')"><Icon name="tabler:player-play" /> Resume</BButton>
        <BButton v-if="job.status === 'FAILED'" size="sm" variant="outline-primary" :disabled="busy"
                 @click="act('retry')"><Icon name="tabler:refresh" /> Retry</BButton>
        <BButton v-if="['QUEUED', 'RUNNING', 'PAUSING', 'PAUSED'].includes(job.status)" size="sm"
                 variant="outline-danger" :disabled="busy" @click="act('cancel')"><Icon name="tabler:x" /> Cancel</BButton>
      </div>
    </div>

    <ProgressBar :value="job.progress" :variant="jobVariant(job.status)" :animated="live" class="mb-2" />

    <BAlert :model-value="!!job.errorMessage" variant="danger" class="small py-2">{{ job.errorMessage }}</BAlert>
    <BAlert :model-value="!!actionError" variant="warning" class="small py-2">{{ actionError }}</BAlert>
    <p v-if="job.status === 'PAUSING'" class="small text-body-secondary">Pausing: running work finishes first, then the job pauses.</p>
    <p v-if="job.status === 'CANCELLING'" class="small text-body-secondary">Cancelling: running work has been told to stop.</p>

    <ChunkGrid v-if="job.chunks.length" :chunks="job.chunks" class="mb-3" />

    <details>
      <summary class="small text-body-secondary">Event history ({{ events.length }})</summary>
      <table class="table table-sm small mt-2 mb-0">
        <tbody>
          <tr v-for="e in events" :key="e.seq">
            <td class="text-nowrap text-body-secondary">{{ new Date(e.createdAt).toLocaleTimeString() }}</td>
            <td><BBadge :variant="e.level === 'ERROR' ? 'danger' : e.level === 'WARN' ? 'warning' : 'light'"
                        :class="{ 'text-dark': e.level !== 'ERROR' }">{{ e.type }}</BBadge></td>
            <td class="text-break">{{ e.message }}</td>
          </tr>
        </tbody>
      </table>
    </details>
  </div>
  <div v-else class="text-body-secondary small"><BSpinner small /> Loading job…</div>
</template>
