import { describe, expect, it } from 'vitest'
import { extensionOf, extractionSchema, newProjectSchema } from '~/utils/schemas'

const MB = 1024 * 1024
const movie = (name: string, size: number) => new File([new Uint8Array(Math.min(size, 16))], name, { type: 'video/mp4' })

function fileWithSize(name: string, size: number): File {
  const f = movie(name, size)
  Object.defineProperty(f, 'size', { value: size })
  return f
}

describe('newProjectSchema', () => {
  const schema = newProjectSchema(100 * MB)

  it('accepts a valid project and trims the name', () => {
    const r = schema.safeParse({ name: '  Film ', sourceLanguage: 'ko', file: fileWithSize('a.MKV', 10 * MB) })
    expect(r.success).toBe(true)
    expect(r.data?.name).toBe('Film')
  })

  it.each([
    ['clip.avi', 10 * MB, 'Only MP4, MKV and MOV movies are supported'],
    ['empty.mp4', 0, 'The file is empty'],
    ['huge.mov', 101 * MB, 'The file is larger than the 100.0 MB limit'],
  ])('rejects %s', (name, size, message) => {
    const r = schema.safeParse({ name: 'x', sourceLanguage: 'auto', file: fileWithSize(name, size) })
    expect(r.success).toBe(false)
    expect(r.error?.issues.map((i) => i.message)).toContain(message)
  })

  it('requires a file, a name and a valid language', () => {
    const r = schema.safeParse({ name: ' ', sourceLanguage: 'Korean!' })
    const fields = r.error?.issues.map((i) => i.path.join('.'))
    expect(fields).toEqual(expect.arrayContaining(['name', 'sourceLanguage', 'file']))
  })
})

describe('extractionSchema', () => {
  it('needs an audio track', () => {
    expect(extractionSchema.safeParse({ sourceLanguage: 'auto' }).error?.issues[0]?.message).toBe('Choose the audio track to dub')
    expect(extractionSchema.safeParse({ audioStreamIndex: 2, sourceLanguage: 'zh-Hant' }).success).toBe(true)
  })
})

describe('extensionOf', () => {
  it('is case-insensitive and handles names without a dot', () => {
    expect(extensionOf('My.Movie.MP4')).toBe('mp4')
    expect(extensionOf('noext')).toBe('')
  })
})
