<script setup lang="ts">
import { useForm } from 'vee-validate'
import type { DialoguePage, JobBrief, RevisionView, SpeakerView, TranscriptionMode } from '~/types/api'

const props = defineProps<{ projectId: string; ready: boolean; busy: boolean; defaultLanguage: string }>()
const emit = defineEmits<{ started: [jobId: string] }>()

const revisions = ref<RevisionView[]>([])
const speakers = ref<SpeakerView[]>([])
const preview = ref<DialoguePage | null>(null)
const error = ref<string | null>(null)
const confirmMessage = ref<string | null>(null)
const activating = ref<RevisionView | null>(null)
const activateMessage = ref<string | null>(null)

const active = computed(() => revisions.value.find((r) => r.status === 'ACTIVE') ?? null)
const speakerCode = computed(() => Object.fromEntries(speakers.value.map((s) => [s.id, s.code])))

const { handleSubmit, errors, defineField, isSubmitting } = useForm({
  validationSchema: toTypedSchema(transcriptionSchema),
  initialValues: { mode: 'QUALITY' as TranscriptionMode, language: props.defaultLanguage || 'auto' },
})
const [mode] = defineField('mode')
const [language, languageAttrs] = defineField('language')

async function load() {
  revisions.value = await api<RevisionView[]>(`/projects/${props.projectId}/dialogue-revisions`).catch(() => [])
  if (active.value) {
    speakers.value = await api<SpeakerView[]>(`/projects/${props.projectId}/speakers`).catch(() => [])
    preview.value = await api<DialoguePage>(`/projects/${props.projectId}/dialogue`, { query: { limit: 12 } }).catch(() => null)
  }
}
onMounted(load)
defineExpose({ load })

async function send(form: { mode: TranscriptionMode; language: string }, confirmReprocess: boolean) {
  error.value = null
  try {
    const job = await api<JobBrief>(`/projects/${props.projectId}/transcribe`, {
      method: 'POST', body: { ...form, confirmReprocess },
    })
    confirmMessage.value = null
    emit('started', job.id)
  } catch (e) {
    const err = toApiError(e)
    if (needsReprocessConfirmation(err.code)) confirmMessage.value = err.message
    else error.value = err.message
  }
}
const start = handleSubmit((form) => send(form, false))
const confirmReprocess = handleSubmit((form) => send(form, true))

async function activate(confirm: boolean) {
  if (!activating.value) return
  activateMessage.value = null
  try {
    await api<RevisionView>(`/projects/${props.projectId}/dialogue-revisions/${activating.value.id}/activate`,
      { method: 'POST', body: { confirm } })
    activating.value = null
    await load()
  } catch (e) {
    activateMessage.value = toApiError(e).message
  }
}

function count(r: RevisionView, key: string) {
  return r.summary?.[key] ?? '–'
}
</script>

