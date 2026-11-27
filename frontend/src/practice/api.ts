import type { Message, MessagePlan } from './messages'
import { editableSlot } from './slot'

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

const errors: Record<string, string> = {
  RESOURCE_NOT_FOUND: '找不到请求的数据。请确认后端已使用 local-practice 模式启动。',
  VERSION_CHANGED: '题目版本已更新，请重新加载。',
  INVALID_CASE: '找不到当前用例。',
  INVALID_SLOT: 'Prompt 无效，请检查必需变量及文字内容。',
  RUN_EXPIRED: '本次运行已过期，请新建运行。',
  RESULT_CONFLICT: '本次运行已有结果。请刷新查看，或新建运行。',
  IDEMPOTENCY_CONFLICT: '本次请求与已创建的运行不一致，请重新创建。',
  PAYLOAD_TOO_LARGE: '输入超过长度或结构限制。',
  TEMPORARILY_UNAVAILABLE: '服务暂时繁忙，请重试。',
  INVALID_RESULT: '提交的正文无效。',
  CSRF_INVALID: '本地会话已更新，请重试。',
  AUTH_REQUIRED: '请先登录。',
  INVALID_CREDENTIALS: '邮箱或密码不正确，或账号不可用。',
  RATE_LIMITED: '操作过于频繁，请稍后再试。',
  DRAFT_REVISION_CONFLICT: '草稿已在其他地方更新。你的本地编辑已保留。',
  INVALID_CURSOR: '列表已变化，请从第一页重新加载。',
}

export class ApiError extends Error {
  constructor(public readonly code: string | undefined, public readonly status: number,
    public readonly details: Record<string, unknown> = {}) {
    super((code && errors[code]) || `请求失败（HTTP ${status}）。`)
  }
}

/** 账号模式下业务接口返回 AUTH_REQUIRED 时，页面切回登录界面。 */
export const authListeners = new Set<() => void>()

export async function getJson<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, options)
  } catch {
    throw new Error('连接后端失败，请检查本地服务。')
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
