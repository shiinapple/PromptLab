import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ConnectionStore, normalizeBase } from '../src/model/connection.ts'
import { DirectTransport } from '../src/model/direct.ts'

const origin = 'http://127.0.0.1:5173'
const base = 'http://localhost:8081/v1'
const canary = 'fixture-key-a1b2c3'
const encoder = new TextEncoder()
const completion = { model: 'test-model', stream: false, messages: [{ role: 'user', content: '冻结内容' }] }

function storage() {
  const entries = new Map()
  return {
    entries,
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
    removeItem: key => entries.delete(key),
  }
}

test('normalizes HTTPS and explicit loopback paths without adding /v1', () => {
  assert.equal(normalizeBase('  HTTPS://EXAMPLE.TEST/api///  ', origin), 'https://example.test/api')
  assert.equal(normalizeBase('http://localhost:8081/', origin), 'http://localhost:8081')
  assert.equal(normalizeBase('https://example.test/custom/v1', origin), 'https://example.test/custom/v1')
})

test('rejects unsafe or platform URLs', () => {
  for (const address of ['http://example.test/v1', 'http://127.0.0.2:9000/v1', 'http://[::1]:9000/v1',
    'https://user:password@example.test/v1', 'https://@example.test', 'https://example.test/?',
    'https://example.test/#', 'https:///example.test', 'javascript:void(0)', '//example.test',
    'https://example.test/v1\\x', origin, 'http://localhost:5173/v1']) {
    assert.throws(() => normalizeBase(address, origin))
  }
})

test('memory key, explicit tab session, account switch, logout, address change and removal', () => {
  const local = storage()
  const session = storage()
  const profile = { base, model: 'test-model', name: 'demo', stream: true }
  const opened = (owner, tab = session) => {
    const store = new ConnectionStore(local, tab, origin)
    store.selectAccount(owner)
    return store
  }
  const current = opened('a')
  assert.equal(current.save(profile, canary, false), true)
  assert.equal(current.getKey(base), canary)
  assert.equal(session.entries.size, 0)
  assert.equal(opened('a').getKey(base), '')
  assert.equal(current.save(profile, '', true), true)
  assert.equal(opened('a').getKey(base), canary)
  assert.equal(opened('a', storage()).getKey(base), '')
  current.selectAccount('b')
  assert.equal(current.profile, undefined)
  assert.equal(session.entries.size, 0)
  current.save({ ...profile, model: 'other' }, '', false)
  current.selectAccount('a')
  assert.equal(current.profile.model, 'test-model')
  assert.equal(current.getKey(base), '')
  current.save(profile, canary, true)
  current.selectAccount(null)
  assert.equal(current.profile, undefined)
  assert.equal(session.entries.size, 0)
  current.selectAccount('a')
  current.save(profile, canary, true)
  assert.equal(current.getKey('https://example.test'), '')
  current.save({ ...profile, base: 'https://example.test' }, canary, true)
  assert.equal(current.getKey('https://example.test'), '')
  assert.equal(session.entries.size, 0)
  assert.throws(() => current.save({ ...profile, name: canary }, canary, true))
  assert.ok([...local.entries.values()].every(value => !value.includes(canary)))
  current.remove()
  assert.equal(opened('a').profile, undefined)
  assert.equal(opened('b').profile.model, 'other')
})

const output = ' \r\n{"text":"中😀"}\t '
const asJson = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json; charset=utf-8' },
})
const ordinary = (content, finish = 'stop', message = {}) => ({
  choices: [{ finish_reason: finish, message: { role: 'assistant', content, ...message } }],
})
const delta = (change, finish = null) => 'data: ' + JSON.stringify({
  choices: [{ index: 0, delta: change, finish_reason: finish }],
}) + '\r\n\r\n'
const stop = delta({}, 'stop')
const done = 'data: [DONE]\r\n\r\n'

