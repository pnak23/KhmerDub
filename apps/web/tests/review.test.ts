import { describe, expect, it } from 'vitest'
import type { LineView } from '~/types/api'
import { ApiError } from '~/utils/api'
import type { EditSummary, RevisionStatus } from '~/types/api'
import {
  applyLines, canActivate, currentChinese, describeWork, formatTimecode, hasWork, isConflict, isReadOnly, lineChanges, needsReload,
  pageCount, parseTimecode, parseVariants, REVISION_CHANGED, REVISION_STATUS, shortcutFor, splitPreview, switchFailure,
} from '~/utils/review'
import { glossaryTermSchema, lineTextSchema, speakerSchema, voiceProfileSchema } from '~/utils/schemas'

function line(over: Partial<LineView> = {}): LineView {
  return {
    id: 'a', seq: 0, startMs: 1000, endMs: 3000, speakerId: 's1', sourceText: '你好我是', sourceTextEdited: null, editedText: null,
    confidence: 0.9, speakerPurity: 1, flags: {}, reviewStatus: 'PENDING', speakerAssignment: 'AUTO', version: 0,
    words: [{ w: '你好', s: 1000, e: 1800 }, { w: '我是', s: 1800, e: 3000 }], ...over,
  }
}

const key = (k: string, mods: Partial<{ ctrlKey: boolean; altKey: boolean; shiftKey: boolean; metaKey: boolean }> = {}) =>
  ({ key: k, ctrlKey: false, altKey: false, shiftKey: false, metaKey: false, ...mods })

describe('conflicts are recognised and never treated as success', () => {
  it('maps the API codes', () => {
    for (const code of ['EDIT_CONFLICT', 'UNDO_CONFLICT', 'PREVIEW_STALE', 'UNDO_ORDER', 'ALREADY_UNDONE']) {
      expect(isConflict(new ApiError({ status: 409, code }))).toBe(true)
    }
    expect(isConflict(new ApiError({ status: 400, code: 'INVALID_TIMING' }))).toBe(false)
    expect(isConflict(null)).toBe(false)
    expect(isReadOnly(new ApiError({ status: 409, code: 'REVISION_READ_ONLY' }))).toBe(true)
  })
})

describe('line edits send only what changed, in the API convention', () => {
  it('keeps recognised text read-only and clears with ""', () => {
    const l = line({ sourceTextEdited: '你好，我是', editedText: 'សួស្តី' })
    expect(currentChinese(l)).toBe('你好，我是')
    expect(lineChanges(l, { chinese: '你好，我是', khmer: 'សួស្តី' })).toEqual({})
    expect(lineChanges(l, { chinese: '你好我是', khmer: 'សួស្តី' })).toEqual({ sourceTextEdited: '' }) // back to recognised
    expect(lineChanges(l, { chinese: '你好，我是', khmer: '' })).toEqual({ editedText: '' })
    expect(lineChanges(line(), { chinese: '您好我是', khmer: 'x' })).toEqual({ sourceTextEdited: '您好我是', editedText: 'x' })
  })

  it('applies returned lines in place and drops removed ones', () => {
    const page = [line({ id: 'a', seq: 0 }), line({ id: 'b', seq: 1 }), line({ id: 'c', seq: 2 })]
    const out = applyLines(page, { lines: [line({ id: 'a', seq: 0, editedText: 'x', version: 1 })], removedLineIds: ['b'] })
    expect(out.map((l) => l.id)).toEqual(['a', 'c'])
    expect(out[0]!.editedText).toBe('x')
    expect(needsReload('SPLIT_SEGMENT')).toBe(true)
    expect(needsReload('EDIT_SEGMENT')).toBe(false)
  })
})

describe('editor helpers', () => {
  it('pages', () => {
    expect(pageCount(761, 100)).toBe(8)
    expect(pageCount(0, 100)).toBe(1)
    expect(pageCount(200, 200)).toBe(1)
  })

  it('round-trips timecodes and rejects garbage', () => {
    expect(formatTimecode(83_456)).toBe('1:23.4')
    expect(formatTimecode(3_723_400)).toBe('1:02:03.4')
    expect(parseTimecode('1:23.4')).toBe(83_400)
    expect(parseTimecode('83.4')).toBe(83_400)
    expect(parseTimecode('1:02:03')).toBe(3_723_000)
    expect(parseTimecode('1:75')).toBeNull()
    expect(parseTimecode('abc')).toBeNull()
  })

  it('previews a split exactly at a word boundary', () => {
    expect(splitPreview(line().words, 1)).toEqual(['你好', '我是'])
  })

  it('parses glossary variants', () => {
    expect(parseVariants(' 碎碎\n穗穗，碎碎\n\n')).toEqual(['碎碎', '穗穗'])
  })

  it('maps shortcuts without stealing normal text editing', () => {
    expect(shortcutFor(key('s', { ctrlKey: true }))).toBe('save')
    expect(shortcutFor(key('Enter', { ctrlKey: true }))).toBe('saveNext')
    expect(shortcutFor(key('ArrowDown', { altKey: true }))).toBe('next')
    expect(shortcutFor(key('ArrowUp', { altKey: true }))).toBe('previous')
    expect(shortcutFor(key('A', { altKey: true }))).toBe('approve')
    expect(shortcutFor(key('p', { altKey: true }))).toBe('play')
    expect(shortcutFor(key('z', { ctrlKey: true, altKey: true }))).toBe('undo')
    expect(shortcutFor(key('z', { ctrlKey: true }))).toBeNull() // plain Ctrl+Z stays the text box's own undo
    expect(shortcutFor(key('a'))).toBeNull()
  })
})

