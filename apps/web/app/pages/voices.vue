<script setup lang="ts">
import { useForm } from 'vee-validate'
import type { VoiceCategory, VoiceProfile } from '~/types/api'
import type { ApiError } from '~/utils/api'

/**
 * Minimal local voice library (M3b-2): names and categories to assign to speakers. There is no TTS provider yet
 * (provider NONE), so there is no audio preview and nothing is sent anywhere. Deactivating keeps the profile.
 */
const voices = ref<VoiceProfile[]>([])
const includeInactive = ref(false)
const error = ref<ApiError | null>(null)
const notice = ref<string | null>(null)
const editing = ref<VoiceProfile | null>(null)
const busy = ref(false)

const { handleSubmit, errors, defineField, resetForm } = useForm({
  validationSchema: toTypedSchema(voiceProfileSchema),
  initialValues: { displayName: '', voiceCategory: 'ADULT_MALE' as VoiceCategory, language: 'km-KH', style: '' },
})
const [displayName] = defineField('displayName')
const [voiceCategory] = defineField('voiceCategory')
const [language] = defineField('language')
const [style] = defineField('style')

async function load() {
  try {
    voices.value = await api<VoiceProfile[]>('/voice-profiles', { query: { includeInactive: includeInactive.value } })
  } catch (e) {
    error.value = toApiError(e)
  }
}

function startEdit(v: VoiceProfile | null) {
  editing.value = v
  resetForm({ values: v
    ? { displayName: v.displayName, voiceCategory: v.voiceCategory, language: v.language, style: v.style ?? '' }
    : { displayName: '', voiceCategory: 'ADULT_MALE', language: 'km-KH', style: '' } })
}

const save = handleSubmit(async (form) => {
  busy.value = true
  error.value = null
  try {
    if (editing.value) {
      await api<VoiceProfile>(`/voice-profiles/${editing.value.id}`, {
        method: 'PATCH', body: { expectedUpdatedAt: editing.value.updatedAt, ...form },
      })
      notice.value = `Saved "${form.displayName}".`
    } else {
      await api<VoiceProfile>('/voice-profiles', { method: 'POST', body: form })
      notice.value = `Added "${form.displayName}".`
    }
    startEdit(null)
    await load()
  } catch (e) {
    error.value = toApiError(e)
  } finally {
    busy.value = false
  }
})

async function setActive(v: VoiceProfile, active: boolean) {
  error.value = null
  try {
    if (active) {
      await api<VoiceProfile>(`/voice-profiles/${v.id}`, { method: 'PATCH', body: { expectedUpdatedAt: v.updatedAt, active: true } })
    } else {
      await api<VoiceProfile>(`/voice-profiles/${v.id}`, { method: 'DELETE', query: { expectedUpdatedAt: v.updatedAt } })
    }
    notice.value = `${active ? 'Activated' : 'Deactivated'} "${v.displayName}".`
    await load()
  } catch (e) {
    error.value = toApiError(e)
  }
}

watch(includeInactive, load)
onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex align-items-center gap-2 mb-2">
      <h1 class="h4 mb-0">Voice Studio</h1>
      <BBadge variant="light" class="text-dark">Local library</BBadge>
    </div>
    <BAlert :model-value="true" variant="info" class="small py-2">
      Voices here are names and categories you assign to speakers. Text-to-speech, voice preview and settings arrive in a
      later milestone; until then no provider is connected (provider <span class="kd-mono">NONE</span>) and nothing leaves this PC.
    </BAlert>
    <ReviewError :error="error" @reload="load(); error = null" @dismiss="error = null" />
    <BAlert :model-value="!!notice" variant="success" class="small py-2 kd-notice" dismissible @update:model-value="notice = null">{{ notice }}</BAlert>

    <div class="row g-3">
      <div class="col-xl-8">
        <BCard>
          <div class="d-flex align-items-center mb-2">
            <h2 class="h6 mb-0">Voices</h2>
            <div class="form-check form-switch ms-auto small">
              <input id="show-inactive" v-model="includeInactive" class="form-check-input" type="checkbox">
              <label class="form-check-label" for="show-inactive">Show deactivated</label>
            </div>
          </div>
          <p v-if="!voices.length" class="small text-body-secondary mb-0">No voices yet.</p>
          <table v-else class="table table-sm small align-middle mb-0 kd-voices">
            <thead><tr><th>Name</th><th>Category</th><th>Language</th><th>Style</th><th>Provider</th><th>Used by</th><th /></tr></thead>
            <tbody>
              <tr v-for="v in voices" :key="v.id" :data-voice="v.displayName" :class="{ 'text-body-secondary': !v.active }">
                <td><strong>{{ v.displayName }}</strong> <BBadge v-if="!v.active" variant="secondary">deactivated</BBadge></td>
                <td>{{ humanize(v.voiceCategory) }}</td>
                <td>{{ v.language }}</td>
                <td>{{ v.style ?? '—' }}</td>
                <td class="kd-mono kd-provider">{{ v.provider }}</td>
                <td>{{ v.usedBySpeakers }} speaker(s)</td>
                <td class="text-end text-nowrap">
                  <BButton size="sm" variant="link" class="kd-edit-voice" @click="startEdit(v)">Edit</BButton>
                  <BButton v-if="v.active" size="sm" variant="link" class="text-danger kd-deactivate" @click="setActive(v, false)">Deactivate</BButton>
                  <BButton v-else size="sm" variant="link" class="kd-activate" @click="setActive(v, true)">Activate</BButton>
                </td>
              </tr>
            </tbody>
          </table>
        </BCard>
      </div>
      <div class="col-xl-4">
        <BCard>
          <h2 class="h6">{{ editing ? `Edit "${editing.displayName}"` : 'Add a voice' }}</h2>
          <form class="small" novalidate @submit.prevent="save">
            <label class="form-label mb-0" for="v-name">Name</label>
            <input id="v-name" v-model="displayName" class="form-control form-control-sm kd-voice-name" maxlength="150">
            <div v-if="errors.displayName" class="text-danger">{{ errors.displayName }}</div>
            <label class="form-label mb-0 mt-1" for="v-cat">Category</label>
            <select id="v-cat" v-model="voiceCategory" class="form-select form-select-sm kd-voice-category">
              <option v-for="c in VOICE_CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</option>
            </select>
            <label class="form-label mb-0 mt-1" for="v-lang">Language</label>
            <input id="v-lang" v-model="language" class="form-control form-control-sm" maxlength="10">
            <div v-if="errors.language" class="text-danger">{{ errors.language }}</div>
            <label class="form-label mb-0 mt-1" for="v-style">Style (notes)</label>
            <input id="v-style" v-model="style" class="form-control form-control-sm" maxlength="100">
            <div class="d-flex gap-2 mt-2">
              <BButton type="submit" size="sm" variant="primary" class="kd-save-voice" :disabled="busy">{{ editing ? 'Save' : 'Add' }}</BButton>
              <BButton v-if="editing" size="sm" variant="outline-secondary" @click="startEdit(null)">Cancel</BButton>
            </div>
          </form>
        </BCard>
      </div>
    </div>
  </div>
</template>
