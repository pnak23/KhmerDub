import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ProjectDetail, ProjectSummary } from '~/types/api'
import { api } from '~/utils/api'

export const useProjectsStore = defineStore('projects', () => {
  const list = ref<ProjectSummary[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const includeArchived = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      list.value = await api<ProjectSummary[]>('/projects', { query: { includeArchived: includeArchived.value } })
      error.value = null
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  /** Apply a live update from /topic/projects (insert, replace, or drop if archived and hidden). */
  function applySummary(summary: ProjectSummary) {
    const others = list.value.filter((p) => p.id !== summary.id)
    if (summary.archived && !includeArchived.value) {
      list.value = others
      return
    }
    list.value = [summary, ...others].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
  }

  async function create(name: string, sourceLanguage: string): Promise<ProjectDetail> {
    const detail = await api<ProjectDetail>('/projects', { method: 'POST', body: { name, sourceLanguage } })
    applySummary(detail.summary)
    return detail
  }

  return { list, loading, error, includeArchived, fetchList, applySummary, create }
})
