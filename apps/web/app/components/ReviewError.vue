<script setup lang="ts">
import type { ApiError } from '~/utils/api'

/**
 * Shows why a change was refused. A conflict means someone else (or another tab) changed the same thing: the change
 * was NOT applied and is never retried automatically; the user reloads and decides again.
 */
const props = defineProps<{ error: ApiError | null }>()
const emit = defineEmits<{ reload: []; dismiss: [] }>()

const conflict = computed(() => isConflict(props.error))
const readOnly = computed(() => isReadOnly(props.error))
</script>

<template>
  <BAlert :model-value="!!error" :variant="conflict || readOnly ? 'warning' : 'danger'" class="small py-2 kd-review-error"
          :data-code="error?.code">
    <div class="d-flex align-items-start gap-2">
      <Icon :name="conflict ? 'tabler:git-compare' : readOnly ? 'tabler:lock' : 'tabler:alert-triangle'" class="mt-1" />
      <div class="flex-grow-1">
        <strong v-if="conflict">Not saved — this was changed elsewhere.</strong>
        <strong v-else-if="readOnly">Read-only.</strong>
        <div>{{ error?.message }}</div>
        <div v-if="conflict" class="text-body-secondary">Reload to see the current version, then make your change again.</div>
      </div>
      <BButton v-if="conflict" size="sm" variant="warning" class="kd-reload" @click="emit('reload')">Reload</BButton>
      <BButton size="sm" variant="link" class="p-0" aria-label="Dismiss" @click="emit('dismiss')"><Icon name="tabler:x" /></BButton>
    </div>
  </BAlert>
</template>
