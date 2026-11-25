import { createParser } from '/eventsource-parser.mjs';

const defaults = {
  totalMs: 180_000, firstMs: 30_000, idleMs: 30_000,
  responseBytes: 1024 * 1024, eventBytes: 256 * 1024, textBytes: 256 * 1024,
};
const encoder = new TextEncoder();

class RunError extends Error {
  constructor(code, status) {
    super(code);
    this.status = status;
  }
}

export function endpoint(base, path) {
  let url;
  try {
    url = new URL(base);
  } catch {
    throw new RunError('invalid_url');
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (!(url.protocol === 'https:' || (url.protocol === 'http:' && loopback)) ||
      url.username || url.password || url.href.includes('?') || url.href.includes('#') ||
      (path !== 'models' && path !== 'chat/completions')) {
    throw new RunError('invalid_url');
  }
  url.pathname = url.pathname.replace(/\/+$/, '') + '/' + path;
  return url.href;
}

function decodeJson(value) {
  try {
    return JSON.parse(value);
  } catch {
    throw new RunError('invalid_json');
  }
}

function checkMessage(message) {
  if (!message || typeof message !== 'object' || Array.isArray(message)) {
    throw new RunError('invalid_response');
  }
  if (message.tool_calls != null || message.function_call != null) {
    throw new RunError('tool_call');
  }
  if (message.refusal != null) throw new RunError('refusal');
}

function checkFinish(reason) {
  if (reason !== 'stop') throw new RunError('unsupported_finish');
}

export async function run({base, key, model, stream = false, list = false, signal,
                           onText = () => {}, limits: overrides = {}}) {
  const limits = {...defaults, ...overrides};
  const controller = new AbortController();
  const cancel = () => controller.abort(new RunError('cancelled'));
  signal?.addEventListener('abort', cancel, {once: true});
  if (signal?.aborted) cancel();

  const timeout = (name, delay) =>
    setTimeout(() => controller.abort(new RunError(name)), delay);
  const total = timeout('total_timeout', limits.totalMs);
  const first = timeout('first_timeout', limits.firstMs);
  let idle;
  let reader;
  let bytes = 0;
  let text = '';
  let textBytes = 0;
  let stopped = false;
  let doneEvent = false;

  function progress() {
    clearTimeout(first);
    clearTimeout(idle);
    idle = timeout('idle_timeout', limits.idleMs);
  }

  function append(content) {
    if (typeof content !== 'string') throw new RunError('invalid_response');
    textBytes += encoder.encode(content).length;
    if (textBytes > limits.textBytes) throw new RunError('text_limit');
    text += content;
    if (content) onText(text);
    controller.signal.throwIfAborted();
  }

  try {
    controller.signal.throwIfAborted();
    const headers = key ? {Authorization: 'Bearer ' + key} : {};
    if (!list) headers['Content-Type'] = 'application/json';
    const response = await fetch(endpoint(base, list ? 'models' : 'chat/completions'), {
      method: list ? 'GET' : 'POST',
      headers,
      body: list ? undefined : JSON.stringify({
        model, stream, messages: [{role: 'user', content: 'Reply OK.'}], max_tokens: 32,
      }),
      signal: controller.signal,
      redirect: 'error',
      credentials: 'omit',
    });
    if (!response.ok) throw new RunError('http_error', response.status);
    const mediaType = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
    if (mediaType !== (stream ? 'text/event-stream' : 'application/json') || !response.body) {
      throw new RunError('invalid_response');
    }
    reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8', {fatal: true});
    let json = '';
    const parser = stream ? createParser({
      onEvent({data}) {
        if (!data || doneEvent) return;
        if (encoder.encode(data).length > limits.eventBytes) throw new RunError('event_limit');
        if (data === '[DONE]') {
          if (!stopped) throw new RunError('incomplete');
          doneEvent = true;
          return;
        }
        const payload = decodeJson(data);
        if (payload?.error || !Array.isArray(payload?.choices)) throw new RunError('invalid_response');
        if (payload.choices.length === 0) return;
        const choice = payload.choices[0];
        if (stopped || !choice) throw new RunError('invalid_response');
        checkMessage(choice.delta);
        if (choice.delta.content != null) append(choice.delta.content);
        if (choice.finish_reason != null) {
          checkFinish(choice.finish_reason);
          stopped = true;
        }
        progress();
      },
    }) : null;

    while (!doneEvent) {
      const chunk = await reader.read();
      if (chunk.done) {
        const tail = decoder.decode();
        if (stream) parser.feed(tail);
        else json += tail;
        break;
      }
      bytes += chunk.value.byteLength;
      if (bytes > limits.responseBytes) throw new RunError('response_limit');
      const decoded = decoder.decode(chunk.value, {stream: true});
      if (stream) parser.feed(decoded);
      else json += decoded;
    }
    if (stream) {
      if (!doneEvent) throw new RunError('incomplete');
    } else {
      const payload = decodeJson(json);
      if (payload?.error) throw new RunError('invalid_response');
      if (list) {
        if (!Array.isArray(payload?.data) || !payload.data.every(item => typeof item?.id === 'string')) {
          throw new RunError('invalid_response');
        }
        return {ok: true, models: payload.data.map(item => item.id)};
      }
      const choice = Array.isArray(payload?.choices) && payload.choices[0];
      checkMessage(choice?.message);
      checkFinish(choice.finish_reason);
      append(choice.message.content);
    }
    if (!text.trim()) throw new RunError('empty_text');
    return {ok: true, text};
  } catch (error) {
    const failure = controller.signal.aborted ? controller.signal.reason : error;
    return {ok: false, code: failure instanceof RunError ? failure.message : 'fetch_or_io_error',
            status: failure?.status, partial: text};
  } finally {
    clearTimeout(first);
    clearTimeout(idle);
    clearTimeout(total);
    signal?.removeEventListener('abort', cancel);
    controller.abort();
    reader?.releaseLock();
  }
}
