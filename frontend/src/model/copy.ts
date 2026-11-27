import { ModelError } from './direct'

export const english = new URL(location.href).searchParams.get('locale') === 'en'
export const t = (zh: string, en: string) => english ? en : zh

export function modelMessage(error: unknown): string {
  if (!(error instanceof ModelError)) return t('本次操作失败。', 'The operation failed.')
  switch (error.code) {
    case 'CONFIG': return t('请检查 Base URL、Model ID 和 API Key。', 'Check the base URL, model ID, and API key.')
    case 'HTTP':
      switch (error.status) {
        case 401: case 403: return t('模型服务拒绝鉴权，请检查 API Key 和权限。', 'The model service denied access. Check the API key and permissions.')
        case 404: return t('接口或模型不存在，请核对 Base URL 和 Model ID。', 'Endpoint or model not found. Check the base URL and model ID.')
        case 429: return t('模型服务限制了频率或额度，请稍后手动重试。', 'The model service reached a rate or quota limit. Retry when available.')
        default: return t(`模型服务返回 HTTP ${error.status}。`, `The model service returned HTTP ${error.status}.`)
      }
    case 'NETWORK': return t('浏览器无法读取模型响应。请检查网络、TLS、目标 CORS、本地网络权限或重定向。',
      'The browser could not read the response. Check the network, TLS, gateway CORS, local network access, or redirects.')
    case 'UPSTREAM_ERROR': return t('模型服务请求失败。可以检查连接后重试。', 'The model request failed. Check the connection and retry.')
    case 'TIMEOUT': return t('模型请求超时。', 'The model request timed out.')
    case 'TRUNCATED': return t('模型响应在完整结束前中断。', 'The model response ended before completion.')
    case 'LIMIT': return t('模型响应超过大小上限。', 'The model response exceeded the size limit.')
    case 'EMPTY': return t('模型返回了空正文。', 'The model returned empty text.')
    case 'CANCELED': return t('已停止浏览器中的模型请求。', 'Stopped the model request in this browser.')
    case 'CREDENTIAL': return t('检测到 API Key 混入文本，已停止处理。', 'An API key was found in the text. Processing stopped.')
    default: return t('模型响应缺少正常结束的 assistant 文本。', 'The response lacks a normally completed assistant message.')
  }
}
