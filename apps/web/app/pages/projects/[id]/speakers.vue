<script setup lang="ts">
import type { DialoguePage, LineView, OperationResult, ProjectDetail, RevisionView, SpeakerView, VoiceProfile } from '~/types/api'
import type { ApiError } from '~/utils/api'

interface Privacy {
  fingerprintFiles: number
  voiceSampleFiles: number
  fingerprintsForgottenAt: string | null
  voiceSamplesDeletedAt: string | null
}

const route = useRoute()
const projectId = computed(() => String(route.params.id))

const project = ref<ProjectDetail | null>(null)
const active = ref<RevisionView | null>(null)
const speakers = ref<SpeakerView[]>([])
const voices = ref<VoiceProfile[]>([])
const privacy = ref<Privacy | null>(null)
const error = ref<ApiError | null>(null)
const loading = ref(true)
const busy = ref(false)
const notice = ref<string | null>(null)
const undoBar = ref<{ refresh: () => Promise<void> } | null>(null)

// selection for merge
const selected = ref<string[]>([])
const mergeTarget = ref<string>('')
// editing one speaker's details
const editingId = ref<string | null>(null)
const form = reactive({ characterName: '', description: '', suggestedCategory: '' as string })
const formErrors = ref<Record<string, string>>({})
// moving lines of one speaker
const splitFor = ref<SpeakerView | null>(null)
const splitLines = ref<LineView[]>([])
const splitChosen = ref<string[]>([])
const splitName = ref('')
const reassignTo = ref('')
const danger = ref<'fingerprints' | 'samples' | null>(null)

/** Only the revision in use can be edited: if it is switched elsewhere, this page turns read-only (see the guard). */
const guard = useRevisionGuard(projectId)
const readOnly = computed(() => guard.switched.value)

const visible = computed(() => speakers.value.filter((s) => !s.mergedIntoId))
const merged = computed(() => speakers.value.filter((s) => s.mergedIntoId))
const byId = computed(() => Object.fromEntries(speakers.value.map((s) => [s.id, s])))
const activeVoices = computed(() => voices.value.filter((v) => v.active))

function label(s: SpeakerView | undefined): string {
  if (!s) return '—'
  return s.characterName ? `${s.code} · ${s.characterName}` : s.code
}

/** Speaker details typed but not saved, for the read-only banner (so they can be copied). */
const unsaved = computed(() => {
  const s = editingId.value ? byId.value[editingId.value] : undefined
  if (!s) return []
  const parts = [
    form.characterName !== (s.characterName ?? '') ? `name "${form.characterName}"` : '',
    form.description !== (s.description ?? '') ? `description "${form.description}"` : '',
    form.suggestedCategory !== (s.suggestedCategory ?? '') ? `category ${humanize(form.suggestedCategory) || 'not set'}` : '',
  ].filter(Boolean)
  return parts.length ? [`${s.code}: ${parts.join(' · ')}`] : []
})

async function load() {
  if (readOnly.value) { // keep showing the old revision (and the typed details) until the user opens the one in use
    privacy.value = await api<Privacy>(`/projects/${projectId.value}/privacy`).catch(() => privacy.value) // project-wide
    return
  }
  loading.value = true
  try {
    project.value = await api<ProjectDetail>(`/projects/${projectId.value}`)
    const revisions = await api<RevisionView[]>(`/projects/${projectId.value}/dialogue-revisions`)
    const inUse = revisions.find((r) => r.status === 'ACTIVE') ?? null
    if (inUse && !(await guard.track(inUse.id, revisions))) return // switched under this page: never swap silently
    active.value = inUse
    speakers.value = active.value
      ? await api<SpeakerView[]>(`/projects/${projectId.value}/speakers`, { query: { revisionId: active.value.id } })
      : []
    voices.value = await api<VoiceProfile[]>('/voice-profiles', { query: { includeInactive: true } }).catch(() => [])
    privacy.value = await api<Privacy>(`/projects/${projectId.value}/privacy`).catch(() => null)
    selected.value = selected.value.filter((id) => visible.value.some((s) => s.id === id))
  } catch (e) {
    error.value = toApiError(e)
  } finally {
    loading.value = false
  }
}

