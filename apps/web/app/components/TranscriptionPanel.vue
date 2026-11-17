<script setup lang="ts">
import { useForm } from 'vee-validate'
import type { DialoguePage, EditSummary, JobBrief, RevisionView, SpeakerView, TranscriptionMode } from '~/types/api'
import { ApiError } from '~/utils/api'

const props = defineProps<{ projectId: string; ready: boolean; busy: boolean; defaultLanguage: string }>()
const emit = defineEmits<{ started: [jobId: string]; changed: [] }>()

const revisions = ref<RevisionView[]>([])
const speakers = ref<SpeakerView[]>([])
const preview = ref<DialoguePage | null>(null)
const error = ref<string | null>(null)
const confirmMessage = ref<string | null>(null)

// switching the revision in use
const activating = ref<RevisionView | null>(null)
const switching = ref(false)
const switchError = ref<ApiError | null>(null)
const switchedTo = ref<number | null>(null)
/** What the user has reviewed in the revision in use: it stays with that revision (not copied to the new one). */
const keptEdits = ref<EditSummary | null>(null)
/** The review work a revision used before still has: it comes back with it, exactly as it was left. */
const returningEdits = ref<EditSummary | null>(null)
/** The revision in use when the confirmation was opened: the switch is not sent if that changed meanwhile. */
let openedWithActive: string | null = null

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
    const revisionId = active.value.id // speakers and lines of the same revision
    speakers.value = await api<SpeakerView[]>(`/projects/${props.projectId}/speakers`, { query: { revisionId } }).catch(() => [])
    preview.value = await api<DialoguePage>(`/projects/${props.projectId}/dialogue`, { query: { revisionId, limit: 12 } })
      .catch(() => null)
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

function editSummary(revisionId: string) {
  return api<EditSummary>(`/projects/${props.projectId}/dialogue-revisions/${revisionId}/edit-summary`).catch(() => null)
}

/** Open the confirmation: what stays with the revision in use, and (for one used before) what comes back with it. */
async function askActivate(r: RevisionView) {
  activating.value = r
  switchError.value = null
  switchedTo.value = null
  keptEdits.value = null
  returningEdits.value = null
  openedWithActive = active.value?.id ?? null
  const [kept, returning] = await Promise.all([
    active.value ? editSummary(active.value.id) : Promise.resolve(null),
    r.status === 'SUPERSEDED' ? editSummary(r.id) : Promise.resolve(null),
  ])
  if (activating.value?.id !== r.id) return // closed, or another revision chosen meanwhile
  keptEdits.value = kept
  returningEdits.value = returning
}

function cancelActivate() {
  if (switching.value) return
  activating.value = null
  keptEdits.value = null
  returningEdits.value = null
}

/**
 * Confirmed: the server backs up the revision in use (verified), then switches, all or nothing. A refusal leaves the
 * revision in use unchanged; the confirmation stays open with the reason so nothing the user chose is lost.
 */
async function activate() {
  const target = activating.value
  if (!target || switching.value) return
  switching.value = true
  switchError.value = null
  try {
    await refresh() // what is in use right now: another tab may have switched since the confirmation was opened
    const now = revisions.value.find((r) => r.id === target.id)
    if ((active.value?.id ?? null) !== openedWithActive || !now || !canActivate(now)) {
      if (now && canActivate(now)) await askActivate(now)
      else activating.value = null
      switchError.value = new ApiError({ status: 409, code: REVISION_CHANGED, detail: 'The revisions changed meanwhile.' })
      return
    }
    await api<RevisionView>(`/projects/${props.projectId}/dialogue-revisions/${target.id}/activate`,
      { method: 'POST', body: { confirm: true } })
    await switched(target)
  } catch (e) {
    const err = toApiError(e)
    await refresh()
    if (active.value?.id === target.id) await switched(target) // it did switch; only the answer got lost on the way
    else switchError.value = err
  } finally {
    switching.value = false
  }
}

/** Reload the list and tell the page when the revision in use changed (e.g. it was switched in another tab). */
async function refresh() {
  const before = active.value?.id ?? null
  await load()
  if ((active.value?.id ?? null) !== before) emit('changed')
}

// Coming back to this tab: show what is in use now (another tab or window may have switched it meanwhile).
function onFocus() {
  if (!switching.value) refresh()
}
function onVisible() {
  if (document.visibilityState === 'visible') onFocus()
}
onMounted(() => {
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisible)
})

