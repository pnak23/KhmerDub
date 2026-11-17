<script setup lang="ts">
import type { DialoguePage, LineView, OperationResult, ProjectDetail, SpeakerView } from '~/types/api'
import { ApiError } from '~/utils/api'

const route = useRoute()
const projectId = computed(() => String(route.params.id))

const project = ref<ProjectDetail | null>(null)
const page = ref<DialoguePage | null>(null)
const speakers = ref<SpeakerView[]>([])
const error = ref<ApiError | null>(null)
const notice = ref<string | null>(null)
const loading = ref(false)
const busy = ref<string | null>(null) // id of the line being saved
const undoBar = ref<{ refresh: () => Promise<void>; undo: () => Promise<void> } | null>(null)

// paging (cursor = first sequence number of the page) and filters
const pageSize = ref<number>(100)
const cursors = ref<number[]>([0])
const filters = reactive({
  q: '',
  speakerId: '',
  flag: typeof route.query.flag === 'string' ? route.query.flag : '',
  reviewStatus: '',
})
const jumpTo = ref('')

/**
 * Unsaved text per line, with the saved text it started from. Text the user typed is kept across reloads, so a refused
 * (conflicting) save never loses it; the current saved version is then shown next to it and the user decides.
 */
interface Draft { seq: number; chinese: string; khmer: string; baseChinese: string; baseKhmer: string }
const drafts = reactive<Record<string, Draft>>({})
const current = ref(0)
const audioButtons = ref<Record<string, { play: () => Promise<void> } | null>>({})

/** Only the revision in use can be edited: if it is switched elsewhere, this page turns read-only (see the guard). */
const guard = useRevisionGuard(projectId)
const readOnly = computed(() => guard.switched.value)

const lines = computed(() => page.value?.lines ?? [])
const speakerById = computed(() => Object.fromEntries(speakers.value.map((s) => [s.id, s])))
const usableSpeakers = computed(() => speakers.value.filter((s) => !s.mergedIntoId))
const pageNo = computed(() => cursors.value.length)
const pages = computed(() => pageCount(page.value?.total ?? 0, pageSize.value))
const filtered = computed(() => !!(filters.q || filters.speakerId || filters.flag || filters.reviewStatus))

function speakerLabel(id: string | null): string {
  const s = id ? speakerById.value[id] : undefined
  return s ? (s.characterName ? `${s.code} · ${s.characterName}` : s.code) : '—'
}

function fresh(l: LineView): Draft {
  const chinese = currentChinese(l)
  const khmer = l.editedText ?? ''
  return { seq: l.seq, chinese, khmer, baseChinese: chinese, baseKhmer: khmer }
}

function typed(d: Draft | undefined): boolean {
  return !!d && (d.chinese !== d.baseChinese || d.khmer !== d.baseKhmer)
}

/** Lines without typed text follow the server; typed text stays (force = after a successful save). */
function syncDrafts(list: LineView[], force = false) {
  for (const l of list) {
    if (force || !typed(drafts[l.id])) drafts[l.id] = fresh(l)
  }
}

function dirty(l: LineView): boolean {
  const d = drafts[l.id]
  return typed(d) && Object.keys(lineChanges(l, d!)).length > 0
}

/** Typed text based on an older saved version (someone saved in between). */
function serverMoved(l: LineView): boolean {
  const d = drafts[l.id]
  return typed(d) && (currentChinese(l) !== d!.baseChinese || (l.editedText ?? '') !== d!.baseKhmer)
}

/** Text typed but not saved, for the read-only banner (so it can be copied). */
const unsaved = computed(() => Object.values(drafts).filter(typed).sort((a, b) => a.seq - b.seq).map((d) => `Line ${d.seq + 1}: ${
  [d.chinese !== d.baseChinese ? `Chinese "${d.chinese}"` : '', d.khmer !== d.baseKhmer ? `Khmer "${d.khmer}"` : ''].filter(Boolean).join(' · ')}`))

