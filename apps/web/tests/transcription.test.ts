import { describe, expect, it } from 'vitest'
import { needsReprocessConfirmation, transcriptionSchema } from '~/utils/schemas'
import { ApiError } from '~/utils/api'

describe('transcription request', () => {
  it('accepts Quality and Fast with auto or a language code', () => {
    expect(transcriptionSchema.safeParse({ mode: 'QUALITY', language: 'auto' }).success).toBe(true)
    expect(transcriptionSchema.safeParse({ mode: 'FAST', language: 'zh' }).success).toBe(true)
  })

  it('rejects unknown modes and malformed languages', () => {
    expect(transcriptionSchema.safeParse({ mode: 'TURBO', language: 'zh' }).success).toBe(false)
    expect(transcriptionSchema.safeParse({ mode: 'QUALITY', language: 'Chinese' }).success).toBe(false)
  })

  it('recognises the API asking for reprocess confirmation', () => {
    const err = new ApiError({ status: 409, code: 'REPROCESS_CONFIRMATION_REQUIRED', detail: 'confirm' })
    expect(needsReprocessConfirmation(err.code)).toBe(true)
    expect(needsReprocessConfirmation('JOB_ALREADY_ACTIVE')).toBe(false)
    expect(needsReprocessConfirmation(undefined)).toBe(false)
  })
})
