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
