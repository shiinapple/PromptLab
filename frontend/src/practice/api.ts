import type { Message, MessagePlan } from './messages'

export type Problem = MessagePlan & {
  problemId: string
  version: number
  title: string
  description: string
  defaultSlots: Record<string, string>
  cases: { caseId: string; inputLocale: string; variables: Record<string, string> }[]
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
}

export class ApiError extends Error {
  constructor(public readonly code: string | undefined, status: number) {
    super((code && errors[code]) || `请求失败（HTTP ${status}）。`)
  }
}

async function getJson<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, options)
  } catch {
    throw new Error('连接后端失败，请检查本地服务。')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(body?.code, response.status)
  }
  return response.json()
}

async function post<T>(path: string, body: unknown, key?: string) {
  const { token } = await getJson<{ token: string }>('/api/practice/session')
  const headers = new Headers({ 'Content-Type': 'application/json', 'X-Practice-CSRF': token })
  if (key) headers.set('Idempotency-Key', key)
  return getJson<T>(path, { method: 'POST', headers, body: JSON.stringify(body) })
}

export const readProblem = (locale: 'zh-CN' | 'en') => getJson<Problem>(`/api/problems/002?locale=${locale}`)
export const readRun = (id: string) => getJson<Run>(`/api/runs/${encodeURIComponent(id)}`)

export function createRun(problem: Problem, caseId: string, user: string, mode: RunMode, key: string) {
  const { problemId, version } = problem
  return post<Run>('/api/runs', { problemId, version, caseId, mode, slots: { user }, requestParams: {} }, key)
}

export type RunResult = { status: 'COMPLETED'; rawOutput: string; reported?: DirectReport }
  | { status: 'FAILED'; failureCode: RunFailure }
  | { status: 'CANCELED' }

export function submitResult(id: string, result: RunResult) {
  return post<Run>(`/api/runs/${encodeURIComponent(id)}/result`, result)
}
