import {setTimeout as delay} from 'node:timers/promises';

const text = ' 中文🙂 ';
const normal = content => ({choices: [{message: {content}, finish_reason: 'stop'}]});
const delta = (content, finish_reason = null) => ({
  choices: [{index: 0, delta: content === null ? {} : {content}, finish_reason}],
});
const frame = value => 'data: ' + JSON.stringify(value) + '\r\n\r\n';
const end = frame(delta(null, 'stop')) + 'data: [DONE]\r\n\r\n';

export function modelMock(pageOrigin, trace) {
  return async (request, response) => {
    const [, scenario, ...pathParts] = request.url.split('/');
    const path = pathParts.join('/');
    if (request.headers.origin !== pageOrigin ||
        !['v1/models', 'v1/chat/completions'].includes(path)) {
      response.writeHead(403).end();
      return;
    }

    const headers = {'Access-Control-Allow-Origin': pageOrigin, Vary: 'Origin'};
    trace[scenario] ??= {options: 0, gets: 0, posts: 0, disconnected: 0};
    const entry = trace[scenario];
    if (request.method === 'OPTIONS') {
      entry.options++;
      entry.requestedHeaders = request.headers['access-control-request-headers'];
      response.writeHead(204, scenario !== 'denied' ? {
        ...headers,
        'Access-Control-Allow-Headers': 'authorization, content-type',
        'Access-Control-Allow-Methods': 'GET, POST',
      } : {}).end();
      return;
    }

    const json = (value, status = 200) => {
      response.writeHead(status, {...headers, 'Content-Type': 'application/json'});
      response.end(JSON.stringify(value));
    };
    const expectedAuth = scenario === 'no-auth' ? undefined : 'Bearer poc-not-a-real-key';
    if (request.headers.authorization !== expectedAuth || request.headers.cookie) {
      json({}, 401);
      return;
    }
    if (request.method === 'GET' && path === 'v1/models') {
      entry.gets++;
      json(scenario === 'manual-id' ? {} : {data: [{id: 'mock-model'}]}, scenario === 'manual-id' ? 404 : 200);
      return;
    }
    if (request.method !== 'POST' || path !== 'v1/chat/completions') {
      json({}, 405);
      return;
    }

    entry.posts++;
    response.on('close', () => {
      if (!response.writableEnded) entry.disconnected++;
    });
    try {
      let body = '';
      for await (const bytes of request) {
        body += bytes;
        if (body.length > 4096) {
          json({}, 413);
          return;
        }
      }
      const {model, stream, messages, max_tokens} = JSON.parse(body);
      if (model !== 'mock-model' || messages?.[0]?.content !== 'Reply OK.' || max_tokens !== 32) {
        json({}, 400);
        return;
      }

      if (/^http-(401|404|429|503)$/.test(scenario)) {
        json({error: {message: 'mock failure'}}, Number(scenario.slice(5)));
        return;
      }
      if (scenario === 'redirect') {
        response.writeHead(302, {...headers, Location: '/redirect-target/v1/chat/completions'}).end();
        return;
      }
      if (scenario === 'denied-actual') {
        response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(normal(text)));
        return;
      }
      if (scenario === 'first-timeout') return;
      if (!stream) {
        if (scenario === 'malformed') {
          response.writeHead(200, {...headers, 'Content-Type': 'application/json'}).end('{');
        } else if (scenario === 'json-tool') {
          json({choices: [{message: {content: text, tool_calls: [{}]}, finish_reason: 'stop'}]});
        } else {
          json(normal(scenario === 'empty' ? '   ' : text));
        }
        return;
      }

      response.writeHead(200, {...headers, 'Content-Type': 'text/event-stream'});
      if (scenario === 'sse') {
        const event = Buffer.from(': comment\r\n\r\ndata:\r\n\r\ndata: {"choices":\r\ndata: ' +
          JSON.stringify(delta(text).choices) + '}\r\n\r\n');
        const split = event.indexOf(Buffer.from('🙂')) + 2;
        response.write(event.subarray(0, split));
        await delay(80);
        if (!response.destroyed) response.end(Buffer.concat([event.subarray(split), Buffer.from(end)]));
      } else if (scenario === 'empty-sse') {
        response.end(end);
      } else if (scenario === 'missing-stop') {
        response.end(frame(delta(text)) + 'data: [DONE]\r\n\r\n');
      } else if (scenario === 'truncated') {
        response.end(frame(delta(text)) + frame(delta(null, 'stop')));
      } else if (scenario === 'socket-closed') {
        response.write(frame(delta(text)));
        await delay(80);
        response.destroy();
      } else if (scenario === 'sse-tool') {
        response.end(frame({choices: [{delta: {tool_calls: [{}]}, finish_reason: 'tool_calls'}]}));
      } else if (scenario === 'invalid-utf8') {
        response.end(Buffer.from([0xff]));
      } else if (scenario === 'finish-length' || scenario === 'finish-filter') {
        response.end(frame(delta(text, scenario === 'finish-length' ? 'length' : 'content_filter')));
      } else if (scenario === 'idle-timeout' || scenario === 'cancel') {
        response.write(frame(delta(text)));
      } else if (scenario === 'total-timeout') {
        while (!response.destroyed) {
          response.write(frame(delta('x')));
          await delay(30);
        }
      } else {
        response.end(frame(delta('中'.repeat(100))) + end);
      }
    } catch {
      if (!response.destroyed && !response.headersSent) json({}, 400);
      else response.destroy();
    }
  };
}
