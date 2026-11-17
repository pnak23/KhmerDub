import type { ApiError } from '~/utils/api'
import type { EditSummary, LineView, OperationResult, RevisionStatus, RevisionView, VoiceCategory, WordTiming } from '~/types/api'

/** How each revision state is shown. Only the revision in use can be edited; the others are read-only. */
export const REVISION_STATUS: Record<RevisionStatus, { label: string; variant: 'success' | 'info' | 'secondary' | 'warning' | 'danger' }> = {
  ACTIVE: { label: 'In use', variant: 'success' },
  READY: { label: 'Ready, not used yet', variant: 'info' },
  SUPERSEDED: { label: 'Used before', variant: 'secondary' },
  BUILDING: { label: 'Processing', variant: 'warning' },
  FAILED: { label: 'Failed', variant: 'danger' },
}

/** A revision can be put in use when it is finished and not used yet, or was in use before (it kept its own work). */
export function canActivate(r: Pick<RevisionView, 'status'>): boolean {
  return r.status === 'READY' || r.status === 'SUPERSEDED'
}

/** The client-side refusal used when the revisions changed between opening the confirmation and confirming it. */
export const REVISION_CHANGED = 'REVISION_CHANGED'

/** What a refused switch means. A switch is all-or-nothing on the server, so a refusal always means nothing changed. */
export function switchFailure(code: string | undefined): string {
  switch (code) {
    case 'BACKUP_FAILED': return 'The backup of the revision in use could not be made, so nothing was switched.'
    case 'EDIT_CONFLICT': return 'Something changed at the same moment, so nothing was switched. Please try again.'
    case REVISION_CHANGED: return 'The revisions changed meanwhile (in another tab or window), so nothing was switched. Check the details below and confirm again.'
    default: return 'Nothing was switched.'
  }
}

/** Any review work in a revision (corrections, Khmer, approvals, speakers, voices or review history)? */
export function hasWork(e: EditSummary | null | undefined): e is EditSummary {
  return !!e && e.linesWithChineseEdits + e.linesWithKhmer + e.approvedLines + e.manualSpeakerAssignments
    + e.namedSpeakers + e.reviewedSpeakers + e.speakersWithVoice + e.reviewOperations > 0
}

/** "3 Chinese corrections, 5 Khmer lines, … , 12 changes in its review history". */
export function describeWork(e: EditSummary): string {
  return `${e.linesWithChineseEdits} Chinese corrections, ${e.linesWithKhmer} Khmer lines, ${e.approvedLines} approved lines, `
    + `${e.manualSpeakerAssignments} manual speaker assignments, ${e.namedSpeakers} named and ${e.reviewedSpeakers} reviewed speakers, `
    + `${e.speakersWithVoice} voices assigned, ${e.reviewOperations} changes in its review history`
}

export const VOICE_CATEGORIES: { value: VoiceCategory; label: string }[] = [
  { value: 'ADULT_MALE', label: 'Adult male' },
  { value: 'ADULT_FEMALE', label: 'Adult female' },
  { value: 'BOY', label: 'Boy' },
  { value: 'GIRL', label: 'Girl' },
  { value: 'ELDERLY_MALE', label: 'Elderly male' },
  { value: 'ELDERLY_FEMALE', label: 'Elderly female' },
  { value: 'CUSTOM', label: 'Custom' },
]

export const LINE_STATUSES = ['PENDING', 'NEEDS_REVIEW', 'APPROVED', 'REJECTED'] as const
export const LINE_FLAGS = [
  { value: 'any', label: 'Any flag' },
  { value: 'lowConfidence', label: 'Low confidence' },
  { value: 'mixedSpeakers', label: 'Mixed speakers' },
  { value: 'firstWordRetimed', label: 'First word re-timed' },
] as const

export const PAGE_SIZES = [100, 150, 200] as const

/**
 * Codes meaning "someone else changed this in the meantime" (or the request was based on an old view). The UI shows
 * them and offers a reload; it never retries the change with a newer version on its own.
 */
const CONFLICT_CODES = new Set(['EDIT_CONFLICT', 'UNDO_CONFLICT', 'PREVIEW_STALE', 'UNDO_ORDER', 'ALREADY_UNDONE'])

export function isConflict(err: Pick<ApiError, 'code'> | null | undefined): boolean {
  return !!err?.code && CONFLICT_CODES.has(err.code)
}

/** The read-only revision answer (older/newly processed revisions cannot be edited). */
export function isReadOnly(err: Pick<ApiError, 'code'> | null | undefined): boolean {
  return err?.code === 'REVISION_READ_ONLY' || err?.code === 'PROJECT_ARCHIVED'
}