async function switched(target: RevisionView) {
  activating.value = null
  keptEdits.value = null
  returningEdits.value = null
  switchedTo.value = target.revisionNo
  await load()
  emit('changed')
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
      <div class="small fw-medium mb-1">
        Revisions
        <span v-if="active" class="ms-1 kd-active-revision">· revision {{ active.revisionNo }} is in use (the one you edit)</span>
      </div>
      <table class="table table-sm small mb-1 kd-revisions">
        <thead><tr><th>#</th><th>Status</th><th>Mode</th><th>Lines</th><th>Speakers</th><th>Created</th><th /></tr></thead>
        <tbody>
          <tr v-for="r in revisions" :key="r.id" class="kd-revision" :class="{ 'table-success fw-medium': r.status === 'ACTIVE' }"
              :data-revision="r.revisionNo" :data-status="r.status" :aria-current="r.status === 'ACTIVE' ? 'true' : undefined">
            <td>{{ r.revisionNo }}</td>
            <td>
              <BBadge :variant="REVISION_STATUS[r.status].variant" class="kd-revision-status">
                <Icon v-if="r.status === 'ACTIVE'" name="tabler:check" /> {{ REVISION_STATUS[r.status].label }}
              </BBadge>
              <div v-if="r.status === 'SUPERSEDED'" class="text-body-secondary">read-only · until {{ fromNow(r.supersededAt) }}</div>
            </td>
            <td>{{ r.mode ? humanize(r.mode) : '–' }}</td>
            <td>{{ count(r, 'lines') }}</td>
            <td>{{ count(r, 'speakers') }}</td>
            <td>{{ fromNow(r.createdAt) }}</td>
            <td class="text-end">
              <BButton v-if="canActivate(r)" size="sm" variant="outline-primary" class="kd-use-revision" :disabled="switching"
                       @click="askActivate(r)">Use this revision</BButton>
              <span v-else-if="r.status === 'ACTIVE'" class="text-success-emphasis kd-editing-this">Editable</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="form-text mt-0 mb-2">
        Only the revision in use can be edited. A revision used before stays read-only with its own review work and can be
        used again; switching never copies or deletes review work.
      </p>
      <BAlert :model-value="!!switchedTo" variant="success" class="small py-2 kd-switched" dismissible
              @update:model-value="switchedTo = null">
        Revision {{ switchedTo }} is now in use and can be edited. The revision used before was backed up and kept read-only.
      </BAlert>
      <BAlert :model-value="!!switchError" variant="danger" class="small py-2 kd-switch-error" :data-code="switchError?.code">
        <strong>Not switched.</strong> {{ switchFailure(switchError?.code) }}
        <div v-if="switchError?.code !== REVISION_CHANGED" class="kd-switch-reason">{{ switchError?.message }}</div>
        <div class="kd-still-in-use">
          {{ active ? `Revision ${active.revisionNo} is in use` : 'No revision is in use' }}; no review work was changed.
        </div>
      </BAlert>
      <BAlert :model-value="!!activating" variant="info" class="small py-2 kd-switch-confirm">
        <strong>Switch to revision {{ activating?.revisionNo }}?</strong>
        <template v-if="active"> Revision {{ active.revisionNo }} (in use now) is backed up first and then kept read-only:
          nothing is deleted, and you can switch back to it later.</template>
        <div v-if="activating?.status === 'SUPERSEDED'" class="mt-2 kd-returning">
          <strong>Revision {{ activating.revisionNo }} was in use before.</strong> It comes back exactly as you left it, with
          its own Chinese corrections, Khmer text, speaker names, speaker assignments and review history (Undo continues
          from there).
          <template v-if="hasWork(returningEdits)"> It has {{ describeWork(returningEdits) }}.</template>
        </div>
        <div v-if="hasWork(keptEdits)" class="mt-2 kd-kept-edits">
          <strong>Your review work stays with revision {{ keptEdits.revisionNo }} and is not copied:</strong>
          {{ describeWork(keptEdits) }}.
        </div>
        <div class="mt-2 d-flex gap-2">
          <BButton size="sm" variant="primary" class="kd-confirm-switch" :disabled="switching" @click="activate">
            <BSpinner v-if="switching" small /> Back up and switch
          </BButton>
          <BButton size="sm" variant="outline-secondary" class="kd-cancel-switch" :disabled="switching" @click="cancelActivate">Cancel</BButton>
        </div>
      </BAlert>
    </div>

    <div v-if="preview && active" class="border-top pt-2">
      <div class="small fw-medium mb-1">
        In use: revision {{ active.revisionNo }} · {{ preview.total }} lines · {{ speakers.length }} speakers
        <NuxtLink :to="`/projects/${projectId}/dialogue`" class="ms-1">Open the dialogue editor</NuxtLink>
      </div>
      <div class="kd-mono small">
        <div v-for="l in preview.lines" :key="l.id">
          {{ formatDuration(l.startMs) }} · {{ (l.speakerId && speakerCode[l.speakerId]) || '—' }}: {{ l.sourceText }}
        </div>
      </div>
    </div>
  </BCard>
</template>
