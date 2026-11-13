import { describe, expect, it } from 'vitest'
import { contentMatches, sniffMovie } from '~/utils/sniff'

const mp4Head = [0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d] // ....ftypisom
const mkvHead = [0x1a, 0x45, 0xdf, 0xa3, 0, 0, 0, 0]

describe('movie content sniffing (same rule as the server)', () => {
  it('recognises MP4/MOV and MKV signatures', () => {
    expect(contentMatches('mp4', new Uint8Array(mp4Head))).toBe(true)
    expect(contentMatches('mov', new Uint8Array(mp4Head))).toBe(true)
    expect(contentMatches('mkv', new Uint8Array(mkvHead))).toBe(true)
  })

  it('rejects mismatches and too-short files', () => {
    expect(contentMatches('mp4', new Uint8Array(mkvHead))).toBe(false)
    expect(contentMatches('mkv', new Uint8Array(mp4Head))).toBe(false)
    expect(contentMatches('mp4', new Uint8Array([0, 0, 0]))).toBe(false)
  })

  it('reads only the first bytes of the File and explains a mismatch', async () => {
    expect(await sniffMovie(new File([new Uint8Array(mp4Head)], 'ok.MP4'))).toBeNull()
    expect(await sniffMovie(new File(['this is not a movie'], 'fake.mp4')))
      .toBe('This file does not look like a MP4 movie.')
  })
})
