<script setup lang="ts">
import { useForm } from 'vee-validate'
import type { JobBrief, ProjectDetail, ProjectSummary, StorageInfo } from '~/types/api'

const route = useRoute()
const id = computed(() => String(route.params.id))
const { $live } = useNuxtApp()

const detail = ref<ProjectDetail | null>(null)
const loadError = ref<string | null>(null)
const storage = ref<StorageInfo | null>(null)
const selectedJobId = ref<string | null>(null)
const extractError = ref<string | null>(null)

const probe = computed(() => detail.value?.original?.metadata?.probe ?? null)
const validation = computed(() => detail.value?.original?.metadata?.validation ?? null)
const activeExtraction = computed(() => detail.value?.jobs.find((j) => j.type === 'EXTRACT_AUDIO'
  && ['QUEUED', 'RUNNING', 'PAUSING', 'PAUSED', 'CANCELLING'].includes(j.status)))
const canExtract = computed(() => !!detail.value && !detail.value.summary.archived && !!probe.value
  && validation.value?.valid === true && !activeExtraction.value)
const activeTranscription = computed(() => detail.value?.jobs.some((j) => j.type === 'TRANSCRIBE'
  && ['QUEUED', 'RUNNING', 'PAUSING', 'PAUSED', 'CANCELLING'].includes(j.status)) ?? false)
const transcriptionPanel = ref<{ load: () => Promise<void> } | null>(null)

async function transcriptionStarted(jobId: string) {
  selectedJobId.value = jobId
  await load()
}

async function load() {
  try {
    detail.value = await api<ProjectDetail>(`/projects/${id.value}`)
    loadError.value = null
    if (!selectedJobId.value && detail.value.jobs.length) selectedJobId.value = detail.value.jobs[0]!.id
    resetForm({ values: {
      audioStreamIndex: detail.value.sourceAudioStreamIndex ?? probe.value?.audioStreams[0]?.index ?? 0,
      sourceLanguage: detail.value.summary.sourceLanguage ?? 'auto',
    } })
  } catch (e) {
    loadError.value = (e as Error).message
  }
}

let reloadTimer: ReturnType<typeof setTimeout> | null = null
let unsubscribe: (() => void) | null = null
onMounted(async () => {
  await load()
  storage.value = await api<StorageInfo>('/system/storage').catch(() => null)
  unsubscribe = $live.subscribe('/topic/projects', (body) => {
    if ((body as ProjectSummary).id !== id.value || reloadTimer) return
    reloadTimer = setTimeout(() => { reloadTimer = null; load(); transcriptionPanel.value?.load() }, 500)
  })
})
onBeforeUnmount(() => {
  unsubscribe?.()
  if (reloadTimer) clearTimeout(reloadTimer)
})

const { handleSubmit, errors, defineField, resetForm, isSubmitting } = useForm({
  validationSchema: toTypedSchema(extractionSchema),
  initialValues: { audioStreamIndex: 0, sourceLanguage: 'auto' },
})
const [audioStreamIndex] = defineField('audioStreamIndex')
const [sourceLanguage, languageAttrs] = defineField('sourceLanguage')

const startExtraction = handleSubmit(async (form) => {
  extractError.value = null
  try {
    const job = await api<JobBrief>(`/projects/${id.value}/extract-audio`, { method: 'POST', body: form })
    selectedJobId.value = job.id
    await load()
  } catch (e) {
    extractError.value = (e as Error).message
  }
})

async function setArchived(archived: boolean) {
  try {
    detail.value = await api<ProjectDetail>(`/projects/${id.value}/${archived ? 'archive' : 'unarchive'}`, { method: 'POST' })
  } catch (e) {
    loadError.value = (e as Error).message
  }
}

function streamLabel(s: { index: number; codec: string | null; channels: number | null; sampleRate: number | null; language: string | null; title: string | null }) {
  return [`Track ${s.index}`, s.language?.toUpperCase(), s.title, s.codec?.toUpperCase(),
    s.channels ? `${s.channels} ch` : null, s.sampleRate ? `${s.sampleRate / 1000} kHz` : null].filter(Boolean).join(' · ')
}
</script>

