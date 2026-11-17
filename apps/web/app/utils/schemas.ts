import { z } from 'zod'
import { formatBytes } from '~/utils/format'

export const SOURCE_LANGUAGES = [
  { value: 'auto', label: 'Detect automatically' },
  { value: 'zh', label: 'Chinese' },
  { value: 'ko', label: 'Korean' },
  { value: 'ja', label: 'Japanese' },
  { value: 'en', label: 'English' },
  { value: 'th', label: 'Thai' },
  { value: 'vi', label: 'Vietnamese' },
  { value: 'hi', label: 'Hindi' },
  { value: 'id', label: 'Indonesian' },
  { value: 'es', label: 'Spanish' },
  { value: 'fr', label: 'French' },
] as const

export const MOVIE_EXTENSIONS = ['mp4', 'mkv', 'mov'] as const

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

const languageCode = z.string().regex(/^(auto|[a-z]{2,3}(-[A-Za-z]{2,4})?)$/, 'Choose a language')

export const projectNameSchema = z.string().trim().min(1, 'Enter a project name').max(200, 'At most 200 characters')

/** Start transcription + speaker identification (M3b). Quality is the default; Fast trades speaker separation for speed. */
export const transcriptionSchema = z.object({
  mode: z.enum(['QUALITY', 'FAST'], { message: 'Choose Quality or Fast' }),
  language: z.string().regex(/^(auto|[a-z]{2,3})$/, 'Choose a language'),
})

/** The API refused because the project already has dialogue: the user must confirm (a verified backup is made). */
export function needsReprocessConfirmation(code: string | undefined): boolean {
  return code === 'REPROCESS_CONFIRMATION_REQUIRED'
}

// ---------------------------------------------------------------------------------------- M3b-2: review forms

const voiceCategory = z.enum(['ADULT_MALE', 'ADULT_FEMALE', 'BOY', 'GIRL', 'ELDERLY_MALE', 'ELDERLY_FEMALE', 'CUSTOM'],
  { message: 'Choose a category' })

/** Speaker card details. Empty strings clear a field. */
export const speakerSchema = z.object({
  characterName: z.string().max(150, 'At most 150 characters'),
  description: z.string().max(2000, 'At most 2000 characters'),
  suggestedCategory: z.union([voiceCategory, z.literal('')]),
})

/** One dialogue line: your Chinese correction and your Khmer (the recognised text is never edited). */
export const lineTextSchema = z.object({
  chinese: z.string().max(5000, 'At most 5000 characters'),
  khmer: z.string().max(5000, 'At most 5000 characters'),
})

export const glossaryTermSchema = z.object({
  sourceTerm: z.string().trim().min(1, 'Enter the correct spelling').max(200, 'At most 200 characters'),
  category: z.enum(['CHARACTER', 'PLACE', 'TERM'], { message: 'Choose a category' }),
  variants: z.string().max(10_000, 'Too long'),
  khmerTerm: z.string().max(200, 'At most 200 characters'),
  notes: z.string().max(2000, 'At most 2000 characters'),
})

/** Local voice library entry (no TTS provider yet). */
export const voiceProfileSchema = z.object({
  displayName: z.string().trim().min(1, 'Enter a name').max(150, 'At most 150 characters'),
  voiceCategory,
  language: z.string().regex(/^[a-z]{2,3}(-[A-Z]{2})?$/, 'Use a code like km-KH'),
  style: z.string().max(100, 'At most 100 characters'),
})

/** The movie file: type, emptiness and size are checked before anything is uploaded. */
export function movieFileSchema(maxUploadBytes: number) {
  return z.instanceof(File, { message: 'Choose a movie file' })
    .refine((f) => (MOVIE_EXTENSIONS as readonly string[]).includes(extensionOf(f.name)), 'Only MP4, MKV and MOV movies are supported')
    .refine((f) => f.size > 0, 'The file is empty')
    .refine((f) => f.size <= maxUploadBytes, `The file is larger than the ${formatBytes(maxUploadBytes)} limit`)
}

export function newProjectSchema(maxUploadBytes: number) {
  return z.object({
    name: projectNameSchema,
    sourceLanguage: languageCode,
    file: movieFileSchema(maxUploadBytes),
  })
}

export const extractionSchema = z.object({
  audioStreamIndex: z.number({ message: 'Choose the audio track to dub' }).int().min(0),
  sourceLanguage: languageCode,
})
