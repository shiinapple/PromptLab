import { t } from '../i18n/locale.ts'
import type { Message } from './messages'

export const MAX_FILE_BYTES = 1024 * 1024
export const MAX_OUTPUT_BYTES = 256 * 1024

export function makeRequest(messages: Message[], model = 'YOUR_MODEL', stream = false) {
  return { model, messages: messages.map(({ role, content }) => ({ role, content })), stream }
}

export function checkOutput(text: string): string {
  if (text.length > MAX_OUTPUT_BYTES || new TextEncoder().encode(text).byteLength > MAX_OUTPUT_BYTES) {
    throw new Error(t('模型正文须小于或等于 256 KiB。', 'The model output must be 256 KiB or smaller.'))
  }
  if (!text.trim()) throw new Error(t('模型正文为空。', 'The model output is empty.'))
  if (/[\uD800-\uDFFF]/u.test(text)) throw new Error(t('模型正文包含无效的 Unicode 字符。', 'The model output contains invalid Unicode.'))
  return text
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export async function importResponse(file: Pick<File, 'size' | 'arrayBuffer'>): Promise<string> {
  if (file.size > MAX_FILE_BYTES) throw new Error(t('响应文件须小于或等于 1 MiB。', 'The response file must be 1 MiB or smaller.'))
  const bytes = await file.arrayBuffer()
  if (bytes.byteLength > MAX_FILE_BYTES) throw new Error(t('响应文件须小于或等于 1 MiB。', 'The response file must be 1 MiB or smaller.'))
  let envelope: unknown
  try {
    envelope = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch {
    throw new Error(t('响应文件须为 UTF-8 编码的 JSON。', 'The response file must be UTF-8 JSON.'))
  }
  return checkOutput(completionText(envelope))
}

export class TruncatedCompletion extends Error {}

export function completionText(envelope: unknown): string {
  if (!isRecord(envelope) || 'error' in envelope || !Array.isArray(envelope.choices)) {
    throw new Error(t('文件中没有可导入的 Chat Completions 响应。', 'The file has no importable Chat Completions response.'))
  }
  const choice: unknown = envelope.choices[0]
  if (isRecord(choice) && choice.finish_reason === 'length') {
    throw new TruncatedCompletion(t('本次响应未正常结束。请在模型工具中重新执行。', 'The response did not finish normally. Run the request again in your model tool.'))
  }
  if (!isRecord(choice) || choice.finish_reason !== 'stop') {
    throw new Error(t('本次响应未正常结束。请在模型工具中重新执行。', 'The response did not finish normally. Run the request again in your model tool.'))
  }
  const message = choice.message
  if (!isRecord(message) || (message.role !== undefined && message.role !== 'assistant')
    || message.tool_calls != null || message.function_call != null || message.refusal != null
    || typeof message.content !== 'string') {
    throw new Error(t('响应缺少完整的 assistant 文本正文。', 'The response has no complete assistant text.'))
  }
  return message.content
}