describe('review forms', () => {
  it('validates speaker, line, glossary and voice forms', () => {
    expect(speakerSchema.safeParse({ characterName: 'Pei', description: '', suggestedCategory: '' }).success).toBe(true)
    expect(speakerSchema.safeParse({ characterName: 'x'.repeat(151), description: '', suggestedCategory: '' }).success).toBe(false)
    expect(speakerSchema.safeParse({ characterName: '', description: '', suggestedCategory: 'ROBOT' }).success).toBe(false)
    expect(lineTextSchema.safeParse({ chinese: 'x'.repeat(5001), khmer: '' }).success).toBe(false)
    expect(glossaryTermSchema.safeParse({ sourceTerm: '  ', category: 'CHARACTER', variants: '', khmerTerm: '', notes: '' }).success).toBe(false)
    expect(glossaryTermSchema.safeParse({ sourceTerm: '岁岁', category: 'PLACE', variants: '碎碎', khmerTerm: '', notes: '' }).success).toBe(true)
    expect(voiceProfileSchema.safeParse({ displayName: 'Warm', voiceCategory: 'ADULT_MALE', language: 'km-KH', style: '' }).success).toBe(true)
    expect(voiceProfileSchema.safeParse({ displayName: 'Warm', voiceCategory: 'ADULT_MALE', language: 'Khmer', style: '' }).success).toBe(false)
  })
})

describe('switching the revision in use', () => {
  const work = (over: Partial<EditSummary> = {}): EditSummary => ({
    revisionId: 'r1', revisionNo: 1, status: 'SUPERSEDED', lines: 450, linesWithChineseEdits: 0, linesWithKhmer: 0, approvedLines: 0,
    manualSpeakerAssignments: 0, speakers: 4, namedSpeakers: 0, reviewedSpeakers: 0, speakersWithVoice: 0, reviewOperations: 0, ...over,
  })

  it('offers "Use this revision" for finished and previously used revisions only', () => {
    const can = (status: RevisionStatus) => canActivate({ status })
    expect(can('READY')).toBe(true)
    expect(can('SUPERSEDED')).toBe(true)
    expect(can('ACTIVE')).toBe(false)
    expect(can('BUILDING')).toBe(false)
    expect(can('FAILED')).toBe(false)
  })

  it('labels every state, the one in use clearly', () => {
    expect(REVISION_STATUS.ACTIVE).toEqual({ label: 'In use', variant: 'success' })
    expect(REVISION_STATUS.SUPERSEDED.label).toBe('Used before')
    for (const s of ['BUILDING', 'READY', 'ACTIVE', 'SUPERSEDED', 'FAILED'] as RevisionStatus[]) expect(REVISION_STATUS[s].label).toBeTruthy()
  })

  it('explains that a refused switch changed nothing', () => {
    expect(switchFailure('BACKUP_FAILED')).toMatch(/backup .* could not be made, so nothing was switched/)
    expect(switchFailure(REVISION_CHANGED)).toMatch(/changed meanwhile/)
    expect(switchFailure('EDIT_CONFLICT')).toMatch(/nothing was switched/)
    expect(switchFailure(undefined)).toBe('Nothing was switched.')
  })

  it('counts and describes review work, including review history alone', () => {
    expect(hasWork(null)).toBe(false)
    expect(hasWork(work())).toBe(false)
    expect(hasWork(work({ reviewOperations: 1 }))).toBe(true)
    expect(hasWork(work({ namedSpeakers: 2 }))).toBe(true)
    const text = describeWork(work({ linesWithChineseEdits: 3, linesWithKhmer: 5, namedSpeakers: 2, reviewOperations: 12 }))
    expect(text).toContain('3 Chinese corrections')
    expect(text).toContain('5 Khmer lines')
    expect(text).toContain('2 named')
    expect(text).toContain('12 changes in its review history')
  })
})
