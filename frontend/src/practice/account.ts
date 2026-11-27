import { getJson as getJsonPublic, post, refreshCsrf, send, useLocalCsrf } from './api'
import type { Locale } from '../i18n/locale'

export type Profile = { userId: string; email: string }
export type DraftSlots = Record<string, string>
export type Draft = {
  problemId: string
  version: number
  caseId: string
  slots: DraftSlots
  revision: number
  updatedAt: string
}
export type HistoryItem = {
  runId: string
  problemId: string
  version: number
  caseId: string
  mode: 'D0' | 'DIRECT'
  status: 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELED' | 'EXPIRED'
  verdict: 'PASSED' | 'FORMAT_ERROR' | 'WRONG_ANSWER' | null
  createdAt: string
  expiresAt: string
  finishedAt: string | null
  submissionId: string | null
  submittedAt: string | null
}
export type Page<T> = { items: T[]; nextCursor: string | null }
export type Submission = { submissionId: string; runId: string; submittedAt: string }

/** local-practice 模式下 /api/practice/session 返回 200；账号模式会被会话拦截并返回 401。 */
export async function detectAccountMode(): Promise<boolean> {
  const response = await fetch('/api/practice/session')
  if (response.status === 200) {
    useLocalCsrf()
    return false
  }
  return true
}

export const me = () => getJsonPublic<Profile>('/api/me')

export type Settings = { locale: Locale; editorPrefs: Record<string, unknown>; updatedAt: string | null }
export const readSettings = () => getJsonPublic<Settings>('/api/settings')
export const saveLocale = (locale: Locale) => send<Settings>('PATCH', '/api/settings', { locale })

export async function login(email: string, password: string) {
  await post<void>('/api/auth/login', { email, password })
  refreshCsrf()
}

export async function logout() {
  await post<void>('/api/auth/logout', {})
  refreshCsrf()
}

export const readDraft = (problemId: string, version: number) =>
  getJsonPublic<{ draft: Draft | null }>(`/api/problems/${encodeURIComponent(problemId)}/draft?version=${version}`)

export function writeDraft(problemId: string, body: { version: number; caseId: string; slots: DraftSlots; revision: number }) {
  return send<{ draft: Draft }>('PUT', `/api/problems/${encodeURIComponent(problemId)}/draft`, body)
}

export function readHistory(problemId: string, cursor?: string | null) {
  const params = new URLSearchParams({ limit: '20' })
  if (cursor) params.set('cursor', cursor)
  return getJsonPublic<Page<HistoryItem>>(`/api/problems/${encodeURIComponent(problemId)}/runs?${params}`)
}

export function readArchive(cursor?: string | null) {
  const params = new URLSearchParams({ limit: '20' })
  if (cursor) params.set('cursor', cursor)
  return getJsonPublic<Page<HistoryItem>>(`/api/submissions?${params}`)
}

export const archiveRun = (runId: string) => post<Submission>('/api/submissions', { runId })
export const unarchive = (submissionId: string) => send<void>('DELETE', `/api/submissions/${encodeURIComponent(submissionId)}`)
