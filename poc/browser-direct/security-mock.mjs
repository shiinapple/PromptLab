import {randomUUID} from 'node:crypto';

export const sampleText =
  ' <em>literal HTML</em>\n<img src="/security/render-probe" onload="void 0">\n' +
  '[local link](/security/render-probe)\n![local image](/security/render-probe)  ';

export function securityFixtures() {
  const canary = 'synthetic-poc02-' + randomUUID();
  const modelTrace = {};
  const platformTrace = [];
  let pageOrigin;
  let selected;
  let alternate;

  function policy(exact) {
    const allowed = exact ? selected : 'https: http://127.0.0.1:* http://localhost:*';
    return "default-src 'none'; script-src 'self'; connect-src 'self' " + allowed +
      "; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
  }

  function containsCanary(value) {
    return JSON.stringify(value).includes(canary);
  }

  function recordPlatform(request, body) {
    const leaked = containsCanary([request.url, request.headers, body]);
    platformTrace.push({
      path: new URL(request.url, pageOrigin).pathname.replaceAll(canary, '[redacted]'),
      method: request.method,
      leaked,
      cookie: Boolean(request.headers.cookie),
    });
    return leaked;
  }

  return {
    configure(origin, [first, second]) {
      pageOrigin = origin;
      selected = first;
      alternate = second;
    },
    observeReport(request, body) {
      if (recordPlatform(request, body)) throw new Error('Canary reached the platform report');
    },
    async platform(request, response) {
      const path = new URL(request.url, pageOrigin).pathname;
      if (path === '/security/run' && request.method === 'POST') {
        let body = '';
        for await (const chunk of request) {
          body += chunk;
          if (body.length > 4096) {
            response.writeHead(413).end();
            return true;
          }
        }
        const leaked = recordPlatform(request, body);
        response.writeHead(leaked ? 400 : 204).end();
        return true;
      }
      recordPlatform(request);
      if (request.method !== 'GET') return false;
      if (['/security/', '/security/context', '/security/fixed', '/security/frame-parent'].includes(path)) {
        const csp = policy(path === '/security/fixed') + (path === '/security/frame-parent' ? "; frame-src 'self'" : '');
        response.writeHead(200, {
          'Content-Type': 'text/html',
          'Content-Security-Policy': csp,
          'X-Content-Type-Options': 'nosniff',
        }).end('<!doctype html><meta charset="utf-8"><title>POC-02</title>' +
          '<main><h1>POC-02 本机验证</h1><pre id="results"></pre></main>' +
          '<script type="module" src="/security/cases.mjs"></script>');
      } else if (path === '/security/fixtures.mjs') {
        response.writeHead(200, {'Content-Type': 'text/javascript'}).end(
          'export const fixtures = ' + JSON.stringify({selected, alternate, canary, sampleText}) + ';');
      } else if (path === '/security/audit') {
        response.writeHead(200, {'Content-Type': 'application/json'})
          .end(JSON.stringify({platform: platformTrace, model: modelTrace}));
      } else if (path === '/security/frame-control') {
        response.writeHead(200, {'Content-Type': 'text/html', 'Content-Security-Policy': "default-src 'none'"})
          .end('<!doctype html><title>Frame control</title>');
      } else if (path === '/security/render-probe') {
        response.writeHead(204).end();
      } else {
        return false;
      }
      return true;
    },
    model(target) {
      return (request, response) => {
        const {pathname} = new URL(request.url, pageOrigin);
        const trace = modelTrace[target] ??= [];
        trace.push({
          path: pathname.replaceAll(canary, '[redacted]'),
          method: request.method,
          authorized: request.headers.authorization === 'Bearer ' + canary,
          hasAuthorization: Boolean(request.headers.authorization),
          cookie: Boolean(request.headers.cookie),
          canaryInUrl: containsCanary(request.url),
        });
        if (request.headers.origin !== pageOrigin) {
          response.writeHead(403).end();
          return;
        }
        const headers = {'Access-Control-Allow-Origin': pageOrigin, Vary: 'Origin'};
        if (request.method === 'OPTIONS') {
          response.writeHead(204, {
            ...headers,
            'Access-Control-Allow-Headers': 'authorization, content-type',
            'Access-Control-Allow-Methods': 'POST',
          }).end();
        } else if (pathname === '/cookie-control' && request.method === 'GET') {
          response.writeHead(204, {...headers, 'Access-Control-Allow-Credentials': 'true'}).end();
        } else if (request.method !== 'POST' || !pathname.endsWith('/v1/chat/completions')) {
          response.writeHead(405, headers).end();
        } else if (pathname.startsWith('/redirect/')) {
          response.writeHead(307, {
            ...headers, Location: alternate + '/redirect-destination/v1/chat/completions',
          }).end();
        } else if (pathname.startsWith('/error/')) {
          response.writeHead(401, {...headers, 'Content-Type': 'application/json'})
            .end(JSON.stringify({message: 'synthetic failure', detail: canary}));
        } else {
          response.writeHead(200, {...headers, 'Content-Type': 'application/json'}).end(JSON.stringify({
            choices: [{message: {content: sampleText}, finish_reason: 'stop'}],
          }));
        }
      };
    },
  };
}
