<script setup lang="ts">
import type { RevisionView } from '~/types/api'

/**
 * Shown on an editor page when the revision it shows is no longer the one in use. The page is read-only then; text the
 * user typed but did not save is listed so it can be copied, and stays listed (until dismissed) after opening the
 * revision now in use. Nothing is ever copied between revisions automatically.
 */
const props = defineProps<{ switched: boolean; shownNo: number | null; inUse: RevisionView | null; unsaved?: string[] }>()
const emit = defineEmits<{ open: [] }>()

const leftover = ref<string[]>([])
const leftoverFrom = ref<number | null>(null)

function open() {
  if (props.unsaved?.length) {
    leftover.value = [...props.unsaved]
    leftoverFrom.value = props.shownNo
  }
  emit('open')
}
</script>

<template>
  <div>
    <BAlert :model-value="switched" variant="warning" class="small py-2 kd-revision-switched">
      <div class="d-flex align-items-start gap-2">
        <Icon name="tabler:lock" class="mt-1" />
        <div class="flex-grow-1">
          <strong>Read-only: revision {{ shownNo ?? '' }} is no longer in use.</strong>
          <template v-if="inUse"> Revision {{ inUse.revisionNo }} was put in use (on the Overview page, maybe in another tab).</template>
          <template v-else> No revision is in use now.</template>
          Only the revision in use can be edited. Nothing you saved is lost: revision {{ shownNo ?? '' }} keeps its own
          review work and can be used again from the Overview page.
          <div v-if="unsaved?.length" class="mt-2 kd-unsaved">
            <strong>Not saved</strong> (copy anything you still need):
            <ul class="mb-0 kd-mono">
              <li v-for="(u, i) in unsaved" :key="i">{{ u }}</li>
            </ul>
          </div>
        </div>
        <BButton v-if="inUse" size="sm" variant="warning" class="kd-open-in-use text-nowrap" @click="open">
          Open revision {{ inUse.revisionNo }}
        </BButton>
      </div>
    </BAlert>
    <BAlert :model-value="!!leftover.length" variant="secondary" class="small py-2 kd-leftover">
      <strong>Unsaved text from revision {{ leftoverFrom }}</strong> — it was not saved anywhere; copy it if you still need it:
      <ul class="mb-1 kd-mono">
        <li v-for="(u, i) in leftover" :key="i">{{ u }}</li>
      </ul>
      <BButton size="sm" variant="outline-secondary" class="kd-dismiss-leftover" @click="leftover = []">I no longer need it</BButton>
    </BAlert>
  </div>
</template>
