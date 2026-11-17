<script setup lang="ts">
/**
 * Plays a piece of the project's own audio (a line, or fromMs-toMs; the API caps it at 60 s). The slice is fetched
 * on demand, played from memory and never stored. Only one clip plays at a time.
 */
const props = defineProps<{ projectId: string; segmentId?: string; fromMs?: number; toMs?: number; size?: 'sm' | 'lg' }>()

const playing = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)
let audio: HTMLAudioElement | null = null
let url: string | null = null

const current = useState<{ stop: () => void } | null>('kd-line-audio', () => null)

function cleanup() {
  audio?.pause()
  audio = null
  if (url) URL.revokeObjectURL(url)
  url = null
  playing.value = false
}

function stop() {
  cleanup()
  if (current.value?.stop === stop) current.value = null
}

async function play() {
  if (playing.value) {
    stop()
    return
  }
  current.value?.stop()
  error.value = null
  loading.value = true
  try {
    const query = props.segmentId ? { segmentId: props.segmentId } : { fromMs: props.fromMs, toMs: props.toMs }
    const res = await fetch(`/api/projects/${props.projectId}/audio?${new URLSearchParams(
      Object.entries(query).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]))}`)
    if (!res.ok) {
      const body = await res.json().catch(() => null) as { detail?: string } | null
      throw new Error(body?.detail ?? `Audio not available (HTTP ${res.status})`)
    }
    if (!(res.headers.get('content-type') ?? '').startsWith('audio/wav')) throw new Error('Unexpected audio format')
    url = URL.createObjectURL(await res.blob())
    audio = new Audio(url)
    audio.onended = stop
    current.value = { stop }
    playing.value = true
    await audio.play()
  } catch (e) {
    cleanup()
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

onBeforeUnmount(stop)
defineExpose({ play, stop })
</script>

<template>
  <span class="d-inline-flex align-items-center gap-1">
    <BButton :size="size ?? 'sm'" variant="outline-secondary" class="kd-play" :title="error ?? (playing ? 'Stop' : 'Play')"
             :aria-label="playing ? 'Stop audio' : 'Play audio'" :data-playing="playing" @click="play">
      <BSpinner v-if="loading" small />
      <Icon v-else :name="playing ? 'tabler:player-stop' : 'tabler:player-play'" />
    </BButton>
    <small v-if="error" class="text-danger kd-audio-error">{{ error }}</small>
  </span>
</template>
