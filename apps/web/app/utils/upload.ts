import type { UploadResult } from '~/types/api'
import { ApiError, type Problem } from '~/utils/api'

export interface UploadHandle {
  promise: Promise<UploadResult>
  abort: () => void
}

/**
 * Stream a movie straight to Spring Boot (not through the Nuxt proxy, which would buffer it).
 * XMLHttpRequest is used because fetch() has no upload progress events.
 */
export function uploadMovie(url: string, file: File, onProgress: (loaded: number, total: number) => void): UploadHandle {
  const xhr = new XMLHttpRequest()
  const promise = new Promise<UploadResult>((resolve, reject) => {
    xhr.open('POST', url)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    xhr.setRequestHeader('X-File-Name', encodeURIComponent(file.name))
    xhr.upload.onprogress = (e) => onProgress(e.loaded, e.lengthComputable ? e.total : file.size)
    xhr.onload = () => {
      if (xhr.status === 201) {
        resolve(JSON.parse(xhr.responseText) as UploadResult)
        return
      }
      let problem: Problem = { status: xhr.status, detail: `Upload failed (HTTP ${xhr.status})` }
      try {
        problem = { status: xhr.status, ...JSON.parse(xhr.responseText) }
      } catch { /* not JSON */ }
      reject(new ApiError(problem))
    }
    xhr.onerror = () => reject(new ApiError({ status: 0, code: 'NETWORK', detail: 'The upload stopped: the connection to the API was lost.' }))
    xhr.onabort = () => reject(new ApiError({ status: 0, code: 'ABORTED', detail: 'Upload cancelled.' }))
    xhr.send(file)
  })
  return { promise, abort: () => xhr.abort() }
}
