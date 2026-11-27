import { createParser } from 'eventsource-parser'
import { checkOutput, completionText, isRecord, makeRequest, MAX_FILE_BYTES, MAX_OUTPUT_BYTES, TruncatedCompletion } from '../practice/d0.ts'
import type { Message } from '../practice/messages'
import type { RunFailure } from '../practice/api'
import { normalizeBase, validKey, validModel } from './connection.ts'

const encoder = new TextEncoder()
const defaults = { totalMs: 180_000, firstMs: 30_000, idleMs: 30_000,
  responseBytes: MAX_FILE_BYTES, eventBytes: MAX_OUTPUT_BYTES, outputBytes: MAX_OUTPUT_BYTES }

type ModelErrorCode = RunFailure | 'CANCELED' | 'NETWORK' | 'HTTP' | 'CONFIG' | 'LIMIT' | 'EMPTY' | 'CREDENTIAL'

export class ModelError extends Error {
  readonly code: ModelErrorCode
  readonly status?: number

  constructor(code: ModelErrorCode, status?: number) {
    super(code)
    this.code = code
    this.status = status
  }

  get failureCode(): RunFailure {
    switch (this.code) {
      case 'TIMEOUT': case 'TRUNCATED': return this.code
      case 'NETWORK': case 'HTTP': case 'UPSTREAM_ERROR': return 'UPSTREAM_ERROR'
      default: return 'PROTOCOL_ERROR'
    }
  }
}

type Options = { signal: AbortSignal; onText?: (text: string) => void }
type Target = { base: string; key: string }
type Completion = { model: string; stream: boolean; messages: Message[]; test?: true }

function json(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    throw new ModelError('PROTOCOL_ERROR')
  }
}

function assertNoKey(text: string, key: string) {
  if (key && text.includes(key)) throw new ModelError('CREDENTIAL')
}

export class DirectTransport {
  private readonly platformOrigin: string
  private readonly limits: typeof defaults

  constructor(platformOrigin: string, overrides: Partial<typeof defaults> = {}) {
    this.platformOrigin = platformOrigin
    this.limits = { ...defaults, ...overrides }
  }

  async list(target: Target, options: Options): Promise<string[]> {
    const raw = await this.request(target, options)
    const payload = json(raw)
    if (!isRecord(payload) || 'error' in payload || !Array.isArray(payload.data)) {
      throw new ModelError('PROTOCOL_ERROR')
    }
    const models = new Set<string>()
    for (const item of payload.data) {
      if (isRecord(item) && typeof item.id === 'string' && validModel(item.id)
        && (!target.key || !item.id.includes(target.key))) models.add(item.id)
    }
    return [...models]
  }

  test(target: Target, model: string, options: Options): Promise<string> {
    return this.complete(target, {
      model, stream: false, messages: [{ role: 'user', content: 'Reply OK.' }], test: true,
    }, options)
  }

  async complete(target: Target, completion: Completion, options: Options): Promise<string> {
    if (!validModel(completion.model)) throw new ModelError('CONFIG')
    const response = await this.request(target, options, completion)
    try {
      const text = completion.stream ? response : completionText(json(response))
      assertNoKey(text, target.key)
      if (encoder.encode(text).length > this.limits.outputBytes) throw new ModelError('LIMIT')
      if (!text.trim()) throw new ModelError('EMPTY')
      return checkOutput(text)
    } catch (err) {
      if (err instanceof ModelError) throw err
      if (err instanceof TruncatedCompletion) throw new ModelError('TRUNCATED')
      throw new ModelError('PROTOCOL_ERROR')
    }
  }

  private async request(target: Target, options: Options, completion?: Completion): Promise<string> {
    let base: string
    try {
      base = normalizeBase(target.base, this.platformOrigin)
      if (!validKey(target.key)) throw new Error()
    } catch {
      throw new ModelError('CONFIG')
    }
    assertNoKey(base, target.key)
    const stream = completion?.stream === true
    const headers = new Headers()
    if (target.key) headers.set('Authorization', 'Bearer ' + target.key)
    let body: string | undefined
    if (completion) {
      headers.set('Content-Type', 'application/json')
      const request = makeRequest(completion.messages, completion.model, stream)
      for (const message of request.messages) assertNoKey(message.content, target.key)
      assertNoKey(request.model, target.key)
      body = JSON.stringify(completion.test ? { ...request, max_tokens: 32 } : request)
    }

    const controller = new AbortController()
    const cancel = () => controller.abort(new ModelError('CANCELED'))
    options.signal.addEventListener('abort', cancel, { once: true })
    if (options.signal.aborted) cancel()

    const timeout = (ms: number) => setTimeout(() => controller.abort(new ModelError('TIMEOUT')), ms)
    const total = timeout(this.limits.totalMs)
    const first = timeout(this.limits.firstMs)
    let idle: ReturnType<typeof setTimeout> | undefined
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
    const progress = () => {
      clearTimeout(first)
      clearTimeout(idle)
      idle = timeout(this.limits.idleMs)
    }

    try {
      controller.signal.throwIfAborted()
      const response = await fetch(base + (completion ? '/chat/completions' : '/models'), {
        method: completion ? 'POST' : 'GET', headers, body, signal: controller.signal,
        credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', cache: 'no-store',
      })
      if (!response.ok) throw new ModelError('HTTP', response.status)
      const mediaType = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase()
      if (mediaType !== (stream ? 'text/event-stream' : 'application/json') || !response.body) {
        throw new ModelError('PROTOCOL_ERROR')
      }
      reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8', { fatal: true })
      const sse = stream ? new CompletionStream(this.limits.eventBytes, this.limits.outputBytes, target.key, progress) : undefined
      let received = 0
      let text = ''
      while (!sse?.done) {
        const chunk = await reader.read()
        controller.signal.throwIfAborted()
        if (chunk.done) break
        received += chunk.value.length
        if (received > this.limits.responseBytes) throw new ModelError('LIMIT')
        const decoded = decode(decoder, chunk.value)
        if (sse) {
          try {
            sse.feed(decoded)
          } finally {
            // A chunk can contain usable text followed by a failing event.
            if (text !== sse.text) options.onText?.(sse.text)
            text = sse.text
          }
        } else {
          progress()
          text += decoded
        }
      }
      if (!sse?.done) decode(decoder)
      if (sse && !sse.done) throw new ModelError('TRUNCATED')
      controller.signal.throwIfAborted()
      return text
    } catch (err) {
      if (controller.signal.aborted) throw controller.signal.reason
      if (err instanceof ModelError) throw err
      throw new ModelError('NETWORK')
    } finally {
      clearTimeout(total)
      clearTimeout(first)
      clearTimeout(idle)
      options.signal.removeEventListener('abort', cancel)
      controller.abort()
      reader?.releaseLock()
    }
  }
}

