<script setup lang="ts">
const props = defineProps<{ value: number; variant?: string; animated?: boolean; height?: string }>()
const pct = computed(() => Math.max(0, Math.min(100, props.value || 0)))
</script>

<template>
  <div class="progress" role="progressbar" :aria-valuenow="pct" aria-valuemin="0" aria-valuemax="100"
       :style="{ height: height ?? '1rem' }">
    <div class="progress-bar" :class="[`bg-${variant ?? 'primary'}`, { 'progress-bar-striped progress-bar-animated': animated }]"
         :style="{ width: `${pct}%` }">
      <span v-if="!height || height !== '0.4rem'">{{ pct.toFixed(0) }}%</span>
    </div>
  </div>
</template>