<template>
  <div>
    <BAlert :model-value="!!loadError" variant="danger">{{ loadError }}</BAlert>
    <template v-if="detail">
      <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
        <h1 class="h4 mb-0">{{ detail.summary.name }}</h1>
        <BBadge variant="secondary">{{ humanize(detail.summary.status) }}</BBadge>
        <BBadge v-if="detail.summary.archived" variant="light" class="text-dark">archived</BBadge>
        <div class="ms-auto">
          <BButton v-if="!detail.summary.archived" size="sm" variant="outline-secondary" @click="setArchived(true)">Archive</BButton>
          <BButton v-else size="sm" variant="outline-secondary" @click="setArchived(false)">Restore</BButton>
        </div>
      </div>

      <div class="row g-3">
        <!-- Movie -->
        <div class="col-xl-5">
          <BCard class="h-100">
            <h2 class="h6"><Icon name="tabler:movie" /> Movie</h2>
            <template v-if="!detail.original">
              <p class="small text-body-secondary">This project has no movie yet.</p>
              <MovieUpload v-if="storage" :project-id="detail.summary.id" :storage="storage" @uploaded="load" />
            </template>
            <template v-else>
              <dl class="kd-dl small">
                <dt>File</dt><dd>{{ detail.original.filename }}</dd>
                <dt>Size</dt><dd>{{ formatBytes(detail.original.sizeBytes) }}</dd>
                <dt>SHA-256</dt><dd class="kd-mono" :title="detail.original.sha256 ?? ''">{{ detail.original.sha256?.slice(0, 16) }}…</dd>
                <template v-if="probe">
                  <dt>Format</dt><dd>{{ probe.formatLongName ?? probe.formatName }}</dd>
                  <dt>Duration</dt><dd>{{ formatDuration(probe.durationMs) }}</dd>
                  <dt>Video</dt>
                  <dd>
                    <span v-for="v in probe.videoStreams" :key="v.index">{{ v.codec?.toUpperCase() }} {{ v.width }}×{{ v.height }}<template v-if="v.fps"> · {{ v.fps }} fps</template></span>
                  </dd>
                  <dt>Audio</dt><dd>{{ probe.audioStreams.length }} track(s)</dd>
                  <dt>Subtitles</dt><dd>{{ probe.subtitleStreams }}</dd>
                </template>
                <template v-else><dt>Inspection</dt><dd class="text-body-secondary">Pending…</dd></template>
              </dl>
              <BAlert v-if="validation && !validation.valid" :model-value="true" variant="danger" class="small mb-0">
                <strong>This movie cannot be dubbed:</strong>
                <ul class="mb-0"><li v-for="p in validation.problems" :key="p">{{ p }}</li></ul>
              </BAlert>
            </template>
          </BCard>
        </div>

        <!-- Audio extraction -->
        <div class="col-xl-7">
          <BCard class="h-100">
            <h2 class="h6"><Icon name="tabler:wave-sine" /> Source audio</h2>
            <p v-if="!probe" class="small text-body-secondary mb-0">Available after the movie has been inspected.</p>
            <form v-else novalidate @submit.prevent="startExtraction">
              <div class="mb-2 small fw-medium">Audio track to dub</div>
              <div v-for="s in probe.audioStreams" :key="s.index" class="form-check small">
                <input :id="`stream-${s.index}`" v-model="audioStreamIndex" class="form-check-input" type="radio" :value="s.index"
                       :disabled="!canExtract">
                <label class="form-check-label" :for="`stream-${s.index}`">
                  {{ streamLabel(s) }} <BBadge v-if="s.default" variant="light" class="text-dark">default</BBadge>
                </label>
              </div>
              <div v-if="errors.audioStreamIndex" class="text-danger small">{{ errors.audioStreamIndex }}</div>
              <div class="row g-2 align-items-end mt-1">
                <div class="col-sm-6">
                  <label class="form-label small" for="src-lang">Source language</label>
                  <select id="src-lang" v-model="sourceLanguage" v-bind="languageAttrs" class="form-select form-select-sm" :disabled="!canExtract">
                    <option v-for="l in SOURCE_LANGUAGES" :key="l.value" :value="l.value">{{ l.label }}</option>
                  </select>
                </div>
                <div class="col-sm-6">
                  <BButton type="submit" size="sm" variant="primary" :disabled="!canExtract || isSubmitting">
                    <Icon name="tabler:player-play" /> {{ detail.extractedAudio ? 'Extract again' : 'Extract audio' }}
                  </BButton>
                </div>
              </div>
              <p class="form-text mb-2">Extracts 16 kHz mono audio in 10-minute segments on the Linux worker; each segment can be retried on its own.</p>
              <BAlert :model-value="!!extractError" variant="danger" class="small py-2">{{ extractError }}</BAlert>
            </form>
            <div v-if="detail.extractedAudio" class="border-top pt-2 mt-2">
              <div class="small mb-1">
                <strong>Extracted audio</strong> · {{ formatDuration(detail.extractedAudio.metadata.durationMs as number) }}
                · {{ formatBytes(detail.extractedAudio.sizeBytes) }} · 16 kHz mono
              </div>
              <audio controls preload="none" class="w-100" :src="`/api/assets/${detail.extractedAudio.id}/content`" />
            </div>
          </BCard>
        </div>

        <!-- Dialogue (M3b) -->
        <div class="col-12">
          <TranscriptionPanel ref="transcriptionPanel" :project-id="detail.summary.id"
                              :ready="!!detail.extractedAudio && !detail.summary.archived"
                              :busy="activeTranscription || detail.summary.archived"
                              :default-language="detail.summary.sourceLanguage ?? 'auto'"
                              @started="transcriptionStarted" />
        </div>

        <!-- Jobs -->
        <div class="col-12">
          <BCard>
            <h2 class="h6"><Icon name="tabler:list-check" /> Jobs</h2>
            <p v-if="!detail.jobs.length" class="small text-body-secondary mb-0">No jobs yet.</p>
            <div v-else class="row g-3">
              <div class="col-lg-4">
                <div class="list-group small">
                  <button v-for="j in detail.jobs" :key="j.id" type="button" class="list-group-item list-group-item-action"
                          :class="{ active: j.id === selectedJobId }" @click="selectedJobId = j.id">
                    <div class="d-flex justify-content-between">
                      <span>{{ humanize(j.type) }}</span>
                      <JobStatusBadge :status="j.status" />
                    </div>
                    <div class="opacity-75">{{ fromNow(j.createdAt) }} · {{ Math.round(j.progress) }}%</div>
                  </button>
                </div>
              </div>
              <div class="col-lg-8">
                <JobPanel v-if="selectedJobId" :job-id="selectedJobId" />
              </div>
            </div>
          </BCard>
        </div>
      </div>
    </template>
    <div v-else-if="!loadError" class="text-body-secondary"><BSpinner small /> Loading project…</div>
  </div>
</template>
