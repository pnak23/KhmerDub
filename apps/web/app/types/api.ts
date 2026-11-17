// Shapes returned by the Spring Boot API (see apps/api ... JobViews / ProjectViews).

export type ProjectStatus = 'CREATED' | 'IMPORTED' | 'AUDIO_READY' | 'TRANSCRIBED' | 'TRANSLATED' | 'VOICED' | 'EXPORTED'
export type JobType = 'IMPORT' | 'EXTRACT_AUDIO' | string
export type JobStatus = 'QUEUED' | 'RUNNING' | 'PAUSING' | 'PAUSED' | 'CANCELLING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type ChunkStatus = 'PENDING' | 'DISPATCHED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'SKIPPED'
export type AttemptStatus = 'DISPATCHED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'LOST' | 'CANCELLED'

export interface JobBrief {
  id: string
  type: JobType
  status: JobStatus
  currentStage: string | null
  progress: number
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface ProjectSummary {
  id: string
  name: string
  status: ProjectStatus
  durationMs: number | null
  sourceLanguage: string
  archived: boolean
  originalFilename: string | null
  originalSizeBytes: number | null
  activeJob: JobBrief | null
  lastJob: JobBrief | null
  createdAt: string
  updatedAt: string
}

export interface AudioStreamInfo {
  index: number
  codec: string | null
  channels: number | null
  channelLayout: string | null
  sampleRate: number | null
  language: string | null
  title: string | null
  default: boolean
  durationMs: number | null
}

export interface ProbeSummary {
  formatName: string | null
  formatLongName: string | null
  durationMs: number | null
  sizeBytes: number | null
  bitRate: number | null
  videoStreams: { index: number; codec: string | null; width: number | null; height: number | null; fps: number | null }[]
  audioStreams: AudioStreamInfo[]
  subtitleStreams: number
}

export interface AssetView {
  id: string
  type: string
  filename: string
  storagePath: string
  mimeType: string | null
  sizeBytes: number | null
  sha256: string | null
  metadata: {
    probe?: ProbeSummary
    validation?: { valid: boolean; problems: string[] }
    [key: string]: unknown
  }
  producedByAttemptId: string | null
  createdAt: string
}

export interface ProjectDetail {
  summary: ProjectSummary
  targetLanguage: string
  translationStyle: string
  sourceAudioStreamIndex: number | null
  processingProfileId: string | null
  original: AssetView | null
  extractedAudio: AssetView | null
  jobs: JobBrief[]
}

export interface AttemptView {
  id: string
  attemptNo: number
  status: AttemptStatus
  countsTowardLimit: boolean
  worker: string | null
  dispatchedAt: string
  claimedAt: string | null
  finishedAt: string | null
  endReason: string | null
  error: string | null
  metrics: Record<string, unknown>
}

export interface ChunkView {
  id: string
  stage: string
  index: number
  startMs: number | null
  endMs: number | null
  status: ChunkStatus
  progress: number
  retryCount: number
  currentAttemptId: string | null
  resultAssetId: string | null
  error: string | null
  attempts: AttemptView[]
}

export interface JobView {
  id: string
  projectId: string
  type: JobType
  status: JobStatus
  currentStage: string | null
  stages: string[]
  progress: number
  maxAttempts: number
  retryCount: number
  errorMessage: string | null
  resultAssetId: string | null
  parameters: Record<string, unknown>
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  updatedAt: string
  version: number
  chunks: ChunkView[]
}

export interface EventView {
  seq: number
  chunkId: string | null
  attemptId: string | null
  type: string
  level: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
  message: string | null
  payload: Record<string, unknown>
  createdAt: string
}

export interface StorageInfo {
  root: string
  freeBytes: number
  totalBytes: number
  minFreeBytes: number
  maxUploadBytes: number
  headroomBytes: number
}

export interface UploadResult {
  original: AssetView
  importJob: JobBrief
}

// ---------------------------------------------------------------------------------------- M3b: dialogue

export type TranscriptionMode = 'QUALITY' | 'FAST'
export type RevisionStatus = 'BUILDING' | 'READY' | 'ACTIVE' | 'SUPERSEDED' | 'FAILED'

export interface TranscriptionRequest {
  mode: TranscriptionMode
  language: string
  diarize?: boolean
  confirmReprocess: boolean
}

export interface RevisionView {
  id: string
  revisionNo: number
  status: RevisionStatus
  mode: TranscriptionMode | null
  summary: Record<string, number | undefined>
  sourceJobId: string | null
  backupAssetId: string | null
  createdAt: string
  activatedAt: string | null
  supersededAt: string | null
}

export type VoiceCategory = 'ADULT_MALE' | 'ADULT_FEMALE' | 'BOY' | 'GIRL' | 'ELDERLY_MALE' | 'ELDERLY_FEMALE' | 'CUSTOM'
export type SpeakerReviewStatus = 'UNREVIEWED' | 'REVIEWED'
export type LineReviewStatus = 'PENDING' | 'NEEDS_REVIEW' | 'APPROVED' | 'REJECTED'

/** mergedIntoId set = merged away (kept so the merge can be undone). Never contains voice fingerprints. */
export interface SpeakerView {
  id: string
  code: string
  characterName: string | null
  speechMs: number
  segmentCount: number
  displayOrder: number
  reviewStatus: SpeakerReviewStatus | string
  voiceProfileId: string | null
  sampleAssetIds: string[]
  description: string | null
  suggestedCategory: VoiceCategory | null
  voiceAssignment: 'NONE' | 'SUGGESTED' | 'MANUAL' | string
  origin: 'DIARIZATION' | 'SPLIT' | 'MANUAL' | string
  mergedIntoId: string | null
  version: number
}

export interface WordTiming {
  w: string
  s: number
  e: number
  p?: number
}

/** sourceText = recognised (read-only), sourceTextEdited = your Chinese correction, editedText = your Khmer. */
export interface LineView {
  id: string
  seq: number
  startMs: number
  endMs: number
  speakerId: string | null
  sourceText: string
  sourceTextEdited: string | null
  editedText: string | null
  confidence: number | null
  speakerPurity: number | null
  flags: Record<string, boolean>
  reviewStatus: LineReviewStatus | string
  speakerAssignment: 'AUTO' | 'MANUAL' | string
  version: number
  words: WordTiming[]
}

// ---------------------------------------------------------------------------------------- M3b-2: review

export interface OperationView {
  id: string
  seq: number
  type: string
  summary: string
  createdAt: string | null
  undoneAt: string | null
  undoable: boolean
}

export interface OperationResult {
  operation: OperationView
  lines: LineView[]
  speakers: SpeakerView[]
  removedLineIds: string[]
  removedSpeakerIds: string[]
}

export interface EditSummary {
  revisionId: string
  revisionNo: number
  status: RevisionStatus
  lines: number
  linesWithChineseEdits: number
  linesWithKhmer: number
  approvedLines: number
  manualSpeakerAssignments: number
  speakers: number
  namedSpeakers: number
  reviewedSpeakers: number
  speakersWithVoice: number
  reviewOperations: number
}

export type GlossaryCategory = 'CHARACTER' | 'PLACE' | 'TERM'

export interface GlossaryTerm {
  id: string
  category: GlossaryCategory
  sourceTerm: string
  variants: string[]
  khmerTerm: string | null
  notes: string | null
  speakerCode: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface GlossaryPreviewLine {
  segmentId: string
  seq: number
  version: number
  before: string
  after: string
  matches: { variant: string; term: string; count: number }[]
}

export interface GlossaryPreview {
  revisionId: string
  token: string
  lines: number
  replacements: number
  changes: GlossaryPreviewLine[]
}

/** Local library entry. provider is always NONE (no TTS yet); nothing is sent anywhere. */
export interface VoiceProfile {
  id: string
  displayName: string
  provider: string
  providerVoiceId: string
  language: string
  voiceCategory: VoiceCategory
  style: string | null
  active: boolean
  usedBySpeakers: number
  createdAt: string
  updatedAt: string
}

/** /api/system/ml: allow-listed fields only (no paths, tokens or fingerprints). */
export interface MlStatus {
  checkedAt: string
  models: {
    manifest: 'present' | 'missing'
    list?: Record<string, { revision: string | null; files: number | null; bytes: number | null; license: string | null;
      verifiedAt: string | null; filesPresent: number | null }>
  }
  worker: {
    reported: boolean
    reportedAt?: string | null
    stale?: boolean
    platform?: string | null
    ml?: { ok: boolean | null; versionsOk: boolean | null; sileroVadOk: boolean | null; models?: Record<string, boolean | null> }
    offline?: Record<string, string | null>
    network?: { internetBlocked: boolean | null; redis: string | null; spring: string | null; isolatedAndConnected: boolean | null }
  }
  keepAwake: { enabled: boolean | null; holding: boolean | null; reason: string | null; lastChange: string | null }
}

export interface DialoguePage {
  revisionId: string
  revisionStatus: RevisionStatus
  total: number
  fromSeq: number
  lines: LineView[]
}