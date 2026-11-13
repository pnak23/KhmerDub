import { extensionOf } from '~/utils/schemas'

const ISO_ATOMS = new Set(['ftyp', 'moov', 'mdat', 'wide', 'free', 'skip'])

/**
 * Check the first bytes against the file extension BEFORE uploading (same rule as the server).
 * The server also checks, but when it refuses a file it closes the connection mid-upload, and the browser
 * then only sees "connection lost". Checking here gives a clear message instead.
 */
export function contentMatches(extension: string, head: Uint8Array): boolean {
  if (extension === 'mkv') {
    return head.length >= 4 && head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3
  }
  if (extension === 'mp4' || extension === 'mov') {
    return head.length >= 8 && ISO_ATOMS.has(String.fromCharCode(head[4]!, head[5]!, head[6]!, head[7]!))
  }
  return false
}

/** Returns an error message, or null when the file looks like the movie type its name says. */
export async function sniffMovie(file: File): Promise<string | null> {
  const ext = extensionOf(file.name)
  const head = new Uint8Array(await file.slice(0, 16).arrayBuffer())
  return contentMatches(ext, head) ? null : `This file does not look like a ${ext.toUpperCase()} movie.`
}
