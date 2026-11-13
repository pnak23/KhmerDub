import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { EventView, JobView } from '~/types/api'
import { api } from '~/utils/api'

export type JobAction = 'pause' | 'resume' | 'cancel' | 'retry'

export const useJobsStore = defineStore('jobs', () => {
  const jobs = ref<Record<string, JobView>>({})
  const events = ref<Record<string, EventView[]>>({})

  /** Keep the newest snapshot: live updates and HTTP responses can arrive in any order. */
  function apply(view: JobView): boolean {
    const current = jobs.value[view.id]
    if (current && current.version > view.version) return false
    jobs.value = { ...jobs.value, [view.id]: view }
    return true
  }

  async function fetch(id: string) {
    apply(await api<JobView>(`/jobs/${id}`))
  }

  async function fetchEvents(id: string, limit = 50) {
    events.value = { ...events.value, [id]: await api<EventView[]>(`/jobs/${id}/events`, { query: { limit } }) }
  }

  async function act(id: string, action: JobAction) {
    apply(await api<JobView>(`/jobs/${id}/${action}`, { method: 'POST' }))
  }

  return { jobs, events, apply, fetch, fetchEvents, act }
})
