import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {createServer} from 'node:http';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {modelMock} from './mock.mjs';

const headless = process.argv.includes('--headless');
const address = '127.0.0.1';
let trace = Object.create(null);
let pageOrigin;
let mockOrigin;
let report;
const staticFiles = new Map([
  ['/', ['index.html', 'text/html']],
  ['/cases.mjs', ['cases.mjs', 'text/javascript']],
  ['/transport.mjs', ['transport.mjs', 'text/javascript']],
  ['/eventsource-parser.mjs', ['node_modules/eventsource-parser/dist/index.js', 'text/javascript']],
]);

const page = createServer(async (request, response) => {
  if (request.headers.host !== new URL(pageOrigin).host ||
      (request.headers.origin && request.headers.origin !== pageOrigin)) {
    response.writeHead(403).end();
    return;
  }
  try {
    if (request.url === '/results' && request.method === 'POST' && headless) {
      let body = '';
      for await (const chunk of request) {
        body += chunk;
        if (body.length > 65536) {
          response.writeHead(413).end();
          return;
        }
      }
      const results = JSON.parse(body);
      response.writeHead(204).end();
      report(results);
      return;
    }
    if (request.url === '/trace' && request.method === 'DELETE') {
      trace = Object.create(null);
      response.writeHead(204).end();
      return;
    }
    const path = new URL(request.url, pageOrigin).pathname;
    if (request.method !== 'GET') {
      response.writeHead(405).end();
    } else if (path === '/trace') {
      response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(trace));
    } else if (path === '/config.mjs') {
      response.writeHead(200, {'Content-Type': 'text/javascript'})
        .end('export const base = ' + JSON.stringify(mockOrigin) + ';');
    } else if (staticFiles.has(path)) {
      const [file, type] = staticFiles.get(path);
      const contents = await readFile(new URL(file, import.meta.url));
      response.writeHead(200, {
        'Content-Type': type,
        'Content-Security-Policy': "default-src 'none'; script-src 'self'; connect-src 'self' " + mockOrigin,
        'X-Content-Type-Options': 'nosniff',
      }).end(contents);
    } else {
      response.writeHead(404).end();
    }
  } catch {
    response.writeHead(500).end();
  }
});
const mock = createServer((request, response) => modelMock(pageOrigin, trace)(request, response));

async function listen(server) {
  server.listen(0, address);
  await once(server, 'listening');
  return 'http://' + address + ':' + server.address().port;
}

async function testChrome() {
  const chrome = process.env.POC_CHROME || (process.platform === 'darwin'
    ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
    : 'google-chrome');
  const profile = await mkdtemp(join(tmpdir(), 'prompt-practice-chrome-'));
  const browser = spawn(chrome, [
    '--headless', '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--user-data-dir=' + profile, pageOrigin + '/?autorun',
  ], {stdio: ['ignore', 'ignore', 'pipe']});
  let diagnostics = '';
  browser.stderr.on('data', chunk => { diagnostics = (diagnostics + chunk).slice(-4000); });
  let deadline;
  try {
    const results = await Promise.race([
      new Promise(resolve => { report = resolve; }),
      once(browser, 'exit').then(([code]) => { throw new Error('Chrome exited early: ' + code); }),
      new Promise((_, reject) => {
        deadline = setTimeout(() => reject(new Error('Chrome did not complete the tests')), 20_000);
      }),
    ]);
    const failures = results.filter(result => result.error);
    for (const {name, error} of results) {
      console.log(error ? 'FAIL ' + name + ' — ' + error : 'PASS ' + name);
    }
    console.log((results.length - failures.length) + '/' + results.length + ' passed');
    if (results.length === 0 || failures.length > 0) process.exitCode = 1;
  } catch (error) {
    console.error(diagnostics);
    throw error;
  } finally {
    clearTimeout(deadline);
    if (browser.pid && browser.exitCode === null && browser.signalCode === null) {
      const exited = once(browser, 'exit');
      browser.kill();
      await exited;
    }
    await rm(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
  }
}

try {
  pageOrigin = await listen(page);
  mockOrigin = await listen(mock);
  console.log('Page: ' + pageOrigin + ' | Mock: ' + mockOrigin);
  if (headless) await testChrome();
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  if (headless || process.exitCode) {
    for (const server of [page, mock]) {
      server.close();
      server.closeAllConnections();
    }
  }
}