function decode(decoder: TextDecoder, bytes?: Uint8Array) {
  try {
    return bytes ? decoder.decode(bytes, { stream: true }) : decoder.decode()
  } catch {
    throw new ModelError('PROTOCOL_ERROR')
  }
}

class CompletionStream {
  text = ''
  done = false
  private stopped = false
  private outputBytes = 0
  private frameBytes = 0
  private lineHasText = false
  private endsWithCR = false
  private readonly eventLimit: number
  private readonly outputLimit: number
  private readonly key: string
  private readonly progress: () => void
  private readonly parser = createParser({ onEvent: event => this.event(event.data, event.event) })

  constructor(eventLimit: number, outputLimit: number, key: string, progress: () => void) {
    this.eventLimit = eventLimit
    this.outputLimit = outputLimit
    this.key = key
    this.progress = progress
  }

  feed(chunk: string) {
    // Bound the unfinished SSE frame before buffering more input in the parser.
    if (!chunk) return
    let start = this.endsWithCR && chunk.startsWith('\n') ? 1 : 0
    for (const newline of chunk.matchAll(/\r\n|\r|\n/g)) {
      if (newline.index < start) continue
      this.count(chunk.slice(start, newline.index))
      if (this.lineHasText) this.count(newline[0])
      else this.frameBytes = 0
      this.lineHasText = false
      start = newline.index + newline[0].length
    }
    this.count(chunk.slice(start))
    this.endsWithCR = chunk.endsWith('\r')
    this.parser.feed(chunk)
  }

  private count(text: string) {
    this.frameBytes += encoder.encode(text).length
    if (this.frameBytes > this.eventLimit) throw new ModelError('LIMIT')
    this.lineHasText ||= text.length > 0
  }

  private event(data: string, event?: string) {
    if (!data || this.done) return
    if (event && event !== 'message') throw new ModelError('PROTOCOL_ERROR')
    if (data === '[DONE]') {
      if (!this.stopped) throw new ModelError('TRUNCATED')
      this.done = true
      return
    }
    const payload = json(data)
    if (!isRecord(payload) || 'error' in payload || !Array.isArray(payload.choices)) {
      throw new ModelError('PROTOCOL_ERROR')
    }
    if (!payload.choices.length) return
    const choice = payload.choices[0]
    if (!isRecord(choice) || (choice.index != null && choice.index !== 0)
      || this.stopped || !isRecord(choice.delta)) throw new ModelError('PROTOCOL_ERROR')
    const delta = choice.delta
    if ((delta.role != null && delta.role !== 'assistant') || delta.tool_calls != null
      || delta.function_call != null || delta.refusal != null) throw new ModelError('PROTOCOL_ERROR')
    if (delta.content != null) {
      if (typeof delta.content !== 'string') throw new ModelError('PROTOCOL_ERROR')
      this.append(delta.content)
    }
    if (choice.finish_reason != null) {
      if (choice.finish_reason === 'length') throw new ModelError('TRUNCATED')
      if (choice.finish_reason !== 'stop') throw new ModelError('PROTOCOL_ERROR')
      this.stopped = true
    }
    this.progress()
  }

  private append(content: string) {
    if (!content) return
    if (this.key) assertNoKey(this.text.slice(-this.key.length) + content, this.key)
    // An escaped UTF-16 pair may be split across JSON events.
    const paired = /[\uD800-\uDBFF]$/.test(this.text) && /^[\uDC00-\uDFFF]/.test(content)
    this.outputBytes += encoder.encode(content).length - (paired ? 2 : 0)
    if (this.outputBytes > this.outputLimit) throw new ModelError('LIMIT')
    this.text += content
  }
}
