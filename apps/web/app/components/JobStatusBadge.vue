<script setup lang="ts">
defineProps<{ status: string | null | undefined }>()
</script>

<template>
  <BBadge :variant="jobVariant(status)">{{ humanize(status) }}</BBadge>
</template>
