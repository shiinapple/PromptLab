import type { Message, MessagePlan } from './messages'
import { editableSlot } from './slot'
import { apiErrorMessage, networkErrorMessage } from '../i18n/errors.ts'

export type Grading = 'STRUCTURE_ONLY' | 'RULES'
export type ProblemSummary = {
  problemId: string
  version: number
  title: string
  category: string
  difficulty: string
}

export type Problem = MessagePlan & {
  problemId: string
  version: number
  publishedVersion: number
  title: string
  description: string
  defaultSlots: Record<string, string>
  cases: { caseId: string; inputLocale: string; grading: Grading; variables: Record<string, string> }[]
}

export type Check = { id: string; path: string; status: 'PASS' | 'FAIL'; code: string; expected: unknown; actual: unknown }
export type RunMode = 'D0' | 'DIRECT'
export type RunFailure = 'TIMEOUT' | 'UPSTREAM_ERROR' | 'TRUNCATED' | 'PROTOCOL_ERROR'
export type DirectReport = { modelId: string; finishReason: 'stop'; durationMs: number }
export type Run = {
  runId: string
  problemId: string
  version: number
  caseId: string
  mode: RunMode
  status: 'PENDING' | 'COMPLETED' | 'FAILED' | 'CANCELED' | 'EXPIRED'
  expiresAt: string
  messages: Message[]
  requestParams: Record<string, never>
  rawOutput: string | null
  verdict: 'PASSED' | 'FORMAT_ERROR' | 'WRONG_ANSWER' | null
  checks: Check[] | null
  reported: DirectReport | null
  failureCode: RunFailure | null
}

export class ApiError extends Error {
  constructor(public readonly code: string | undefined, public readonly status: number,
    public readonly details: Record<string, unknown> = {}) {
    super(apiErrorMessage(code, status))
  }
}

/** 账号模式下业务接口返回 AUTH_REQUIRED 时，页面切回登录界面。 */
export const authListeners = new Set<() => void>()

export async function getJson<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, options)
  } catch {
    throw new Error(networkErrorMessage())
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const error = new ApiError(body?.code, response.status, body?.details ?? {})
    if (error.code === 'AUTH_REQUIRED') authListeners.forEach(listener => listener())
    throw error
  }
  if (response.status === 204) return undefined as T
  return response.json()
}

type Csrf = { token: string; headerName: string }
let csrf: Promise<Csrf> | undefined
// local-practice 的写接口校验自己的 token（/api/practice/session），账号模式使用会话 token。
let csrfPath = '/api/auth/csrf'
export function useLocalCsrf() {
  csrfPath = '/api/practice/session'
  csrf = undefined
}

/** 每次写请求前取同一会话的 CSRF；登录或退出后调用 refreshCsrf 失效旧 token。 */
export function refreshCsrf() {
  csrf = undefined
}
function csrfToken() {
  csrf ??= getJson<Csrf>(csrfPath).catch(err => {
    csrf = undefined
    throw err
  })
  return csrf
}

export async function post<T>(path: string, body: unknown, key?: string) {
  const { token, headerName } = await csrfToken()
  const headers = new Headers({ 'Content-Type': 'application/json', [headerName]: token })
  if (key) headers.set('Idempotency-Key', key)
  return getJson<T>(path, { method: 'POST', headers, body: JSON.stringify(body) })
}

export async function send<T>(method: 'PUT' | 'PATCH' | 'DELETE', path: string, body?: unknown) {
  const { token, headerName } = await csrfToken()
  const headers = new Headers({ [headerName]: token })
  if (body !== undefined) headers.set('Content-Type', 'application/json')
  return getJson<T>(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
}

type Locale = 'zh-CN' | 'en'
export const readProblems = (locale: Locale) => getJson<{ items: ProblemSummary[] }>(`/api/problems?locale=${locale}`)
export function readProblem(id: string, locale: Locale, version?: number) {
  const params = new URLSearchParams({ locale })
  if (version !== undefined) params.set('version', String(version))
  return getJson<Problem>(`/api/problems/${encodeURIComponent(id)}?${params}`)
}
export const readRun = (id: string) => getJson<Run>(`/api/runs/${encodeURIComponent(id)}`)

export function createRun(problem: Problem, caseId: string, text: string, mode: RunMode, key: string) {
  const { problemId, version } = problem
  const slots = { [editableSlot(problem).name]: text }
  return post<Run>('/api/runs', { problemId, version, caseId, mode, slots, requestParams: {} }, key)
}

export type RunResult = { status: 'COMPLETED'; rawOutput: string; reported?: DirectReport }
  | { status: 'FAILED'; failureCode: RunFailure }
  | { status: 'CANCELED' }

export function submitResult(id: string, result: RunResult) {
  return post<Run>(`/api/runs/${encodeURIComponent(id)}/result`, result)
}
