<script setup lang="ts">
import { useForm } from 'vee-validate'
import type { StorageInfo } from '~/types/api'

const projects = useProjectsStore()
const storage = ref<StorageInfo | null>(null)
const storageError = ref<string | null>(null)
const createdProjectId = ref<string | null>(null)
const pendingFile = ref<File | null>(null)
const submitError = ref<string | null>(null)

onMounted(async () => {
  try {
    storage.value = await api<StorageInfo>('/system/storage')
  } catch (e) {
    storageError.value = (e as Error).message
  }
})

const maxBytes = computed(() => storage.value?.maxUploadBytes ?? 20 * 1024 ** 3)
const { handleSubmit, errors: allErrors, defineField, setFieldValue, values, isSubmitting, submitCount } = useForm({
  validationSchema: computed(() => toTypedSchema(newProjectSchema(maxBytes.value))),
  initialValues: { name: '', sourceLanguage: 'auto' },
})
// Show a field's error only after the user picked a file or tried to submit, not on first load
// (loading the upload limit changes the schema, and vee-validate re-validates when that happens).
const filePicked = ref(false)
const errors = computed(() => {
  const e = allErrors.value
  if (submitCount.value > 0) return e
  return filePicked.value ? { file: e.file } : {}
})
const [name, nameAttrs] = defineField('name')
const [sourceLanguage, languageAttrs] = defineField('sourceLanguage')

const needed = computed(() => {
  const s = storage.value
  return s && values.file ? values.file.size + s.headroomBytes + s.minFreeBytes : null
})

function pick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  // A cleared input leaves the field empty on purpose: validation then shows "Choose a movie file".
  setFieldValue('file', file as File)
  filePicked.value = true
  if (file && !values.name) {
    setFieldValue('name', file.name.replace(/\.[^.]+$/, '').replace(/[._]+/g, ' ').trim())
  }
}

const submit = handleSubmit(async (form) => {
  submitError.value = null
  try {
    const detail = await projects.create(form.name, form.sourceLanguage)
    createdProjectId.value = detail.summary.id
    pendingFile.value = form.file // MovieUpload starts streaming immediately
  } catch (e) {
    submitError.value = (e as Error).message
  }
})

function uploaded() {
  navigateTo(`/projects/${createdProjectId.value}`)
}
</script>

<template>
  <div style="max-width: 44rem">
    <h1 class="h4 mb-3"><Icon name="tabler:file-import" /> Import Movie</h1>

    <BCard v-if="!pendingFile">
      <form novalidate @submit.prevent="submit">
        <div class="mb-3">
          <label class="form-label" for="movie">Movie file</label>
          <input id="movie" class="form-control" type="file" accept=".mp4,.mkv,.mov,video/mp4,video/x-matroska,video/quicktime"
                 :class="{ 'is-invalid': errors.file }" @change="pick">
          <div class="invalid-feedback">{{ errors.file }}</div>
          <div class="form-text">MP4, MKV or MOV, up to 180 minutes and {{ formatBytes(maxBytes) }}. The original file is copied and never modified.</div>
        </div>
        <div class="mb-3">
          <label class="form-label" for="name">Project name</label>
          <input id="name" v-model="name" v-bind="nameAttrs" class="form-control" :class="{ 'is-invalid': errors.name }">
          <div class="invalid-feedback">{{ errors.name }}</div>
        </div>
        <div class="row g-3 mb-3">
          <div class="col-sm-6">
            <label class="form-label" for="lang">Source language</label>
            <select id="lang" v-model="sourceLanguage" v-bind="languageAttrs" class="form-select" :class="{ 'is-invalid': errors.sourceLanguage }">
              <option v-for="l in SOURCE_LANGUAGES" :key="l.value" :value="l.value">{{ l.label }}</option>
            </select>
            <div class="invalid-feedback">{{ errors.sourceLanguage }}</div>
            <div class="form-text">Automatic detection arrives with transcription (M3).</div>
          </div>
          <div class="col-sm-6">
            <label class="form-label">Output language</label>
            <input class="form-control" value="Khmer (Cambodia)" disabled>
          </div>
        </div>

        <div class="border rounded p-2 small mb-3 bg-body-tertiary">
          <div class="d-flex justify-content-between"><span>Free disk space ({{ storage?.root ?? '…' }})</span><strong>{{ formatBytes(storage?.freeBytes) }}</strong></div>
          <div v-if="needed" class="d-flex justify-content-between"><span>Needed (movie + working space + minimum free)</span>
            <strong :class="{ 'text-danger': storage && needed > storage.freeBytes }">{{ formatBytes(needed) }}</strong></div>
          <div class="d-flex justify-content-between"><span>Processing profile</span><span>Balanced (CPU, 10-minute audio segments)</span></div>
          <div v-if="storageError" class="text-danger">{{ storageError }}</div>
        </div>

        <BAlert :model-value="!!submitError" variant="danger" class="small">{{ submitError }}</BAlert>
        <BButton type="submit" variant="primary" :disabled="isSubmitting || !storage">
          <BSpinner v-if="isSubmitting" small /> Create project and upload
        </BButton>
      </form>
    </BCard>

    <BCard v-else-if="createdProjectId && storage">
      <h2 class="h6">Uploading to “{{ values.name }}”</h2>
      <MovieUpload :project-id="createdProjectId" :storage="storage" :file="pendingFile" auto-start
                   @uploaded="uploaded" />
      <p class="small text-body-secondary mt-2 mb-0">
        If the upload fails, the project is kept: <NuxtLink :to="`/projects/${createdProjectId}`">open it</NuxtLink> to try again.
      </p>
    </BCard>
  </div>
</template>