function sse(parts, { signal, stall = false } = {}) {
  return new Response(new ReadableStream({
    start(stream) {
      for (const part of parts) stream.enqueue(typeof part === 'string' ? encoder.encode(part) : part)
      if (stall) signal.addEventListener('abort', () => stream.error(signal.reason), { once: true })
      else stream.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

function useFetch(context, fetch) {
  context.mock.method(globalThis, 'fetch', fetch)
}

const testSignal = () => new AbortController().signal
const target = { base, key: canary }
const run = (transport, stream, options = {}) => transport.complete(
  target, { ...completion, stream }, { signal: testSignal(), ...options },
)

test('JSON sends only frozen provider fields, exact path and selected authorization', async context => {
  useFetch(context, async (url, init) => {
    assert.equal(url, base + '/chat/completions')
    assert.equal(init.headers.get('Authorization'), 'Bearer ' + canary)
    assert.equal(init.credentials, 'omit')
    assert.equal(init.redirect, 'error')
    assert.equal(init.referrerPolicy, 'no-referrer')
    assert.deepEqual(JSON.parse(init.body), completion)
    return asJson(ordinary(output))
  })
  assert.equal(await run(new DirectTransport(origin), false), output)
})

test('lists without a key and tests with a small standalone prompt', async context => {
  useFetch(context, async (url, init) => {
    assert.equal(init.headers.get('Authorization'), null)
    if (init.method === 'GET') {
      assert.equal(url, base + '/models')
      return asJson({ data: [{ id: 'manual' }, { id: 'manual' }, { id: '' }] })
    }
    const request = JSON.parse(init.body)
    assert.equal(request.max_tokens, 32)
    assert.equal(request.stream, false)
    return asJson(ordinary('OK'))
  })
  const transport = new DirectTransport(origin)
  const noKey = { base, key: '' }
  assert.deepEqual(await transport.list(noKey, { signal: testSignal() }), ['manual'])
  assert.equal(await transport.test(noKey, 'manual', { signal: testSignal() }), 'OK')
})

test('SSE handles multi-line data, mixed CRLF, comments, usage and byte-split UTF-8', async context => {
  const first = output.slice(0, 17)
  const next = output.slice(17)
  const stream = ': comment\r\n\r\n\r\n' + delta({ role: 'assistant', content: first }) +
    'data: {"choices":\r\ndata: [{"index":0,"delta":{"content":' + JSON.stringify(next) +
    '},"finish_reason":null}]}\r\n\r\n' + stop + 'data: {"choices":[],"usage":{}}\n\n' + done
  const bytes = encoder.encode(stream)
  useFetch(context, async () => sse(Array.from(bytes, byte => Uint8Array.of(byte))))
  const seen = []
  assert.equal(await run(new DirectTransport(origin), true, { onText: text => seen.push(text) }), output)
  assert.deepEqual(seen, [first, output])
})

test('SSE preserves JSON surrogate pairs split across events at the output byte limit', async context => {
  useFetch(context, async () => sse([delta({ content: '\ud83d' }), delta({ content: '\ude00' }), stop, done]))
  assert.equal(await run(new DirectTransport(origin, { outputBytes: 4 }), true), '😀')
})

for (const [name, parts, code] of [
  ['EOF without done', [delta({ content: 'partial' }), stop], 'TRUNCATED'],
  ['done without stop', [delta({ content: 'partial' }), done], 'TRUNCATED'],
  ['length finish', [delta({ content: 'partial' }), delta({}, 'length'), done], 'TRUNCATED'],
  ['filter finish', [delta({ content: 'partial' }), delta({}, 'content_filter'), done], 'PROTOCOL_ERROR'],
  ['tool calls', [delta({ tool_calls: [{}] }), stop, done], 'PROTOCOL_ERROR'],
  ['error envelope', ['data: {"error":{}}\n\n', done], 'PROTOCOL_ERROR'],
  ['invalid UTF-8', [Uint8Array.of(0xc3, 0x28)], 'PROTOCOL_ERROR'],
  ['empty response', [delta({ content: ' ' }), stop, done], 'EMPTY'],
  ['credential echo across events', [delta({ content: canary.slice(0, 8) }), delta({ content: canary.slice(8) }), stop, done], 'CREDENTIAL'],
]) {
  test('SSE rejects ' + name, async context => {
    useFetch(context, async () => sse(parts))
    await assert.rejects(run(new DirectTransport(origin), true), { code })
  })
}

test('response, unfinished event and output limits are independent', async context => {
  useFetch(context, async () => sse(['data: ' + 'x'.repeat(70)]))
  await assert.rejects(run(new DirectTransport(origin, { eventBytes: 64 }), true), { code: 'LIMIT' })
  await assert.rejects(run(new DirectTransport(origin, { responseBytes: 64 }), true), { code: 'LIMIT' })
  globalThis.fetch.mock.mockImplementation(async () => sse([delta({ content: '界界' }), stop, done]))
  await assert.rejects(run(new DirectTransport(origin, { outputBytes: 5 }), true), { code: 'LIMIT' })
})

test('HTTP statuses, JSON truncation, key echo, and request failures are safe', async context => {
  const fetch = context.mock.method(globalThis, 'fetch', async () => asJson({ error: canary }, 401))
  const transport = new DirectTransport(origin)
  await assert.rejects(run(transport, false), { code: 'HTTP', status: 401 })
  fetch.mock.mockImplementation(async () => asJson(ordinary(output, 'length')))
  await assert.rejects(run(transport, false), { code: 'TRUNCATED' })
  fetch.mock.mockImplementation(async () => asJson(ordinary(canary)))
  await assert.rejects(run(transport, false), { code: 'CREDENTIAL' })
  fetch.mock.mockImplementation(async () => { throw new TypeError(canary) })
  await assert.rejects(run(transport, false), { code: 'NETWORK', message: 'NETWORK' })
})

test('cancellation after partial text prevents success even with an already buffered finish', async context => {
  useFetch(context, async () => sse([delta({ content: 'partial' }) + stop + done]))
  const controller = new AbortController()
  await assert.rejects(run(new DirectTransport(origin), true, {
    signal: controller.signal,
    onText: text => { assert.equal(text, 'partial'); controller.abort() },
  }), { code: 'CANCELED' })
})

test('first effective event and idle timeouts abort active reads', async context => {
  const fetch = context.mock.method(globalThis, 'fetch', async (_url, { signal }) =>
    sse([': heartbeat\n\n'], { signal, stall: true }))
  const transport = new DirectTransport(origin, { firstMs: 25, idleMs: 25, totalMs: 500 })
  await assert.rejects(run(transport, true), { code: 'TIMEOUT' })
  fetch.mock.mockImplementation(async (_url, { signal }) => sse([delta({ content: 'part' })], { signal, stall: true }))
  await assert.rejects(run(transport, true), { code: 'TIMEOUT' })
})

test('total timeout aborts a responsive stream', async context => {
  useFetch(context, async (_url, { signal }) => new Response(new ReadableStream({
    start(stream) {
      stream.enqueue(encoder.encode(delta({ content: 'part' })))
      const clock = setInterval(() => stream.enqueue(encoder.encode(delta({ content: 'more' }))), 8)
      signal.addEventListener('abort', () => { clearInterval(clock); stream.error(signal.reason) }, { once: true })
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } }))
  await assert.rejects(run(new DirectTransport(origin, { totalMs: 80, firstMs: 50, idleMs: 50 }), true), {
    code: 'TIMEOUT',
  })
})
