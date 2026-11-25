import {endpoint, run} from '/transport.mjs';
import {base} from '/config.mjs';

const expectedText = ' 中文🙂 ';
const request = (name, options) => run({
  base: base + '/' + name + '/v1/', key: 'poc-not-a-real-key', model: 'mock-model', ...options,
});

function check(actual, expected) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(JSON.stringify({actual, expected}));
  }
}

async function failure(name, code, {status, partial, ...options} = {}) {
  const result = await request(name, options);
  check([result.ok, result.code, result.status], [false, code, status]);
  if (partial !== undefined) check(result.partial, partial);
}

const scenarios = [
  ['URL preserves the configured prefix', () => {
    check(endpoint(base + '/prefix/v2///', 'chat/completions'), base + '/prefix/v2/chat/completions');
  }],
  ['URL rejects query, fragment, credentials and non-loopback HTTP', () => {
    for (const url of [base + '/v1?', base + '/v1#', base.replace('//', '//user@'), 'http://example.invalid']) {
      const result = (() => {
        try { endpoint(url, 'models'); } catch (error) { return error.message; }
      })();
      check(result, 'invalid_url');
    }
  }],
  ['GET models with Authorization', async () => {
    check(await request('models', {list: true}), {ok: true, models: ['mock-model']});
  }],
  ['404 on models still permits a manually entered ID', async () => {
    await failure('manual-id', 'http_error', {status: 404, list: true});
    check(await request('manual-id'), {ok: true, text: expectedText});
  }],
  ['POST JSON preserves exact text', async () => {
    check(await request('json'), {ok: true, text: expectedText});
  }],
  ['POST JSON with no Authorization', async () => {
    check(await request('no-auth', {key: ''}), {ok: true, text: expectedText});
  }],
  ['SSE handles split UTF-8, CRLF, empty event, multiline data and explicit end', async () => {
    const updates = [];
    check(await request('sse', {stream: true, onText: text => updates.push(text)}),
      {ok: true, text: expectedText});
    check(updates, [expectedText]);
  }],
  ['SSE EOF after stop without DONE is incomplete', () =>
    failure('truncated', 'incomplete', {stream: true, partial: expectedText})],
  ['SSE DONE without stop is incomplete', () =>
    failure('missing-stop', 'incomplete', {stream: true, partial: expectedText})],
  ['SSE socket failure keeps partial text', () =>
    failure('socket-closed', 'fetch_or_io_error', {stream: true, partial: expectedText})],
  ['JSON whitespace-only body is not a usable result', () => failure('empty', 'empty_text')],
  ['SSE without text is not a usable result', () => failure('empty-sse', 'empty_text', {stream: true})],
  ['Malformed JSON is rejected', () => failure('malformed', 'invalid_json')],
  ['JSON tool call is rejected', () => failure('json-tool', 'tool_call')],
  ['SSE tool call is rejected', () => failure('sse-tool', 'tool_call', {stream: true})],
  ['SSE invalid UTF-8 is rejected', () => failure('invalid-utf8', 'fetch_or_io_error', {stream: true})],
  ['SSE token truncation is rejected', () => failure('finish-length', 'unsupported_finish', {stream: true})],
  ['SSE content filtering is rejected', () => failure('finish-filter', 'unsupported_finish', {stream: true})],
  ...[401, 404, 429, 503].map(status => [
    'HTTP ' + status + ' remains visible', () => failure('http-' + status, 'http_error', {status}),
  ]),
  ['Denied CORS preflight is opaque', () => failure('denied', 'fetch_or_io_error')],
  ['Missing CORS header on POST response is opaque', () => failure('denied-actual', 'fetch_or_io_error')],
  ['Redirect is refused', () => failure('redirect', 'fetch_or_io_error')],
  ['Cancellation after partial text stops the run', async () => {
    const controller = new AbortController();
    await failure('cancel', 'cancelled', {
      stream: true, signal: controller.signal, partial: expectedText,
      onText: () => controller.abort(),
    });
  }],
  ['Cancellation takes priority when completion is already buffered', async () => {
    const controller = new AbortController();
    await failure('cancel-buffered', 'cancelled', {
      stream: true, signal: controller.signal, partial: '中'.repeat(100),
      onText: () => controller.abort(),
    });
  }],
  ['First response timeout', () => failure('first-timeout', 'first_timeout', {limits: {firstMs: 250}})],
  ['Stream idle timeout', () => failure('idle-timeout', 'idle_timeout', {
    stream: true, limits: {idleMs: 150}, partial: expectedText,
  })],
  ['Total timeout despite ongoing events', () => failure('total-timeout', 'total_timeout', {
    stream: true, limits: {totalMs: 350},
  })],
  ['Cumulative response bytes are limited', () =>
    failure('response-limit', 'response_limit', {stream: true, limits: {responseBytes: 128}})],
  ['One SSE event has a UTF-8 byte limit', () =>
    failure('event-limit', 'event_limit', {stream: true, limits: {eventBytes: 256}})],
  ['Model text has a UTF-8 byte limit', () =>
    failure('text-limit', 'text_limit', {stream: true, limits: {textBytes: 128}})],
  ['Mock saw the real preflight; rejected preflight blocked POST; redirects were not followed', async () => {
    const trace = await (await fetch('/trace')).json();
    check(trace.json.requestedHeaders, 'authorization,content-type');
    check([trace.json.options, trace.json.posts], [1, 1]);
    check([trace.denied.options, trace.denied.posts], [1, 0]);
    check([trace['denied-actual'].options, trace['denied-actual'].posts], [1, 1]);
    check(trace['redirect-target'], undefined);
    for (const name of ['cancel', 'first-timeout', 'idle-timeout', 'total-timeout']) {
      check([trace[name].posts, trace[name].disconnected], [1, 1]);
    }
  }],
];

const button = document.querySelector('#run');
const output = document.querySelector('#results');
const autorun = new URLSearchParams(location.search).has('autorun');
async function main() {
  button.disabled = true;
  output.textContent = '';
  const results = [];
  await fetch('/trace', {method: 'DELETE'});
  for (const [name, test] of scenarios) {
    let error;
    try { await test(); } catch (failure) { error = failure.message; }
    results.push({name, error});
    output.textContent += (error ? 'FAIL ' : 'PASS ') + name + (error ? ' — ' + error : '') + '\n';
  }
  const passed = results.filter(result => !result.error).length;
  output.textContent += passed + '/' + results.length + ' passed';
  button.disabled = false;
  if (autorun) {
    await fetch('/results', {method: 'POST', body: JSON.stringify(results)});
  }
}

button.addEventListener('click', main);
if (autorun) await main();
