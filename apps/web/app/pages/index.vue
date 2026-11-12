<script setup lang="ts">
const system = useSystemStore()

const cloudEnabled = computed(() => system.health?.components.aiService?.details?.cloudEnabled === true)
</script>

<template>
  <div>
    <h1 class="h4 mb-3">Dashboard</h1>

    <div class="row g-3">
      <div class="col-lg-8">
        <BCard>
          <h2 class="h6 mb-2">Recent projects</h2>
          <p class="text-body-secondary mb-0">
            Projects and their processing jobs appear here from M2 (import and job engine).
            No project data exists yet.
          </p>
        </BCard>
      </div>

      <div class="col-lg-4">
        <BCard>
          <div class="d-flex justify-content-between align-items-center mb-2">
            <h2 class="h6 mb-0">System</h2>
            <StatusBadge :status="system.overall" />
          </div>
          <p v-if="system.error" class="text-danger small mb-2">{{ system.error }}</p>
          <dl class="kd-dl small mb-3">
            <dt>Cloud processing</dt>
            <dd>{{ cloudEnabled ? 'Enabled' : 'Disabled (local only)' }}</dd>
            <dt>Last check</dt>
            <dd>{{ fromNow(system.lastUpdated) }}</dd>
          </dl>
          <NuxtLink to="/system" class="btn btn-sm btn-outline-secondary">Open System Status</NuxtLink>
        </BCard>
      </div>
    </div>
  </div>
</template>