async function load() {
  if (readOnly.value) return // keep showing the old revision (and the typed text) until the user opens the one in use
  loading.value = true
  try {
    project.value ??= await api<ProjectDetail>(`/projects/${projectId.value}`)
    const next = await api<DialoguePage>(`/projects/${projectId.value}/dialogue`, {
      query: {
        fromSeq: cursors.value[cursors.value.length - 1], limit: pageSize.value,
        q: filters.q || undefined, speakerId: filters.speakerId || undefined,
        flag: filters.flag || undefined, reviewStatus: filters.reviewStatus || undefined,
      },
    })
    if (!(await guard.track(next.revisionId))) return // switched under this page: never swap the lines silently
    // the speakers of the same revision as the lines (never of another one, even if a switch happens meanwhile)
    speakers.value = await api<SpeakerView[]>(`/projects/${projectId.value}/speakers`, { query: { revisionId: next.revisionId } })
    syncDrafts(next.lines)
    page.value = next
    current.value = Math.min(current.value, Math.max(0, next.lines.length - 1))
  } catch (e) {
    error.value = toApiError(e)
  } finally {
    loading.value = false
  }
}

/** The user chose to leave the old revision: its unsaved text stays listed by the banner until dismissed. */
async function openRevisionInUse() {
  for (const id of Object.keys(drafts)) delete drafts[id]
  timing.value = null
  splitting.value = null
  merging.value = null
  error.value = null
  notice.value = null
  const reloadedByFilter = !!filters.speakerId
  filters.speakerId = '' // speaker ids belong to one revision (clearing it reloads through the filter watcher)
  guard.accept()
  page.value = null
  cursors.value = [0]
  current.value = 0
  if (!reloadedByFilter) await load()
  await undoBar.value?.refresh()
}

function resetPaging() {
  cursors.value = [0]
  load()
}

function nextPage() {
  const last = lines.value[lines.value.length - 1]
  if (!last || pageNo.value >= pages.value) return
  cursors.value = [...cursors.value, last.seq + 1]
  load()
}

function previousPage() {
  if (cursors.value.length <= 1) return
  cursors.value = cursors.value.slice(0, -1)
  load()
}

function jump() {
  const n = Number(jumpTo.value)
  if (!Number.isInteger(n) || n < 1) return
  cursors.value = [Math.max(0, n - 1)]
  load()
}

/** One change: on success apply the result, on refusal show it (never retried with a newer version automatically). */
async function change(lineId: string | null, run: () => Promise<OperationResult>): Promise<OperationResult | null> {
  busy.value = lineId ?? 'page'
  error.value = null
  notice.value = null
  try {
    const result = await run()
    if (needsReload(result.operation.type)) {
      const affected = new Set(result.lines.map((l) => l.id))
      await load()
      syncDrafts(lines.value.filter((l) => affected.has(l.id)), true)
      for (const id of result.removedLineIds) delete drafts[id]
    } else if (page.value) {
      syncDrafts(result.lines, true)
      page.value = { ...page.value, lines: applyLines(page.value.lines, result) }
      if (result.speakers.length) {
        speakers.value = await api<SpeakerView[]>(`/projects/${projectId.value}/speakers`, { query: { revisionId: page.value.revisionId } })
      }
    }
    await undoBar.value?.refresh()
    return result
  } catch (e) {
    error.value = toApiError(e)
    if (isReadOnly(error.value)) await guard.check() // most likely switched elsewhere: turn read-only
    return null
  } finally {
    busy.value = null
  }
}

function patchLine(l: LineView, body: Record<string, unknown>) {
  return change(l.id, () => api<OperationResult>(`/projects/${projectId.value}/segments/${l.id}`,
    { method: 'PATCH', body: { version: l.version, ...body } }))
}

async function save(l: LineView) {
  const d = drafts[l.id] ?? fresh(l)
  const parsed = lineTextSchema.safeParse(d)
  if (!parsed.success) {
    error.value = new ApiError({ status: 400, detail: parsed.error.issues[0]?.message ?? 'Invalid text' })
    return false
  }
  const body = lineChanges(l, d)
  if (!Object.keys(body).length) return true
  return !!(await patchLine(l, body))
}

function setStatus(l: LineView, status: string) {
  return patchLine(l, { reviewStatus: status })
}

function setSpeaker(l: LineView, speakerId: string) {
  if (speakerId && speakerId !== l.speakerId) return patchLine(l, { speakerId })
}

// ------------------------------------------------------------------ timing
const timing = ref<{ line: LineView; start: string; end: string; problem: string | null } | null>(null)

function openTiming(l: LineView) {
  error.value = null
  timing.value = { line: l, start: formatTimecode(l.startMs), end: formatTimecode(l.endMs), problem: null }
}