/** Run a change; on success reload what it touched, on refusal show why (never retried automatically). */
async function change(run: () => Promise<OperationResult | unknown>, message?: string) {
  busy.value = true
  error.value = null
  notice.value = null
  try {
    await run()
    if (message) notice.value = message
    await load()
    await undoBar.value?.refresh()
    return true
  } catch (e) {
    error.value = toApiError(e)
    if (isReadOnly(error.value)) await guard.check() // most likely switched elsewhere: turn read-only
    return false
  } finally {
    busy.value = false
  }
}

/** The user chose to leave the old revision: unsaved details stay listed by the banner until dismissed. */
async function openRevisionInUse() {
  editingId.value = null
  splitFor.value = null
  selected.value = []
  mergeTarget.value = ''
  error.value = null
  notice.value = null
  guard.accept()
  await load()
  await undoBar.value?.refresh()
}

function patchSpeaker(s: SpeakerView, body: Record<string, unknown>) {
  return api<OperationResult>(`/projects/${projectId.value}/speakers/${s.id}`, { method: 'PATCH', body: { version: s.version, ...body } })
}

function startEdit(s: SpeakerView) {
  editingId.value = s.id
  form.characterName = s.characterName ?? ''
  form.description = s.description ?? ''
  form.suggestedCategory = s.suggestedCategory ?? ''
  formErrors.value = {}
}

async function saveEdit(s: SpeakerView) {
  const parsed = speakerSchema.safeParse(form)
  if (!parsed.success) {
    formErrors.value = Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message]))
    return
  }
  const body: Record<string, unknown> = {}
  if (parsed.data.characterName !== (s.characterName ?? '')) body.characterName = parsed.data.characterName
  if (parsed.data.description !== (s.description ?? '')) body.description = parsed.data.description
  if (parsed.data.suggestedCategory !== (s.suggestedCategory ?? '')) body.suggestedCategory = parsed.data.suggestedCategory
  if (!Object.keys(body).length) {
    editingId.value = null
    return
  }
  if (await change(() => patchSpeaker(s, body), `Saved ${s.code}.`)) editingId.value = null
}

function toggleReviewed(s: SpeakerView) {
  return change(() => patchSpeaker(s, { reviewStatus: s.reviewStatus === 'REVIEWED' ? 'UNREVIEWED' : 'REVIEWED' }))
}

function assignVoice(s: SpeakerView, voiceId: string) {
  return change(() => patchSpeaker(s, { voiceProfileId: voiceId }))
}

function addSpeaker() {
  return change(() => api<OperationResult>(`/projects/${projectId.value}/speakers`, { method: 'POST', body: {} }), 'Speaker added.')
}

async function mergeSelected() {
  const target = byId.value[mergeTarget.value]
  const sources = selected.value.filter((id) => id !== mergeTarget.value).map((id) => byId.value[id]!)
  if (!target || !sources.length) return
  const ok = await change(() => api<OperationResult>(`/projects/${projectId.value}/speakers/merge`, {
    method: 'POST',
    body: { target: { id: target.id, version: target.version }, sources: sources.map((s) => ({ id: s.id, version: s.version })) },
  }), `Merged ${sources.map((s) => s.code).join(', ')} into ${target.code}.`)
  if (ok) {
    selected.value = []
    mergeTarget.value = ''
  }
}

async function openSplit(s: SpeakerView) {
  error.value = null
  splitFor.value = s
  splitChosen.value = []
  splitName.value = ''
  reassignTo.value = ''
  const page = await api<DialoguePage>(`/projects/${projectId.value}/dialogue`, { query: { speakerId: s.id, revisionId: active.value?.id, limit: 1000 } })
    .catch((e) => { error.value = toApiError(e); return null })
  splitLines.value = page?.lines ?? []
}

function chosenRefs() {
  return splitLines.value.filter((l) => splitChosen.value.includes(l.id)).map((l) => ({ id: l.id, version: l.version }))
}

async function splitToNew() {
  const s = splitFor.value
  if (!s || !splitChosen.value.length) return
  if (await change(() => api<OperationResult>(`/projects/${projectId.value}/speakers/${s.id}/split`, {
    method: 'POST', body: { version: s.version, segments: chosenRefs(), characterName: splitName.value || undefined },
  }), `Moved ${splitChosen.value.length} line(s) to a new speaker.`)) splitFor.value = null
}