/** What the user sees: corrected Chinese if there is one, else the recognised text. */
export function currentChinese(line: Pick<LineView, 'sourceText' | 'sourceTextEdited'>): string {
  return line.sourceTextEdited ?? line.sourceText
}

/** Lines returned by a change replace their old copies; removed lines disappear. Order follows seq. */
export function applyLines(page: LineView[], result: Pick<OperationResult, 'lines' | 'removedLineIds'>): LineView[] {
  const updated = new Map(result.lines.map((l) => [l.id, l]))
  const removed = new Set(result.removedLineIds)
  return page.filter((l) => !removed.has(l.id)).map((l) => updated.get(l.id) ?? l).sort((a, b) => a.seq - b.seq)
}

/** Structural changes renumber lines, so the page is reloaded instead of patched. */
export function needsReload(type: string): boolean {
  return ['SPLIT_SEGMENT', 'MERGE_SEGMENTS', 'MERGE_SPEAKERS', 'SPLIT_SPEAKER', 'APPLY_GLOSSARY'].includes(type)
}

export function pageCount(total: number, size: number): number {
  return Math.max(1, Math.ceil(total / Math.max(1, size)))
}

/** 83456 -> "1:23.4" (tenths), 3723400 -> "1:02:03.4". */
export function formatTimecode(ms: number): string {
  const tenths = Math.floor(ms / 100) % 10
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}.${tenths}` : `${m}:${pad(s)}.${tenths}`
}

/** "1:23.4", "83.4", "1:02:03" -> milliseconds; null when it is not a time. */
export function parseTimecode(text: string): number | null {
  const t = text.trim()
  if (!/^\d+(:\d{1,2}){0,2}(\.\d{1,3})?$/.test(t)) return null
  const [main, frac = ''] = t.split('.')
  const parts = main!.split(':').map(Number)
  if (parts.slice(1).some((p) => p > 59)) return null
  const seconds = parts.reduce((acc, p) => acc * 60 + p, 0)
  return seconds * 1000 + (frac ? Math.round(Number(`0.${frac}`) * 1000) : 0)
}

/** The two texts a split before word {@code at} produces (recognised text is kept exactly). */
export function splitPreview(words: WordTiming[], at: number): [string, string] {
  return [words.slice(0, at).map((w) => w.w).join(''), words.slice(at).map((w) => w.w).join('')]
}

/** Glossary variants typed one per line (or separated by commas): trimmed, de-duplicated, no empties. */
export function parseVariants(text: string): string[] {
  return [...new Set(text.split(/[\n,，]/).map((v) => v.trim()).filter(Boolean))]
}

export type ShortcutAction = 'save' | 'saveNext' | 'next' | 'previous' | 'approve' | 'play' | 'undo'

/**
 * Dialogue editor shortcuts. Chosen so they never steal normal text editing keys (Ctrl+Z inside a text box still
 * undoes typing): Ctrl+S save, Ctrl+Enter save + next line, Alt+Down / Alt+Up next / previous line, Alt+A approve,
 * Alt+P play the line, Ctrl+Alt+Z undo the last saved change.
 */
export function shortcutFor(e: { key: string; ctrlKey: boolean; altKey: boolean; shiftKey: boolean; metaKey: boolean }): ShortcutAction | null {
  const ctrl = e.ctrlKey || e.metaKey
  const key = e.key.length === 1 ? e.key.toLowerCase() : e.key
  if (ctrl && e.altKey && key === 'z') return 'undo'
  if (ctrl && !e.altKey && key === 's') return 'save'
  if (ctrl && !e.altKey && key === 'Enter') return 'saveNext'
  if (e.altKey && !ctrl && key === 'ArrowDown') return 'next'
  if (e.altKey && !ctrl && key === 'ArrowUp') return 'previous'
  if (e.altKey && !ctrl && key === 'a') return 'approve'
  if (e.altKey && !ctrl && key === 'p') return 'play'
  return null
}

/**
 * Only the fields the user actually changed, in the API's convention: undefined = leave unchanged, "" = clear.
 */
export function lineChanges(line: LineView, draft: { chinese: string; khmer: string }): { sourceTextEdited?: string; editedText?: string } {
  const out: { sourceTextEdited?: string; editedText?: string } = {}
  if (draft.chinese !== currentChinese(line)) out.sourceTextEdited = draft.chinese === line.sourceText ? '' : draft.chinese
  if (draft.khmer !== (line.editedText ?? '')) out.editedText = draft.khmer
  return out
}
