<script setup lang="ts">
import type { ProjectSummary } from '~/types/api'

const system = useSystemStore()
const projects = useProjectsStore()
const jobs = useJobsStore()
const { $live } = useNuxtApp()
const actionError = ref<string | null>(null)

const cloudEnabled = computed(() => system.health?.components.aiService?.details?.cloudEnabled === true)
const failedCount = computed(() => projects.list.filter((p) => p.lastJob?.status === 'FAILED').length)

let unsubscribe: (() => void) | null = null
onMounted(() => {
  projects.fetchList()
  unsubscribe = $live.subscribe('/topic/projects', (body) => projects.applySummary(body as ProjectSummary))
})
onBeforeUnmount(() => unsubscribe?.())
watch(() => projects.includeArchived, () => projects.fetchList())

async function act(jobId: string, action: 'resume' | 'retry') {
  actionError.value = null
  try {
    await jobs.act(jobId, action)
  } catch (e) {
    actionError.value = (e as Error).message
  }
}
</script>

<template>
  <div>
    <div class="d-flex align-items-center mb-3">
      <h1 class="h4 mb-0">Dashboard</h1>
      <NuxtLink to="/import" class="btn btn-primary btn-sm ms-auto"><Icon name="tabler:plus" /> New project</NuxtLink>
    </div>

    <div class="row g-3">
      <div class="col-xl-9">
        <BCard>
          <div class="d-flex align-items-center mb-2">
            <h2 class="h6 mb-0">Projects</h2>
            <BBadge v-if="failedCount" variant="danger" class="ms-2">{{ failedCount }} failed</BBadge>
            <div class="form-check form-switch ms-auto small">
              <input id="show-archived" v-model="projects.includeArchived" class="form-check-input" type="checkbox">
              <label class="form-check-label" for="show-archived">Show archived</label>
            </div>
          </div>
          <BAlert :model-value="!!projects.error" variant="danger" class="small">{{ projects.error }}</BAlert>
          <BAlert :model-value="!!actionError" variant="warning" class="small">{{ actionError }}</BAlert>
          <p v-if="!projects.loading && !projects.list.length" class="text-body-secondary mb-0">
            No projects yet. <NuxtLink to="/import">Import a movie</NuxtLink> to start.
          </p>
          <div v-else class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead class="small text-body-secondary">
                <tr><th>Project</th><th>Duration</th><th>Status</th><th style="width: 32%">Processing</th><th /></tr>
              </thead>
              <tbody>
                <tr v-for="p in projects.list" :key="p.id">
                  <td>
                    <NuxtLink :to="`/projects/${p.id}`" class="fw-medium text-decoration-none">{{ p.name }}</NuxtLink>
                    <BBadge v-if="p.archived" variant="light" class="text-dark ms-1">archived</BBadge>
                    <div class="small text-body-secondary text-truncate" style="max-width: 22rem">{{ p.originalFilename ?? 'No movie yet' }}</div>
                  </td>
                  <td class="text-nowrap">{{ formatDuration(p.durationMs) }}</td>
                  <td><BBadge variant="secondary">{{ humanize(p.status) }}</BBadge></td>
                  <td>
                    <template v-if="p.activeJob">
                      <div class="d-flex justify-content-between small mb-1">
                        <span>{{ humanize(p.activeJob.type) }} · {{ humanize(p.activeJob.currentStage) }}</span>
                        <JobStatusBadge :status="p.activeJob.status" />
                      </div>
                      <ProgressBar :value="p.activeJob.progress" :variant="jobVariant(p.activeJob.status)"
                                   :animated="p.activeJob.status === 'RUNNING'" height="0.4rem" />
                    </template>
                    <template v-else-if="p.lastJob">
                      <span class="small">{{ humanize(p.lastJob.type) }}: </span><JobStatusBadge :status="p.lastJob.status" />
                      <div v-if="p.lastJob.errorMessage" class="small text-danger text-truncate" style="max-width: 20rem"
                           :title="p.lastJob.errorMessage">{{ p.lastJob.errorMessage }}</div>
                    </template>
                    <span v-else class="small text-body-secondary">—</span>
                  </td>
                  <td class="text-end text-nowrap">
                    <BButton v-if="p.activeJob?.status === 'PAUSED'" size="sm" variant="outline-primary" class="me-1"
                             @click="act(p.activeJob.id, 'resume')">Resume</BButton>
                    <BButton v-if="!p.activeJob && p.lastJob?.status === 'FAILED'" size="sm" variant="outline-primary"
                             class="me-1" @click="act(p.lastJob.id, 'retry')">Retry</BButton>
                    <NuxtLink :to="`/projects/${p.id}`" class="btn btn-sm btn-outline-secondary">Open</NuxtLink>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </BCard>
      </div>

      <div class="col-xl-3">
        <BCard>
          <div class="d-flex justify-content-between align-items-center mb-2">
            <h2 class="h6 mb-0">System</h2>
            <StatusBadge :status="system.overall" />
          </div>
          <p v-if="system.error" class="text-danger small mb-2">{{ system.error }}</p>
          <dl class="kd-dl small mb-3">
            <dt>Cloud</dt>
            <dd>{{ cloudEnabled ? 'Enabled' : 'Disabled (local only)' }}</dd>
            <dt>Free disk</dt>
            <dd>{{ formatBytes(system.health?.components.storage?.details?.freeBytes as number) }}</dd>
            <dt>Checked</dt>
            <dd>{{ fromNow(system.lastUpdated) }}</dd>
          </dl>
          <NuxtLink to="/system" class="btn btn-sm btn-outline-secondary">System Status</NuxtLink>
        </BCard>
      </div>
    </div>
  </div>
</template>
