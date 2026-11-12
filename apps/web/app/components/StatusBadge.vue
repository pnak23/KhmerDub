<script setup lang="ts">
const props = defineProps<{ status: string | null | undefined }>()
</script>

<template>
  <BBadge :variant="statusVariant(props.status)">{{ statusLabel(props.status) }}</BBadge>
</template>
