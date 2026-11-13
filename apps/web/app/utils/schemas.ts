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
