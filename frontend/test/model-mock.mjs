import { createServer } from 'node:http'
import { once } from 'node:events'

const part = (content, finish_reason = null) => 'data: ' + JSON.stringify({
  choices: [{ index: 0, delta: content === undefined ? {} : { content }, finish_reason }],
}) + '\r\n\r\n'

export async function modelMock(allowedOrigin, raw) {
  const records = []
  let behavior = 'normal'
  let disconnected = 0
  const server = createServer(async (req, res) => {
    const { method, url } = req
    if (url === '/embed') {
      res.writeHead(200, { 'Content-Type': 'text/html' }).end(
        `<!doctype html><iframe src="${allowedOrigin}"></iframe>`,
      )
      return
    }
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const body = Buffer.concat(chunks).toString('utf8')
    const record = { method, url, headers: req.headers, body }
    records.push(record)
    const cors = {
      'Access-Control-Allow-Origin': allowedOrigin,
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'authorization, content-type',
      'Access-Control-Max-Age': '0',
    }
    if (behavior === 'preflight' && method === 'OPTIONS') {
      res.writeHead(403).end()
      return
    }
    if (method === 'OPTIONS') {
      res.writeHead(204, cors).end()
      return
    }
    function json(status, data) {
      res.writeHead(status, {
        ...(behavior === 'cors' ? {} : cors), 'Content-Type': 'application/json',
      }).end(JSON.stringify(data))
    }
    if (url.endsWith('/models')) {
      if (behavior === 'list-error') json(404, { error: 'unavailable' })
      else json(200, { data: [{ id: 'example-model' }] })
      return
    }
    if (url === '/probe') {
      json(200, {})
      return
    }
    if (method !== 'POST' || !url.endsWith('/chat/completions')) {
      json(404, { error: 'unknown mock route' })
      return
    }
    if (typeof behavior === 'number') {
      json(behavior, { error: 'mock error' })
      return
    }
    if (behavior.startsWith('redirect:')) {
      res.writeHead(307, { ...cors, Location: behavior.slice('redirect:'.length) }).end()
      return
    }
    if (behavior === 'cors') {
      json(200, { choices: [] })
      return
    }
    const request = JSON.parse(body)
    const text = behavior === 'echo' ? req.headers.authorization?.slice('Bearer '.length) : raw()
    if (!request.stream) {
      json(200, { choices: [{
        finish_reason: 'stop', message: { role: 'assistant', content: request.max_tokens ? 'OK' : text },
      }] })
      return
    }
    res.writeHead(200, { ...cors, 'Content-Type': 'text/event-stream' })
    const bytes = Buffer.from(part(text))
    const cut = bytes.indexOf('中') + 1
    res.write(bytes.subarray(0, Math.max(1, cut)))
    const remainder = setTimeout(() => {
      res.write(bytes.subarray(Math.max(1, cut)))
      if (behavior === 'truncated') res.end(part(undefined, 'stop'))
      else if (behavior === 'normal') res.end(part(undefined, 'stop') + 'data: [DONE]\r\n\r\n')
    }, 35)
    res.on('close', () => {
      clearTimeout(remainder)
      if (!res.writableFinished) disconnected++
    })
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  return {
    origin: `http://127.0.0.1:${server.address().port}`,
    records,
    behavior(value) { behavior = value },
    disconnected: () => disconnected,
    close: () => new Promise(resolve => server.close(resolve)),
  }
}
