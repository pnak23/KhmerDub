<script setup lang="ts">
import { useForm } from 'vee-validate'
import type { DialoguePage, GlossaryCategory, GlossaryPreview, GlossaryTerm, OperationResult, ProjectDetail } from '~/types/api'
import type { ApiError } from '~/utils/api'

const route = useRoute()
const projectId = computed(() => String(route.params.id))

const project = ref<ProjectDetail | null>(null)
const terms = ref<GlossaryTerm[]>([])
const error = ref<ApiError | null>(null)
const notice = ref<string | null>(null)
const busy = ref(false)
const editing = ref<GlossaryTerm | null>(null)
const deleting = ref<GlossaryTerm | null>(null)
const undoBar = ref<{ refresh: () => Promise<void> } | null>(null)

// preview / apply
const preview = ref<GlossaryPreview | null>(null)
const chosen = ref<string[]>([])
/** A preview belongs to the revision in use when it was made; if another revision is put in use, it cannot be applied. */
const guard = useRevisionGuard(projectId)
watch(preview, (p) => { if (!p) guard.accept() })
// find a spelling in the dialogue
const findText = ref('')
const found = ref<DialoguePage | null>(null)
const addVariantTo = ref('')

const { handleSubmit, errors, defineField, resetForm, setErrors } = useForm({
  validationSchema: toTypedSchema(glossaryTermSchema),
  initialValues: { sourceTerm: '', category: 'CHARACTER' as GlossaryCategory, variants: '', khmerTerm: '', notes: '' },
})
const [sourceTerm] = defineField('sourceTerm')
const [category] = defineField('category')
const [variants] = defineField('variants')
const [khmerTerm] = defineField('khmerTerm')
const [notes] = defineField('notes')

async function load() {
  try {
    project.value ??= await api<ProjectDetail>(`/projects/${projectId.value}`)
    terms.value = await api<GlossaryTerm[]>(`/projects/${projectId.value}/glossary`)
  } catch (e) {
    error.value = toApiError(e)
  }
}

function startEdit(t: GlossaryTerm | null) {
  editing.value = t
  resetForm({ values: t
    ? { sourceTerm: t.sourceTerm, category: t.category, variants: t.variants.join('\n'), khmerTerm: t.khmerTerm ?? '', notes: t.notes ?? '' }
    : { sourceTerm: '', category: 'CHARACTER', variants: '', khmerTerm: '', notes: '' } })
}

const saveTerm = handleSubmit(async (form) => {
  error.value = null
  busy.value = true
  const body = { sourceTerm: form.sourceTerm, category: form.category, variants: parseVariants(form.variants),
    khmerTerm: form.khmerTerm, notes: form.notes }
  try {
    if (editing.value) {
      await api<GlossaryTerm>(`/projects/${projectId.value}/glossary/${editing.value.id}`, {
        method: 'PATCH', body: { version: editing.value.version, ...body },
      })
      notice.value = `Saved "${form.sourceTerm}".`
    } else {
      await api<GlossaryTerm>(`/projects/${projectId.value}/glossary`, { method: 'POST', body })
      notice.value = `Added "${form.sourceTerm}".`
    }
    editing.value = null
    startEdit(null)
    preview.value = null
    await load()
  } catch (e) {
    const err = toApiError(e)
    if (Object.keys(err.fieldErrors).length) setErrors(err.fieldErrors)
    error.value = err
  } finally {
    busy.value = false
  }
})

async function confirmDelete() {
  const t = deleting.value
  if (!t) return
  error.value = null
  try {
    await api(`/projects/${projectId.value}/glossary/${t.id}`, { method: 'DELETE', query: { version: t.version } })
    notice.value = `Deleted "${t.sourceTerm}".`
    deleting.value = null
    preview.value = null
    await load()
  } catch (e) {
    error.value = toApiError(e)
  }
}

async function runPreview() {
  error.value = null
  notice.value = null
  try {
    const next = await api<GlossaryPreview>(`/projects/${projectId.value}/glossary/preview`, { method: 'POST', body: {} })
    guard.accept()
    await guard.track(next.revisionId)
    preview.value = next
    chosen.value = next.changes.map((c) => c.segmentId)
  } catch (e) {
    error.value = toApiError(e)
  }
}

async function apply() {
  if (!preview.value || !chosen.value.length) return
  busy.value = true
  error.value = null
  try {
    const all = chosen.value.length === preview.value.changes.length
    const result = await api<OperationResult>(`/projects/${projectId.value}/glossary/apply`, {
      method: 'POST', body: { token: preview.value.token, segmentIds: all ? undefined : chosen.value },
    })
    notice.value = `${result.operation.summary}. Undo is available above.`
    preview.value = null
    await undoBar.value?.refresh()
  } catch (e) {
    error.value = toApiError(e)
    if (error.value.code === 'PREVIEW_STALE') preview.value = null
    else if (isReadOnly(error.value)) await guard.check()
  } finally {
    busy.value = false
  }
}

