import { getLocale } from './locale.ts'

type Text = { 'zh-CN': string; en: string }

/** API.md「错误响应」表中的业务码，以及前端会直接展示的少量补充码。 */
export const apiErrorText: Record<string, Text> = {
  INVALID_JSON: { 'zh-CN': '请求正文不是有效的 JSON。', en: 'The request body is not valid JSON.' },
  UNKNOWN_FIELD: { 'zh-CN': '请求包含不允许的字段。', en: 'The request contains an unsupported field.' },
  INVALID_CURSOR: { 'zh-CN': '列表已变化，请从第一页重新加载。', en: 'The list changed. Reload from the first page.' },
  INVALID_IDEMPOTENCY_KEY: { 'zh-CN': '运行请求标识无效，请重新创建运行。', en: 'The run request key is invalid. Create the run again.' },
  INVALID_LOCALE: { 'zh-CN': '不支持的语言，只能是中文或 English。', en: 'Unsupported language. Use Chinese or English.' },
  AUTH_REQUIRED: { 'zh-CN': '请先登录。', en: 'Sign in first.' },
  INVALID_CREDENTIALS: { 'zh-CN': '邮箱或密码不正确，或账号不可用。', en: 'Incorrect email or password, or the account is unavailable.' },
  CSRF_INVALID: { 'zh-CN': '本地会话已更新，请重试。', en: 'The session was updated. Try again.' },
  FORBIDDEN: { 'zh-CN': '没有权限执行此操作。', en: 'You do not have permission for this action.' },
  RESOURCE_NOT_FOUND: { 'zh-CN': '找不到请求的数据。', en: 'The requested data was not found.' },
  INVALID_CASE: { 'zh-CN': '找不到当前用例。', en: 'The current case was not found.' },
  INVALID_SLOT: { 'zh-CN': 'Prompt 无效，请检查必需变量及文字内容。', en: 'The prompt is invalid. Check the required variables and text.' },
  INVALID_RESULT: { 'zh-CN': '提交的正文无效。', en: 'The submitted text is invalid.' },
  VERSION_CHANGED: { 'zh-CN': '题目版本已更新，请重新加载。', en: 'The problem version changed. Reload.' },
  RUN_EXPIRED: { 'zh-CN': '本次运行已过期，请新建运行。', en: 'This run has expired. Create a new run.' },
  RUN_NOT_COMPLETED: { 'zh-CN': '运行尚未完成。', en: 'The run is not complete yet.' },
  RESULT_CONFLICT: { 'zh-CN': '本次运行已有结果。请刷新查看，或新建运行。', en: 'This run already has a result. Refresh, or create a new run.' },
  IDEMPOTENCY_CONFLICT: { 'zh-CN': '本次请求与已创建的运行不一致，请重新创建。', en: 'This request does not match the run already created. Create it again.' },
  DRAFT_REVISION_CONFLICT: { 'zh-CN': '草稿已在其他地方更新。你的本地编辑已保留。', en: 'The draft was updated elsewhere. Your local edits are kept.' },
  PAYLOAD_TOO_LARGE: { 'zh-CN': '输入超过长度或结构限制。', en: 'The input exceeds the size or structure limit.' },
  UNSUPPORTED_MEDIA_TYPE: { 'zh-CN': '请求格式不受支持。', en: 'The request format is not supported.' },
  RATE_LIMITED: { 'zh-CN': '操作过于频繁，请稍后再试。', en: 'Too many requests. Try again later.' },
  TEMPORARILY_UNAVAILABLE: { 'zh-CN': '服务暂时繁忙，请重试。', en: 'The service is busy. Try again.' },
  INTERNAL_ERROR: { 'zh-CN': '服务出现内部错误，请稍后重试。', en: 'An internal error occurred. Try again later.' },
  METHOD_NOT_ALLOWED: { 'zh-CN': '请求方法不被支持。', en: 'The request method is not allowed.' },
}

/** Nginx 等非 JSON 响应没有业务码，只按 HTTP 状态回退，不解析错误页正文。 */
const statusText: Record<number, Text> = {
  413: apiErrorText.PAYLOAD_TOO_LARGE,
  415: apiErrorText.UNSUPPORTED_MEDIA_TYPE,
  429: apiErrorText.RATE_LIMITED,
  500: apiErrorText.INTERNAL_ERROR,
  502: { 'zh-CN': '上游服务暂时不可用，请稍后重试。', en: 'The upstream service is unavailable. Try again later.' },
  503: apiErrorText.TEMPORARILY_UNAVAILABLE,
  504: { 'zh-CN': '上游服务响应超时，请稍后重试。', en: 'The upstream service timed out. Try again later.' },
}

export function apiErrorMessage(code: string | undefined, status: number): string {
  const locale = getLocale()
  const text = (code && apiErrorText[code]) || statusText[status]
  if (text) return text[locale]
  return locale === 'en' ? `Request failed (HTTP ${status}).` : `请求失败（HTTP ${status}）。`
}

export const networkErrorMessage = (): string => (getLocale() === 'en'
  ? 'Could not reach the backend. Check the local service.'
  : '连接后端失败，请检查本地服务。')
