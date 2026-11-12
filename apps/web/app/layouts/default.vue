<script setup lang="ts">
const system = useSystemStore()
const config = useRuntimeConfig()

const nav = [
  { to: '/', label: 'Dashboard', icon: 'tabler:layout-dashboard' },
  { to: '/import', label: 'Import Movie', icon: 'tabler:file-import' },
  { to: '/translation', label: 'Translation Editor', icon: 'tabler:language' },
  { to: '/voices', label: 'Voice Studio', icon: 'tabler:microphone' },
  { to: '/export', label: 'Export Studio', icon: 'tabler:movie' },
  { to: '/system', label: 'System Status', icon: 'tabler:heart-rate-monitor' },
]

onMounted(() => {
  system.fetchHealth()
})
</script>

<template>
  <div class="app-shell">
    <aside class="app-sidebar">
      <div class="brand">
        KhmerDub AI <small>v{{ config.public.appVersion }}</small>
      </div>
      <nav class="nav flex-column">
        <NuxtLink v-for="item in nav" :key="item.to" :to="item.to" class="nav-link" exact-active-class="active">
          <Icon :name="item.icon" size="18" />
          <span>{{ item.label }}</span>
        </NuxtLink>
      </nav>
      <div class="sidebar-footer">
        <NuxtLink to="/system" class="text-decoration-none d-block mb-1">
          <StatusBadge :status="system.overall" />
        </NuxtLink>
        <span>
          <Icon :name="system.liveConnected ? 'tabler:plug-connected' : 'tabler:plug-connected-x'" />
          Live updates {{ system.liveConnected ? 'on' : 'off' }}
        </span>
      </div>
    </aside>
    <main class="app-main">
      <slot />
    </main>
  </div>
</template>