async function saveTiming() {
  const t = timing.value
  if (!t) return
  const start = parseTimecode(t.start)
  const end = parseTimecode(t.end)
  if (start === null || end === null) {
    t.problem = 'Use times like 1:23.4'
    return
  }
  if (end <= start) {
    t.problem = 'The end must be after the start'
    return
  }
  if (await patchLine(t.line, { startMs: start, endMs: end })) timing.value = null
}

// ------------------------------------------------------------------ split / merge
const splitting = ref<{ line: LineView; at: number; first: { chinese: string; khmer: string }; second: { chinese: string; khmer: string } } | null>(null)

function openSplit(l: LineView) {
  error.value = null
  const at = Math.max(1, Math.floor(l.words.length / 2))
  splitting.value = { line: l, at, first: { chinese: l.sourceTextEdited ?? '', khmer: l.editedText ?? '' }, second: { chinese: '', khmer: '' } }
}

const splitParts = computed(() => splitting.value ? splitPreview(splitting.value.line.words, splitting.value.at) : ['', ''])
const splitNeedsText = computed(() => !!splitting.value && (splitting.value.line.sourceTextEdited !== null || splitting.value.line.editedText !== null))

async function confirmSplit() {
  const s = splitting.value
  if (!s) return
  const body: Record<string, unknown> = { version: s.line.version, atWord: s.at }
  if (splitNeedsText.value) {
    body.first = { sourceTextEdited: s.first.chinese, editedText: s.first.khmer }
    body.second = { sourceTextEdited: s.second.chinese, editedText: s.second.khmer }
  }
  if (await change(s.line.id, () => api<OperationResult>(`/projects/${projectId.value}/segments/${s.line.id}/split`,
    { method: 'POST', body }))) splitting.value = null
}

const merging = ref<{ first: LineView; second: LineView; speakerId: string } | null>(null)

function openMerge(i: number) {
  const first = lines.value[i]
  const second = lines.value[i + 1]
  if (!first || !second || second.seq !== first.seq + 1) return
  error.value = null
  merging.value = { first, second, speakerId: first.speakerId ?? '' }
}

async function confirmMerge() {
  const m = merging.value
  if (!m) return
  const body: Record<string, unknown> = { first: { id: m.first.id, version: m.first.version }, second: { id: m.second.id, version: m.second.version } }
  if (m.first.speakerId !== m.second.speakerId) body.speakerId = m.speakerId
  if (await change(m.first.id, () => api<OperationResult>(`/projects/${projectId.value}/segments/merge`, { method: 'POST', body }))) {
    merging.value = null
  }
}

// ------------------------------------------------------------------ keyboard
function focusRow(i: number) {
  current.value = Math.max(0, Math.min(i, lines.value.length - 1))
  nextTick(() => (document.querySelector(`[data-line-index="${current.value}"] .kd-khmer`) as HTMLElement | null)?.focus())
}

async function onKey(e: KeyboardEvent) {
  const action = shortcutFor(e)
  if (!action || readOnly.value) return
  e.preventDefault()
  const l = lines.value[current.value]
  switch (action) {
    case 'save': if (l) await save(l); break
    case 'saveNext': if (l && await save(l)) focusRow(current.value + 1); break
    case 'next': focusRow(current.value + 1); break
    case 'previous': focusRow(current.value - 1); break
    case 'approve': if (l) await setStatus(l, l.reviewStatus === 'APPROVED' ? 'PENDING' : 'APPROVED'); break
    case 'play': if (l) await audioButtons.value[l.id]?.play(); break
    case 'undo': await undoBar.value?.undo(); break
  }
}

async function afterUndo() {
  notice.value = 'Undone.'
  await load()
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(() => filters.q, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(resetPaging, 350)
})
watch(() => [filters.speakerId, filters.flag, filters.reviewStatus, pageSize.value], resetPaging)

onMounted(load)
</script>

