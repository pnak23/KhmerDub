<script setup lang="ts">
const props = defineProps<{ projectId: string; current: 'overview' | 'speakers' | 'dialogue' | 'glossary'; title?: string }>()

const tabs = computed(() => [
  { key: 'overview', label: 'Overview', icon: 'tabler:movie', to: `/projects/${props.projectId}` },
  { key: 'speakers', label: 'Speakers', icon: 'tabler:users', to: `/projects/${props.projectId}/speakers` },
  { key: 'dialogue', label: 'Dialogue', icon: 'tabler:messages', to: `/projects/${props.projectId}/dialogue` },
  { key: 'glossary', label: 'Glossary', icon: 'tabler:book', to: `/projects/${props.projectId}/glossary` },
])
</script>

<template>
  <div class="mb-3">
    <div class="small mb-1"><NuxtLink to="/">Dashboard</NuxtLink><template v-if="title"> / {{ title }}</template></div>
    <ul class="nav nav-tabs kd-review-nav">
      <li v-for="t in tabs" :key="t.key" class="nav-item">
        <NuxtLink :to="t.to" class="nav-link" :class="{ active: t.key === current }" :data-tab="t.key">
          <Icon :name="t.icon" /> {{ t.label }}
        </NuxtLink>
      </li>
    </ul>
  </div>
</template>
