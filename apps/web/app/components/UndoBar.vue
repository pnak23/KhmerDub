<script setup lang="ts">
import type { OperationResult, OperationView } from '~/types/api'
import { ApiError } from '~/utils/api'

/**
 * The latest review changes; only the newest one can be undone (changes are undone last-first). Undo always targets
 * the change the user sees: if the history moved on (another change was saved meanwhile, here or in another tab),
 * nothing is undone and the user is told to look again.
 */
const props = defineProps<{ projectId: string; busy?: boolean }>()
const emit = defineEmits<{ undone: [result: OperationResult]; reload: [] }>()

const operations = ref<OperationView[]>([])
const working = ref(false)
const error = ref<ApiError | null>(null)

const latest = computed(() => operations.value.find((o) => o.undoable) ?? null)

async function refresh() {
  operations.value = await api<OperationView[]>(`/projects/${props.projectId}/review-operations`, { query: { limit: 15 } })
    .catch(() => [])
}

async function undo() {
  const shown = latest.value
  if (!shown || working.value || props.busy) return
  working.value = true
  error.value = null
  try {
    await refresh()
    if (latest.value?.id !== shown.id) {
      error.value = new ApiError({ status: 409, code: 'UNDO_ORDER', detail: `Nothing was undone: the last change is now "${
        latest.value?.summary ?? 'none'}". Check it and press Undo again.` })
      return
    }
    const result = await api<OperationResult>(`/projects/${props.projectId}/review-operations/${shown.id}/undo`, { method: 'POST' })
    emit('undone', result)
  } catch (e) {
    error.value = toApiError(e)
  } finally {
    working.value = false
    await refresh()
  }
}

onMounted(refresh)
defineExpose({ refresh, undo })
</script>

<template>
  <div class="kd-undo-bar">
    <div class="d-flex align-items-center gap-2 small">
      <Icon name="tabler:history" />
      <span v-if="latest" class="text-truncate kd-last-change">Last change: <strong>{{ latest.summary }}</strong></span>
      <span v-else class="text-body-secondary">No changes to undo.</span>
      <BButton size="sm" variant="outline-secondary" class="ms-auto kd-undo" :disabled="!latest || working || busy" title="Ctrl+Alt+Z"
               :data-op="latest?.id" @click="undo">
        <BSpinner v-if="working" small /><Icon v-else name="tabler:arrow-back-up" /> Undo
      </BButton>
      <BDropdown size="sm" variant="link" text="History" :disabled="!operations.length" end>
        <BDropdownItem v-for="o in operations" :key="o.id" disabled>
          <span :class="{ 'text-decoration-line-through': o.undoneAt }">#{{ o.seq }} {{ o.summary }}</span>
          <small class="text-body-secondary"> · {{ fromNow(o.createdAt) }}</small>
        </BDropdownItem>
      </BDropdown>
    </div>
    <ReviewError :error="error" @reload="emit('reload'); error = null" @dismiss="error = null" />
  </div>
</template>