<template>
  <BCard class="h-100">
    <h2 class="h6"><Icon name="tabler:message-2" /> Dialogue (transcription + speakers)</h2>
    <p v-if="!ready" class="small text-body-secondary mb-0">Available after the audio has been extracted.</p>
    <form v-else novalidate @submit.prevent="start">
      <div class="small fw-medium mb-1">Speaker identification</div>
      <div class="form-check small">
        <input id="mode-quality" v-model="mode" class="form-check-input" type="radio" value="QUALITY" :disabled="busy">
        <label class="form-check-label" for="mode-quality">Quality (default) — best speaker separation; about 1.2× the movie length on this PC</label>
      </div>
      <div class="form-check small">
        <input id="mode-fast" v-model="mode" class="form-check-input" type="radio" value="FAST" :disabled="busy">
        <label class="form-check-label" for="mode-fast">Fast — about 2.5× faster speaker identification</label>
      </div>
      <BAlert :model-value="mode === 'FAST'" variant="warning" class="small py-2 my-2">
        Fast mode separates speakers less well: different characters with similar voices are more often merged into one
        speaker (in tests it merged two male characters). You can split them later in speaker review.
      </BAlert>
      <div v-if="errors.mode" class="text-danger small">{{ errors.mode }}</div>
      <div class="row g-2 align-items-end mt-1">
        <div class="col-sm-6">
          <label class="form-label small" for="tr-lang">Spoken language</label>
          <select id="tr-lang" v-model="language" v-bind="languageAttrs" class="form-select form-select-sm" :disabled="busy">
            <option v-for="l in SOURCE_LANGUAGES" :key="l.value" :value="l.value">{{ l.label }}</option>
          </select>
        </div>
        <div class="col-sm-6">
          <BButton type="submit" size="sm" variant="primary" :disabled="busy || isSubmitting">
            <Icon name="tabler:player-play" /> {{ active ? 'Process again' : 'Transcribe & identify speakers' }}
          </BButton>
        </div>
      </div>
      <p class="form-text mb-2">Runs on this PC only (no cloud). The PC is kept awake while it processes; the screen can still turn off.</p>
      <BAlert :model-value="!!confirmMessage" variant="warning" class="small py-2">
        <div>{{ confirmMessage }}</div>
        <div class="mt-2 d-flex gap-2">
          <BButton size="sm" variant="warning" :disabled="isSubmitting" @click="confirmReprocess">Back up and process again</BButton>
          <BButton size="sm" variant="outline-secondary" @click="confirmMessage = null">Keep the current dialogue</BButton>
        </div>
      </BAlert>
      <BAlert :model-value="!!error" variant="danger" class="small py-2">{{ error }}</BAlert>
    </form>

    <div v-if="revisions.length" class="border-top pt-2 mt-2">
      <div class="small fw-medium mb-1">Revisions</div>
      <table class="table table-sm small mb-2">
        <thead><tr><th>#</th><th>Status</th><th>Mode</th><th>Lines</th><th>Speakers</th><th>Created</th><th /></tr></thead>
        <tbody>
          <tr v-for="r in revisions" :key="r.id">
            <td>{{ r.revisionNo }}</td>
            <td><BBadge :variant="r.status === 'ACTIVE' ? 'success' : r.status === 'READY' ? 'info' : 'secondary'">{{ humanize(r.status) }}</BBadge></td>
            <td>{{ r.mode ? humanize(r.mode) : '–' }}</td>
            <td>{{ count(r, 'lines') }}</td>
            <td>{{ count(r, 'speakers') }}</td>
            <td>{{ fromNow(r.createdAt) }}</td>
            <td class="text-end">
              <BButton v-if="r.status === 'READY'" size="sm" variant="outline-primary" @click="activating = r">Use this revision</BButton>
            </td>
          </tr>
        </tbody>
      </table>
      <BAlert :model-value="!!activating" variant="info" class="small py-2">
        Switch to revision {{ activating?.revisionNo }}? The revision in use<template v-if="active"> ({{ active.revisionNo }})</template>
        is backed up first and kept read-only; nothing is deleted.
        <div class="mt-2 d-flex gap-2">
          <BButton size="sm" variant="primary" @click="activate(true)">Switch</BButton>
          <BButton size="sm" variant="outline-secondary" @click="activating = null">Cancel</BButton>
        </div>
        <div v-if="activateMessage" class="text-danger mt-1">{{ activateMessage }}</div>
      </BAlert>
    </div>

    <div v-if="preview && active" class="border-top pt-2">
      <div class="small fw-medium mb-1">
        In use: revision {{ active.revisionNo }} · {{ preview.total }} lines · {{ speakers.length }} speakers
        <span class="text-body-secondary">(first lines; editing comes in the review step)</span>
      </div>
      <div class="kd-mono small">
        <div v-for="l in preview.lines" :key="l.id">
          {{ formatDuration(l.startMs) }} · {{ (l.speakerId && speakerCode[l.speakerId]) || '—' }}: {{ l.sourceText }}
        </div>
      </div>
    </div>
  </BCard>
</template>
