import type { RevisionView } from '~/types/api'

/**
 * Only the revision in use can be edited. An editor page tells the guard which revision it shows; when another revision
 * is put in use (on the Overview page, possibly in another tab or window), the guard notices: when the page gets focus
 * again, when the server refuses a change as read-only, or when new data arrives for a different revision. The page
 * then turns read-only and keeps what it shows, including unsaved text, until the user chooses to open the revision now
 * in use. It never reloads to the other revision on its own.
 */
export function useRevisionGuard(projectId: Ref<string>) {
  const shownId = ref<string | null>(null)
  const revisions = ref<RevisionView[]>([])
  const switched = ref(false)

  const inUse = computed(() => revisions.value.find((r) => r.status === 'ACTIVE') ?? null)
  const shownNo = computed(() => revisions.value.find((r) => r.id === shownId.value)?.revisionNo ?? null)

  let inFlight: Promise<boolean> | null = null

  /** Re-read which revision is in use. True when it is no longer the one shown (the page is then read-only). */
  function check(): Promise<boolean> {
    inFlight ??= (async () => {
      try {
        const list = await api<RevisionView[]>(`/projects/${projectId.value}/dialogue-revisions`).catch(() => null)
        if (list) revisions.value = list
        if (list && shownId.value && inUse.value?.id !== shownId.value) switched.value = true
        return switched.value
      } finally {
        inFlight = null
      }
    })()
    return inFlight
  }

  /**
   * The page loaded data of revision {@code revisionId}. The first time this is remembered; afterwards, data of another
   * revision means the revision was switched under the page: returns false and the page must keep what it shows.
   */
  async function track(revisionId: string, list?: RevisionView[]): Promise<boolean> {
    if (switched.value) return false
    if (shownId.value && shownId.value !== revisionId) {
      switched.value = true
      await check()
      return false
    }
    if (!shownId.value) {
      shownId.value = revisionId
      if (list) revisions.value = list
      else await check()
    }
    return true
  }

  /** The user chose to open the revision now in use: forget the old one (the page then loads and tracks again). */
  function accept() {
    shownId.value = null
    switched.value = false
  }

  function onFocus() {
    if (shownId.value) check()
  }
  function onVisible() {
    if (document.visibilityState === 'visible') onFocus()
  }
  onMounted(() => {
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisible)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('focus', onFocus)
    document.removeEventListener('visibilitychange', onVisible)
  })

  return { switched: readonly(switched), inUse, shownNo, shownId: readonly(shownId), check, track, accept }
}
