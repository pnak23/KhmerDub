<script setup lang="ts">
defineProps<{ title: string; icon: string; milestone: string; planned: string[] }>()
</script>

<template>
  <div>
    <h1 class="h4 mb-3 d-flex align-items-center gap-2">
      <Icon :name="icon" />
      {{ title }}
    </h1>
    <BAlert :model-value="true" variant="secondary">
      <strong>Not built yet.</strong> This screen is planned for {{ milestone }}. Nothing on this page works yet.
    </BAlert>
    <BCard title="Planned">
      <ul class="mb-0">
        <li v-for="item in planned" :key="item">{{ item }}</li>
      </ul>
    </BCard>
  </div>
</template>
