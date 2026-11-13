<script setup lang="ts">
import type { ChunkView } from '~/types/api'

const props = defineProps<{ chunks: ChunkView[] }>()

const byStage = computed(() => {
  const groups = new Map<string, ChunkView[]>()
  for (const c of props.chunks) groups.set(c.stage, [...(groups.get(c.stage) ?? []), c])
  return [...groups]
})

function tooltip(c: ChunkView): string {
  const span = c.startMs === null ? '' : ` ${formatDuration(c.startMs)}–${formatDuration(c.endMs)}`
  const tries = c.attempts.length ? `, ${c.attempts.length} attempt(s)` : ''
  const err = c.error ? `\n${c.error}` : ''
  return `#${c.index}${span}: ${humanize(c.status)} ${Math.round(c.progress)}%${tries}${err}`
}
</script>

<template>
  <div>
    <div v-for="[stage, list] in byStage" :key="stage" class="mb-2">
      <div class="small text-body-secondary mb-1">{{ humanize(stage) }}</div>
      <div class="d-flex flex-wrap gap-1">
        <div v-for="c in list" :key="c.id" class="chunk border rounded text-center small"
             :class="`border-${chunkVariant(c.status)}`" :title="tooltip(c)">
          <div class="chunk-fill" :class="`bg-${chunkVariant(c.status)}`" :style="{ height: `${c.progress}%` }" />
          <span class="chunk-label">{{ c.index }}</span>
          <span v-if="c.attempts.length > 1" class="chunk-retry text-danger" title="Retried">↻</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.chunk {
  position: relative;
  width: 2.25rem;
  height: 2.25rem;
  overflow: hidden;
  background: var(--bs-body-bg);
}

.chunk-fill {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  opacity: 0.35;
  transition: height 0.4s ease;
}

.chunk-label {
  position: relative;
  line-height: 2.25rem;
  font-weight: 500;
}

.chunk-retry {
  position: absolute;
  top: -2px;
  right: 2px;
  font-size: 0.7rem;
}
</style>