async function find() {
  const q = findText.value.trim()
  if (!q) return
  found.value = await api<DialoguePage>(`/projects/${projectId.value}/dialogue`, { query: { q, limit: 50 } })
    .catch((e) => { error.value = toApiError(e); return null })
}

async function addAsVariant() {
  const t = terms.value.find((x) => x.id === addVariantTo.value)
  const v = findText.value.trim()
  if (!t || !v) return
  error.value = null
  try {
    await api<GlossaryTerm>(`/projects/${projectId.value}/glossary/${t.id}`, {
      method: 'PATCH', body: { version: t.version, variants: [...t.variants, v] },
    })
    notice.value = `"${v}" is now a variant of "${t.sourceTerm}". Preview to see which lines change.`
    addVariantTo.value = ''
    await load()
  } catch (e) {
    error.value = toApiError(e)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <ReviewNav :project-id="projectId" current="glossary" :title="project?.summary.name" />
    <div class="d-flex align-items-center gap-2 mb-2">
      <h1 class="h4 mb-0">Glossary</h1>
      <span class="small text-body-secondary">Correct spellings of names, places and terms. The Khmer term is used for translation later.</span>
    </div>
    <UndoBar ref="undoBar" :project-id="projectId" :busy="busy" class="mb-2" @undone="notice = 'Undone.'" @reload="load" />
    <ReviewError :error="error" @reload="load(); runPreview(); error = null" @dismiss="error = null" />
    <BAlert :model-value="!!notice" variant="success" class="small py-2 kd-notice" dismissible @update:model-value="notice = null">{{ notice }}</BAlert>

    <div class="row g-3">
      <div class="col-xl-7">
        <BCard>
          <h2 class="h6">Terms</h2>
          <p v-if="!terms.length" class="small text-body-secondary">No terms yet.</p>
          <table v-else class="table table-sm small align-middle kd-terms">
            <thead><tr><th>Category</th><th>Correct</th><th>Misheard as</th><th>Khmer</th><th /></tr></thead>
            <tbody>
              <tr v-for="t in terms" :key="t.id" :data-term="t.sourceTerm">
                <td>{{ humanize(t.category) }}</td>
                <td lang="zh"><strong>{{ t.sourceTerm }}</strong><div v-if="t.notes" class="text-body-secondary">{{ t.notes }}</div></td>
                <td lang="zh">{{ t.variants.join(', ') || '—' }}</td>
                <td lang="km">{{ t.khmerTerm ?? '—' }}</td>
                <td class="text-end text-nowrap">
                  <BButton size="sm" variant="link" class="kd-edit-term" @click="startEdit(t)">Edit</BButton>
                  <BButton size="sm" variant="link" class="text-danger kd-delete-term" @click="deleting = t">Delete</BButton>
                </td>
              </tr>
            </tbody>
          </table>
          <BAlert :model-value="!!deleting" variant="warning" class="small py-2">
            Delete "{{ deleting?.sourceTerm }}"? Lines already changed keep their text (use Undo for those).
            <div class="mt-2 d-flex gap-2">
              <BButton size="sm" variant="danger" class="kd-confirm-delete" @click="confirmDelete">Delete</BButton>
              <BButton size="sm" variant="outline-secondary" @click="deleting = null">Cancel</BButton>
            </div>
          </BAlert>
        </BCard>

        <BCard class="mt-3">
          <div class="d-flex align-items-center gap-2">
            <h2 class="h6 mb-0">Apply to the dialogue</h2>
            <BButton size="sm" variant="primary" class="ms-auto kd-preview" :disabled="!terms.length" @click="runPreview">
              <Icon name="tabler:eye" /> Preview changes
            </BButton>
          </div>
          <p class="small text-body-secondary mb-2">
            Nothing changes until you apply a preview. Applying writes your Chinese correction only (the recognised text is kept)
            and can be undone.
          </p>
          <template v-if="preview">
            <BAlert :model-value="guard.switched.value" variant="warning" class="small py-2 kd-revision-switched">
              <Icon name="tabler:lock" /> This preview was made for revision {{ guard.shownNo.value ?? '' }}, which is no longer in use
              <template v-if="guard.inUse.value">(revision {{ guard.inUse.value.revisionNo }} is in use now)</template>. Nothing was
              applied; only the revision in use can be changed.
              <BButton size="sm" variant="warning" class="ms-2 kd-preview-again" :disabled="busy" @click="runPreview">Preview again</BButton>
            </BAlert>
            <p class="small kd-preview-summary"><strong>{{ preview.lines }}</strong> line(s), <strong>{{ preview.replacements }}</strong> replacement(s).</p>
            <div style="max-height: 50vh; overflow: auto">
              <div v-for="c in preview.changes" :key="c.segmentId" class="d-flex align-items-start gap-2 border-bottom py-1 small kd-preview-line">
                <input v-model="chosen" class="form-check-input mt-1" type="checkbox" :value="c.segmentId" :aria-label="`Line ${c.seq + 1}`">
                <LineAudioButton :project-id="projectId" :segment-id="c.segmentId" />
                <span class="kd-mono">#{{ c.seq + 1 }}</span>
                <div lang="zh">
                  <div class="text-decoration-line-through text-body-secondary">{{ c.before }}</div>
                  <div class="kd-after">{{ c.after }}</div>
                </div>
              </div>
            </div>
            <BButton size="sm" variant="primary" class="mt-2 kd-apply" :disabled="!chosen.length || busy || guard.switched.value" @click="apply">
              Apply {{ chosen.length }} line(s)
            </BButton>
          </template>
        </BCard>
      </div>

      <div class="col-xl-5">
        <BCard>
          <h2 class="h6">{{ editing ? `Edit "${editing.sourceTerm}"` : 'Add a term' }}</h2>
          <form class="small" novalidate @submit.prevent="saveTerm">
            <label class="form-label mb-0" for="g-term">Correct spelling</label>
            <input id="g-term" v-model="sourceTerm" class="form-control form-control-sm kd-term" lang="zh" maxlength="200">
            <div v-if="errors.sourceTerm" class="text-danger">{{ errors.sourceTerm }}</div>
            <label class="form-label mb-0 mt-1" for="g-cat">Category</label>
            <select id="g-cat" v-model="category" class="form-select form-select-sm">
              <option value="CHARACTER">Character</option><option value="PLACE">Place</option><option value="TERM">Term</option>
            </select>
            <label class="form-label mb-0 mt-1" for="g-var">Misheard as (one per line)</label>
            <textarea id="g-var" v-model="variants" class="form-control form-control-sm kd-variants" rows="3" lang="zh" />
            <label class="form-label mb-0 mt-1" for="g-km">Khmer term</label>
            <input id="g-km" v-model="khmerTerm" class="form-control form-control-sm kd-khmer-term" lang="km" maxlength="200">
            <label class="form-label mb-0 mt-1" for="g-notes">Notes</label>
            <input id="g-notes" v-model="notes" class="form-control form-control-sm" maxlength="2000">
            <div class="d-flex gap-2 mt-2">
              <BButton type="submit" size="sm" variant="primary" class="kd-save-term" :disabled="busy">{{ editing ? 'Save' : 'Add' }}</BButton>
              <BButton v-if="editing" size="sm" variant="outline-secondary" @click="startEdit(null)">Cancel</BButton>
            </div>
          </form>
        </BCard>

        <BCard class="mt-3">
          <h2 class="h6">Find a spelling in the dialogue</h2>
          <form class="d-flex gap-2 small" @submit.prevent="find">
            <input v-model="findText" class="form-control form-control-sm kd-find" lang="zh" maxlength="200" placeholder="e.g. 碎碎">
            <BButton type="submit" size="sm" variant="outline-secondary" class="kd-find-go">Find</BButton>
          </form>
          <template v-if="found">
            <p class="small mt-2 mb-1 kd-found-count">{{ found.total }} line(s) contain it.</p>
            <div style="max-height: 30vh; overflow: auto">
              <div v-for="l in found.lines" :key="l.id" class="d-flex align-items-center gap-2 small border-bottom py-1">
                <LineAudioButton :project-id="projectId" :segment-id="l.id" />
                <span class="kd-mono">#{{ l.seq + 1 }}</span><span lang="zh">{{ currentChinese(l) }}</span>
              </div>
            </div>
            <div v-if="found.total && terms.length" class="d-flex gap-2 mt-2 small">
              <select v-model="addVariantTo" class="form-select form-select-sm kd-variant-of">
                <option value="" disabled>Add "{{ findText }}" as a misspelling of…</option>
                <option v-for="t in terms" :key="t.id" :value="t.id">{{ t.sourceTerm }}</option>
              </select>
              <BButton size="sm" variant="outline-primary" class="kd-add-variant" :disabled="!addVariantTo" @click="addAsVariant">Add</BButton>
            </div>
          </template>
        </BCard>
      </div>
    </div>
  </div>
</template>