async function moveToExisting() {
  if (!splitFor.value || !reassignTo.value || !splitChosen.value.length) return
  if (await change(() => api<OperationResult>(`/projects/${projectId.value}/segments/reassign`, {
    method: 'POST', body: { speakerId: reassignTo.value, segments: chosenRefs() },
  }), `Moved ${splitChosen.value.length} line(s) to ${label(byId.value[reassignTo.value])}.`)) splitFor.value = null
}

async function runDanger() {
  const kind = danger.value
  if (!kind) return
  const path = kind === 'fingerprints' ? 'voice-fingerprints/forget' : 'voice-samples/delete'
  if (await change(() => api(`/projects/${projectId.value}/${path}`, { method: 'POST' }),
    kind === 'fingerprints' ? 'Voice fingerprints forgotten (files deleted and verified).' : 'Voice samples deleted.')) danger.value = null
}

async function afterUndo() {
  notice.value = 'Undone.'
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <ReviewNav :project-id="projectId" current="speakers" :title="project?.summary.name" />
    <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
      <h1 class="h4 mb-0">Speakers</h1>
      <BBadge v-if="active && !readOnly" variant="success" class="kd-shown-revision">Revision {{ active.revisionNo }} in use</BBadge>
      <BBadge v-else-if="active" variant="secondary" class="kd-shown-revision"><Icon name="tabler:lock" /> Revision {{ active.revisionNo }} · read-only</BBadge>
      <span v-if="active" class="small text-body-secondary">{{ visible.length }} speakers · Diarization separates voices; it does not know who is who — please review.</span>
      <div class="ms-auto d-flex gap-2">
        <NuxtLink :to="{ path: `/projects/${projectId}/dialogue`, query: { flag: 'mixedSpeakers' } }" class="btn btn-sm btn-outline-secondary">
          <Icon name="tabler:filter" /> Mixed-speaker lines
        </NuxtLink>
        <BButton size="sm" variant="outline-primary" class="kd-add-speaker" :disabled="!active || busy || readOnly" @click="addSpeaker">
          <Icon name="tabler:user-plus" /> Add speaker
        </BButton>
      </div>
    </div>

    <RevisionSwitchedBanner :switched="readOnly" :shown-no="guard.shownNo.value" :in-use="guard.inUse.value" :unsaved="unsaved"
                            @open="openRevisionInUse" />
    <UndoBar v-if="active" ref="undoBar" :project-id="projectId" :busy="busy || readOnly" class="mb-2" @undone="afterUndo" @reload="load" />
    <ReviewError :error="error" @reload="load(); error = null" @dismiss="error = null" />
    <BAlert :model-value="!!notice" variant="success" class="small py-2 kd-notice" dismissible @update:model-value="notice = null">{{ notice }}</BAlert>

    <div v-if="loading && !speakers.length" class="text-body-secondary"><BSpinner small /> Loading…</div>
    <BAlert v-else-if="!active" :model-value="true" variant="info">This project has no dialogue in use yet. Transcribe it on the Overview tab.</BAlert>

    <template v-else>
      <fieldset :disabled="readOnly" class="kd-editor-scope">
      <!-- merge bar -->
      <BCard v-if="selected.length >= 2" class="mb-2 kd-merge-bar" body-class="py-2">
        <div class="d-flex flex-wrap align-items-center gap-2 small">
          <strong>{{ selected.length }} selected.</strong> Merge into
          <select v-model="mergeTarget" class="form-select form-select-sm w-auto kd-merge-target">
            <option value="" disabled>choose the speaker to keep</option>
            <option v-for="id in selected" :key="id" :value="id">{{ label(byId[id]) }}</option>
          </select>
          <BButton size="sm" variant="primary" class="kd-merge" :disabled="!mergeTarget || busy" @click="mergeSelected">Merge</BButton>
          <BButton size="sm" variant="link" @click="selected = []">Clear</BButton>
          <span class="text-body-secondary">All lines move to the kept speaker; the others stay listed as "merged" and can be restored with Undo.</span>
        </div>
      </BCard>

      <div class="row g-3">
        <div v-for="s in visible" :key="s.id" class="col-xxl-4 col-lg-6">
          <BCard class="h-100 kd-speaker" :data-code="s.code" :border-variant="s.reviewStatus === 'REVIEWED' ? 'success' : undefined">
            <div class="d-flex align-items-start gap-2">
              <input :id="`sel-${s.id}`" v-model="selected" class="form-check-input mt-1 kd-select" type="checkbox" :value="s.id"
                     :aria-label="`Select ${s.code}`">
              <div class="flex-grow-1">
                <div class="d-flex flex-wrap align-items-center gap-2">
                  <strong class="kd-code">{{ s.code }}</strong>
                  <span class="kd-name">{{ s.characterName ?? 'Unnamed' }}</span>
                  <BBadge :variant="s.reviewStatus === 'REVIEWED' ? 'success' : 'secondary'">{{ humanize(s.reviewStatus) }}</BBadge>
                  <BBadge v-if="s.origin !== 'DIARIZATION'" variant="light" class="text-dark">{{ humanize(s.origin) }}</BBadge>
                </div>
                <div class="small text-body-secondary">
                  {{ formatDuration(s.speechMs) }} of speech · <span class="kd-count">{{ s.segmentCount }}</span> lines
                  <template v-if="s.suggestedCategory"> · {{ humanize(s.suggestedCategory) }}</template>
                </div>
                <div v-if="s.description" class="small">{{ s.description }}</div>
              </div>
            </div>

            <div class="mt-2">
              <div class="small fw-medium">Voice samples</div>
              <div v-if="!s.sampleAssetIds.length" class="small text-body-secondary">No samples (deleted, or none were cut).</div>
              <audio v-for="a in s.sampleAssetIds" :key="a" controls preload="none" class="w-100 kd-sample" :src="`/api/assets/${a}/content`" />
            </div>

            <form v-if="editingId === s.id" class="mt-2 small" novalidate @submit.prevent="saveEdit(s)">
              <label class="form-label mb-0" :for="`name-${s.id}`">Character name</label>
              <input :id="`name-${s.id}`" v-model="form.characterName" class="form-control form-control-sm kd-edit-name" maxlength="150">
              <div v-if="formErrors.characterName" class="text-danger">{{ formErrors.characterName }}</div>
              <label class="form-label mb-0 mt-1" :for="`desc-${s.id}`">Description</label>
              <textarea :id="`desc-${s.id}`" v-model="form.description" class="form-control form-control-sm" rows="2" maxlength="2000" />
              <label class="form-label mb-0 mt-1" :for="`cat-${s.id}`">Category (your judgement)</label>
              <select :id="`cat-${s.id}`" v-model="form.suggestedCategory" class="form-select form-select-sm kd-edit-category">
                <option value="">Not set</option>
                <option v-for="c in VOICE_CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</option>
              </select>
              <div class="d-flex gap-2 mt-2">
                <BButton type="submit" size="sm" variant="primary" class="kd-save-speaker" :disabled="busy">Save</BButton>
                <BButton size="sm" variant="outline-secondary" @click="editingId = null">Cancel</BButton>
              </div>
            </form>

            <div class="d-flex flex-wrap align-items-center gap-2 mt-2">
              <BButton size="sm" variant="outline-secondary" class="kd-edit" :disabled="busy" @click="startEdit(s)"><Icon name="tabler:pencil" /> Rename</BButton>
              <BButton size="sm" :variant="s.reviewStatus === 'REVIEWED' ? 'outline-success' : 'outline-secondary'" class="kd-review"
                       :disabled="busy" @click="toggleReviewed(s)">
                <Icon name="tabler:check" /> {{ s.reviewStatus === 'REVIEWED' ? 'Reviewed' : 'Mark reviewed' }}
              </BButton>
              <BButton size="sm" variant="outline-secondary" class="kd-split" :disabled="busy || !s.segmentCount" @click="openSplit(s)">
                <Icon name="tabler:arrows-split" /> Move lines
              </BButton>
              <select class="form-select form-select-sm w-auto ms-auto kd-voice" :value="s.voiceProfileId ?? ''" :disabled="busy"
                      :aria-label="`Voice for ${s.code}`" @change="assignVoice(s, ($event.target as HTMLSelectElement).value)">
                <option value="">No voice</option>
                <option v-for="v in activeVoices" :key="v.id" :value="v.id">{{ v.displayName }}</option>
                <option v-if="s.voiceProfileId && !activeVoices.some((v) => v.id === s.voiceProfileId)" :value="s.voiceProfileId" disabled>
                  (deactivated voice)
                </option>
              </select>
            </div>
          </BCard>
        </div>
      </div>

      <div v-if="merged.length" class="small text-body-secondary mt-3 kd-merged">
        Merged away (kept for undo):
        <span v-for="m in merged" :key="m.id" class="me-2">{{ m.code }} → {{ label(byId[m.mergedIntoId!]) }}</span>
      </div>
      </fieldset>

      <!-- move lines of one speaker -->
      <BModal :model-value="!!splitFor" :title="`Move lines of ${label(splitFor ?? undefined)}`" size="lg" no-footer
              @update:model-value="(v: boolean) => { if (!v) splitFor = null }">
        <p class="small">Choose the lines spoken by someone else. Each line is played from the movie's own audio.</p>
        <div class="kd-split-lines" style="max-height: 45vh; overflow: auto">
          <div v-for="l in splitLines" :key="l.id" class="d-flex align-items-center gap-2 border-bottom py-1 small">
            <input v-model="splitChosen" class="form-check-input kd-split-pick" type="checkbox" :value="l.id" :aria-label="`Line ${l.seq + 1}`">
            <LineAudioButton :project-id="projectId" :segment-id="l.id" />
            <span class="kd-mono">{{ formatTimecode(l.startMs) }}</span>
            <span class="flex-grow-1">{{ currentChinese(l) }}</span>
            <BBadge v-if="l.flags.mixedSpeakers" variant="warning">mixed</BBadge>
          </div>
        </div>
        <div v-if="error" class="text-danger small mt-2 kd-modal-error">{{ error.message }}</div>
        <div class="row g-2 mt-2 small">
          <div class="col-md-6">
            <label class="form-label mb-0" for="split-name">New speaker's name (optional)</label>
            <div class="d-flex gap-2">
              <input id="split-name" v-model="splitName" class="form-control form-control-sm" maxlength="150">
              <BButton size="sm" variant="primary" class="kd-split-new" :disabled="!splitChosen.length || busy || readOnly" @click="splitToNew">To new speaker</BButton>
            </div>
          </div>
          <div class="col-md-6">
            <label class="form-label mb-0" for="move-to">Or move to an existing speaker</label>
            <div class="d-flex gap-2">
              <select id="move-to" v-model="reassignTo" class="form-select form-select-sm kd-move-target">
                <option value="" disabled>choose</option>
                <option v-for="o in visible.filter((x) => x.id !== splitFor?.id)" :key="o.id" :value="o.id">{{ label(o) }}</option>
              </select>
              <BButton size="sm" variant="primary" class="kd-move-existing" :disabled="!splitChosen.length || !reassignTo || busy || readOnly" @click="moveToExisting">Move</BButton>
            </div>
          </div>
        </div>
      </BModal>

      <!-- privacy -->
      <BCard class="mt-4 border-danger-subtle">
        <h2 class="h6 text-danger"><Icon name="tabler:shield-lock" /> Voice data (stays on this PC)</h2>
        <p class="small mb-2">
          Voice fingerprints are used only to match speakers between movie parts and are never shown or sent anywhere.
          <template v-if="privacy">Fingerprint files: {{ privacy.fingerprintFiles }} · voice sample files: {{ privacy.voiceSampleFiles }}.</template>
        </p>
        <div class="d-flex flex-wrap gap-2">
          <BButton size="sm" variant="outline-danger" class="kd-forget" :disabled="busy || !privacy?.fingerprintFiles" @click="danger = 'fingerprints'">Forget voice fingerprints</BButton>
          <BButton size="sm" variant="outline-danger" class="kd-delete-samples" :disabled="busy || !privacy?.voiceSampleFiles" @click="danger = 'samples'">Delete voice samples</BButton>
        </div>
        <BAlert :model-value="!!danger" variant="danger" class="small mt-2 py-2">
          {{ danger === 'fingerprints'
            ? 'Permanently delete this project\'s voice fingerprints? Speaker names and dialogue stay; re-matching speakers would need processing again.'
            : 'Permanently delete this project\'s voice samples? You will no longer hear speakers on their cards; line playback still works.' }}
          <div class="mt-2 d-flex gap-2">
            <BButton size="sm" variant="danger" class="kd-confirm-danger" :disabled="busy" @click="runDanger">Delete</BButton>
            <BButton size="sm" variant="outline-secondary" @click="danger = null">Cancel</BButton>
          </div>
        </BAlert>
      </BCard>
    </template>
  </div>
</template>