<template>
  <div>
    <ReviewNav :project-id="projectId" current="dialogue" :title="project?.summary.name" />
    <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
      <h1 class="h4 mb-0">Dialogue</h1>
      <BBadge v-if="page && !readOnly" variant="success" class="kd-shown-revision">Revision {{ guard.shownNo.value ?? '' }} in use</BBadge>
      <BBadge v-else-if="page" variant="secondary" class="kd-shown-revision"><Icon name="tabler:lock" /> Revision {{ guard.shownNo.value ?? '' }} · read-only</BBadge>
      <span v-if="page" class="small text-body-secondary kd-total">{{ page.total }} {{ filtered ? 'matching' : '' }} lines</span>
      <span class="small text-body-secondary ms-auto">
        Shortcuts: Ctrl+S save · Ctrl+Enter save + next · Alt+↑/↓ move · Alt+A approve · Alt+P play · Ctrl+Alt+Z undo
      </span>
    </div>

    <RevisionSwitchedBanner :switched="readOnly" :shown-no="guard.shownNo.value" :in-use="guard.inUse.value" :unsaved="unsaved"
                            @open="openRevisionInUse" />
    <UndoBar ref="undoBar" :project-id="projectId" :busy="!!busy || readOnly" class="mb-2" @undone="afterUndo" @reload="load" />

    <!-- filters -->
    <fieldset :disabled="readOnly" class="d-flex flex-wrap align-items-end gap-2 mb-2 small">
      <div>
        <label class="form-label mb-0" for="q">Search</label>
        <input id="q" v-model="filters.q" class="form-control form-control-sm kd-search" placeholder="Chinese or Khmer text" maxlength="200">
      </div>
      <div>
        <label class="form-label mb-0" for="f-speaker">Speaker</label>
        <select id="f-speaker" v-model="filters.speakerId" class="form-select form-select-sm kd-filter-speaker">
          <option value="">All</option>
          <option v-for="s in usableSpeakers" :key="s.id" :value="s.id">{{ speakerLabel(s.id) }}</option>
        </select>
      </div>
      <div>
        <label class="form-label mb-0" for="f-flag">Flag</label>
        <select id="f-flag" v-model="filters.flag" class="form-select form-select-sm kd-filter-flag">
          <option value="">Any or none</option>
          <option v-for="f in LINE_FLAGS" :key="f.value" :value="f.value">{{ f.label }}</option>
        </select>
      </div>
      <div>
        <label class="form-label mb-0" for="f-status">Status</label>
        <select id="f-status" v-model="filters.reviewStatus" class="form-select form-select-sm kd-filter-status">
          <option value="">All</option>
          <option v-for="s in LINE_STATUSES" :key="s" :value="s">{{ humanize(s) }}</option>
        </select>
      </div>
      <div>
        <label class="form-label mb-0" for="size">Lines per page</label>
        <select id="size" v-model.number="pageSize" class="form-select form-select-sm kd-page-size">
          <option v-for="n in PAGE_SIZES" :key="n" :value="n">{{ n }}</option>
        </select>
      </div>
      <form v-if="!filtered" class="d-flex align-items-end gap-1" @submit.prevent="jump">
        <div>
          <label class="form-label mb-0" for="jump">Go to line</label>
          <input id="jump" v-model="jumpTo" class="form-control form-control-sm kd-jump" style="width: 6rem" inputmode="numeric">
        </div>
        <BButton type="submit" size="sm" variant="outline-secondary">Go</BButton>
      </form>
    </fieldset>

    <ReviewError :error="error" @reload="load(); error = null" @dismiss="error = null" />
    <BAlert :model-value="!!notice" variant="success" class="small py-2 kd-notice" dismissible @update:model-value="notice = null">{{ notice }}</BAlert>

    <div v-if="!page && loading" class="text-body-secondary"><BSpinner small /> Loading…</div>
    <div v-else-if="page" class="table-responsive kd-editor" @keydown="onKey">
      <fieldset :disabled="readOnly" class="kd-editor-scope">
      <table class="table table-sm align-top small mb-2">
        <thead class="text-body-secondary">
          <tr>
            <th style="width: 7.5rem">Time</th>
            <th style="width: 11rem">Speaker</th>
            <th>Recognised (read-only)</th>
            <th>Chinese correction</th>
            <th>Khmer</th>
            <th style="width: 10rem" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(l, i) in lines" :key="l.id" :data-line-index="i" :data-seq="l.seq" class="kd-line"
              :class="{ 'table-active': i === current, 'table-success': l.reviewStatus === 'APPROVED' }" @focusin="current = i">
            <td class="kd-mono">
              <div>{{ l.seq + 1 }}</div>
              <div>{{ formatTimecode(l.startMs) }}</div>
              <div class="text-body-secondary">{{ formatTimecode(l.endMs) }}</div>
              <div class="d-flex gap-1 mt-1">
                <LineAudioButton :ref="(c: unknown) => { audioButtons[l.id] = c as { play: () => Promise<void> } | null }"
                                 :project-id="projectId" :segment-id="l.id" />
                <BButton size="sm" variant="link" class="p-0 kd-timing" title="Edit timing" @click="openTiming(l)"><Icon name="tabler:clock-edit" /></BButton>
              </div>
            </td>
            <td>
              <select class="form-select form-select-sm kd-line-speaker" :value="l.speakerId ?? ''" :disabled="busy === l.id"
                      :aria-label="`Speaker of line ${l.seq + 1}`" @change="setSpeaker(l, ($event.target as HTMLSelectElement).value)">
                <option v-if="!l.speakerId" value="">—</option>
                <option v-for="s in usableSpeakers" :key="s.id" :value="s.id">{{ speakerLabel(s.id) }}</option>
              </select>
              <div class="mt-1 d-flex flex-wrap gap-1">
                <BBadge v-if="l.speakerAssignment === 'MANUAL'" variant="light" class="text-dark">manual</BBadge>
                <BBadge v-for="(on, f) in l.flags" v-show="on" :key="f" variant="warning" class="kd-flag">{{ humanize(String(f)) }}</BBadge>
              </div>
            </td>
            <td class="kd-original" lang="zh">{{ l.sourceText }}</td>
            <td>
              <textarea v-if="drafts[l.id]" v-model="drafts[l.id]!.chinese" class="form-control form-control-sm kd-chinese" rows="2" maxlength="5000" lang="zh"
                        :aria-label="`Chinese correction of line ${l.seq + 1}`" />
            </td>
            <td>
              <textarea v-if="drafts[l.id]" v-model="drafts[l.id]!.khmer" class="form-control form-control-sm kd-khmer" rows="2" maxlength="5000" lang="km"
                        :aria-label="`Khmer of line ${l.seq + 1}`" />
              <div v-if="serverMoved(l)" class="text-warning-emphasis kd-saved-version">
                Saved meanwhile: {{ currentChinese(l) }} · {{ l.editedText ?? '—' }} (your unsaved text is kept above)
              </div>
            </td>
            <td>
              <div class="d-flex flex-wrap gap-1">
                <BButton size="sm" :variant="dirty(l) ? 'primary' : 'outline-secondary'" class="kd-save" :disabled="!dirty(l) || busy === l.id"
                         title="Ctrl+S" @click="save(l)">
                  <BSpinner v-if="busy === l.id" small /> Save
                </BButton>
                <BButton size="sm" :variant="l.reviewStatus === 'APPROVED' ? 'success' : 'outline-success'" class="kd-approve"
                         :disabled="busy === l.id" title="Alt+A"
                         @click="setStatus(l, l.reviewStatus === 'APPROVED' ? 'PENDING' : 'APPROVED')">
                  <Icon name="tabler:check" />
                </BButton>
                <BButton size="sm" variant="outline-secondary" class="kd-split-line" :disabled="l.words.length < 2 || !!busy" title="Split"
                         @click="openSplit(l)"><Icon name="tabler:arrows-split-2" /></BButton>
                <BButton size="sm" variant="outline-secondary" class="kd-merge-next" :disabled="i === lines.length - 1 || lines[i + 1]?.seq !== l.seq + 1 || !!busy"
                         title="Merge with the next line" @click="openMerge(i)"><Icon name="tabler:arrow-merge" /></BButton>
              </div>
              <div class="text-body-secondary mt-1">{{ humanize(l.reviewStatus) }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      </fieldset>
      <p v-if="!lines.length" class="text-body-secondary">No lines match.</p>
    </div>

    <div v-if="page" class="d-flex align-items-center gap-2 small">
      <BButton size="sm" variant="outline-secondary" class="kd-prev" :disabled="pageNo <= 1 || loading || readOnly" @click="previousPage">Previous</BButton>
      <span class="kd-page">Page {{ pageNo }} of {{ pages }}</span>
      <BButton size="sm" variant="outline-secondary" class="kd-next" :disabled="pageNo >= pages || loading || readOnly" @click="nextPage">Next</BButton>
    </div>

    <!-- timing -->
    <BModal :model-value="!!timing" title="Line timing" no-footer @update:model-value="(v: boolean) => { if (!v) timing = null }">
      <form v-if="timing" class="small" novalidate @submit.prevent="saveTiming">
        <div class="row g-2">
          <div class="col"><label class="form-label mb-0" for="t-start">Start</label><input id="t-start" v-model="timing.start" class="form-control form-control-sm kd-t-start"></div>
          <div class="col"><label class="form-label mb-0" for="t-end">End</label><input id="t-end" v-model="timing.end" class="form-control form-control-sm kd-t-end"></div>
        </div>
        <div v-if="timing.problem" class="text-danger mt-1">{{ timing.problem }}</div>
        <div v-if="error" class="text-danger mt-1 kd-modal-error">{{ error.message }}</div>
        <p class="form-text">A line keeps its place: its start stays between the neighbouring lines' starts.</p>
        <BButton type="submit" size="sm" variant="primary" class="kd-save-timing" :disabled="readOnly">Save timing</BButton>
      </form>
    </BModal>

    <!-- split -->
    <BModal :model-value="!!splitting" title="Split line" size="lg" no-footer @update:model-value="(v: boolean) => { if (!v) splitting = null }">
      <div v-if="splitting" class="small">
        <p>Choose where the second line starts (the recognised text stays exactly as it is, split at a word):</p>
        <div class="d-flex flex-wrap gap-1 mb-2 kd-words" lang="zh">
          <template v-for="(w, k) in splitting.line.words" :key="k">
            <BButton v-if="k > 0" size="sm" :variant="splitting.at === k ? 'primary' : 'outline-secondary'" class="px-1 py-0 kd-cut"
                     :data-at="k" :aria-label="`Split before word ${k + 1}`" @click="splitting.at = k">|</BButton>
            <span class="align-self-center">{{ w.w }}</span>
          </template>
        </div>
        <div class="row g-2">
          <div class="col-md-6"><strong>First:</strong> <span class="kd-part1">{{ splitParts[0] }}</span></div>
          <div class="col-md-6"><strong>Second:</strong> <span class="kd-part2">{{ splitParts[1] }}</span></div>
        </div>
        <template v-if="splitNeedsText">
          <BAlert :model-value="true" variant="info" class="py-2 mt-2">This line has your own text. Say what each part keeps, so nothing is lost.</BAlert>
          <div class="row g-2">
            <div class="col-md-6">
              <label class="form-label mb-0">First: Chinese correction</label><input v-model="splitting.first.chinese" class="form-control form-control-sm">
              <label class="form-label mb-0 mt-1">First: Khmer</label><input v-model="splitting.first.khmer" class="form-control form-control-sm kd-first-khmer">
            </div>
            <div class="col-md-6">
              <label class="form-label mb-0">Second: Chinese correction</label><input v-model="splitting.second.chinese" class="form-control form-control-sm">
              <label class="form-label mb-0 mt-1">Second: Khmer</label><input v-model="splitting.second.khmer" class="form-control form-control-sm kd-second-khmer">
            </div>
          </div>
        </template>
        <div v-if="error" class="text-danger mt-2 kd-modal-error">{{ error.message }}</div>
        <BButton size="sm" variant="primary" class="mt-2 kd-confirm-split" :disabled="!!busy || readOnly" @click="confirmSplit">Split</BButton>
      </div>
    </BModal>

    <!-- merge -->
    <BModal :model-value="!!merging" title="Merge with the next line" no-footer @update:model-value="(v: boolean) => { if (!v) merging = null }">
      <div v-if="merging" class="small">
        <p>Lines {{ merging.first.seq + 1 }} and {{ merging.second.seq + 1 }} become one line:</p>
        <p class="kd-mono" lang="zh">{{ currentChinese(merging.first) }}{{ currentChinese(merging.second) }}</p>
        <template v-if="merging.first.speakerId !== merging.second.speakerId">
          <label class="form-label mb-0" for="merge-speaker">The lines have different speakers. The merged line belongs to</label>
          <select id="merge-speaker" v-model="merging.speakerId" class="form-select form-select-sm kd-merge-speaker">
            <option :value="merging.first.speakerId ?? ''">{{ speakerLabel(merging.first.speakerId) }}</option>
            <option :value="merging.second.speakerId ?? ''">{{ speakerLabel(merging.second.speakerId) }}</option>
          </select>
        </template>
        <div v-if="error" class="text-danger mt-2 kd-modal-error">{{ error.message }}</div>
        <BButton size="sm" variant="primary" class="mt-2 kd-confirm-merge" :disabled="!!busy || readOnly" @click="confirmMerge">Merge</BButton>
      </div>
    </BModal>
  </div>
</template>
