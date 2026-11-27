import { ModelError } from './direct.ts'
import { t } from '../i18n/locale.ts'

/**
 * 直连失败后的排查要点。浏览器不会告诉页面 Fetch 拒绝的具体原因，
 * 所以只列出可能原因，不下确定结论。
 */
export function failureHints(error: ModelError): string[] {
  switch (error.code) {
    case 'NETWORK': return [
      t('确认 Base URL 可以从这个浏览器访问，且服务端允许当前页面来源（CORS）。', 'Check that this browser can reach the base URL and the service allows this page origin (CORS).'),
      t('检查证书（TLS）是否可信，以及网络或代理是否拦截。', 'Check that the TLS certificate is trusted and no network or proxy blocks the request.'),
      t('如果地址在本机或局域网，确认浏览器已授权本地网络访问。', 'For a local or LAN address, allow this browser to access the local network.'),
      t('检查服务是否重定向到其他地址；浏览器拒绝了跨源重定向。', 'Check for redirects; the browser rejects cross-origin redirects.'),
    ]
    case 'HTTP': {
      switch (error.status) {
        case 401: case 403: return [t('检查 API Key 是否正确，以及该 Key 是否有权访问所选模型。', 'Check that the API key is correct and can access the selected model.')]
        case 404: return [t('核对 Base URL 是否为 API 前缀，以及 Model ID 是否存在。', 'Check that the base URL is the API prefix and the model ID exists.')]
        case 429: return [t('额度或频率已达上限，可稍后再试，或改用手动运行。', 'A rate or quota limit was reached. Try later, or switch to manual run.')]
        default: return [t('模型服务暂时异常，可稍后重试，或改用手动运行。', 'The model service is having trouble. Retry later, or switch to manual run.')]
      }
    }
    case 'TIMEOUT': return [t('模型响应太慢。可重试，或改用手动运行。', 'The model responded too slowly. Retry, or switch to manual run.')]
    case 'TRUNCATED': return [t('响应在完整结束前中断。可重试，或关闭流式输出后再试。', 'The response stopped before completion. Retry, or turn off streaming and try again.')]
    default: return []
  }
}
