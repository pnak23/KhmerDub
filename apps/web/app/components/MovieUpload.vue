<script setup lang="ts">
import type { StorageInfo, UploadResult } from '~/types/api'
import type { UploadHandle } from '~/utils/upload'

/** File picker + checks + streamed upload with progress. Used on the Import page and on a project without a movie. */
const props = defineProps<{ projectId: string; storage: StorageInfo; file?: File | null; autoStart?: boolean }>()
const emit = defineEmits<{ uploaded: [UploadResult]; failed: [string] }>()

const config = useRuntimeConfig()
const selected = ref<File | null>(props.file ?? null)
const fileError = ref<string | null>(null)
const uploadError = ref<string | null>(null)
const loaded = ref(0)
const startedAt = ref(0)
const handle = shallowRef<UploadHandle | null>(null)

const percent = computed(() => (selected.value ? (loaded.value / selected.value.size) * 100 : 0))
const speed = computed(() => {
  const seconds = (performance.now() - startedAt.value) / 1000
  return seconds > 0.5 ? loaded.value / seconds : 0
})

function pick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  selected.value = file
  const check = movieFileSchema(props.storage.maxUploadBytes).safeParse(file)
  fileError.value = check.success ? null : check.error.issues[0]?.message ?? 'Invalid file'
}

async function start() {
  const file = selected.value
  if (!file || fileError.value) return
  uploadError.value = null
  try {
    const mismatch = await sniffMovie(file)
    if (mismatch) {
      uploadError.value = mismatch
      emit('failed', mismatch)
      return
    }
    // Check name, size and free space on the server before sending any bytes.
    await api(`/projects/${props.projectId}/upload/check`, { method: 'POST', body: { fileName: file.name, sizeBytes: file.size } })
    loaded.value = 0
    startedAt.value = performance.now()
    handle.value = uploadMovie(`${config.public.apiDirectUrl}/api/projects/${props.projectId}/upload`, file,
      (done) => (loaded.value = done))
    emit('uploaded', await handle.value.promise)
  } catch (e) {
    uploadError.value = (e as Error).message
    emit('failed', uploadError.value)
  } finally {
    handle.value = null
  }
}

onMounted(() => {
  if (props.autoStart && selected.value) start()
})
defineExpose({ start })
</script>

<template>
  <div>
    <div v-if="!autoStart" class="mb-2">
      <input class="form-control" type="file" accept=".mp4,.mkv,.mov,video/mp4,video/x-matroska,video/quicktime"
             :class="{ 'is-invalid': fileError }" :disabled="!!handle" @change="pick">
      <div class="invalid-feedback">{{ fileError }}</div>
    </div>
    <div v-if="selected && !fileError" class="small text-body-secondary mb-2">
      {{ selected.name }} · {{ formatBytes(selected.size) }}
    </div>
    <div v-if="handle" class="mb-2">
      <ProgressBar :value="percent" animated />
      <div class="d-flex justify-content-between small text-body-secondary mt-1">
        <span>{{ formatBytes(loaded) }} of {{ formatBytes(selected?.size) }}</span>
        <span v-if="speed">{{ formatBytes(speed) }}/s</span>
      </div>
      <BButton size="sm" variant="outline-secondary" class="mt-2" @click="handle?.abort()">Cancel upload</BButton>
    </div>
    <BAlert :model-value="!!uploadError" variant="danger" class="small py-2">{{ uploadError }}</BAlert>
    <BButton v-if="!autoStart && !handle" variant="primary" :disabled="!selected || !!fileError" @click="start">
      <Icon name="tabler:upload" /> Upload movie
    </BButton>
  </div>
</template>
