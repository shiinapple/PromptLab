import {fixtures} from '/security/fixtures.mjs';
import {connection} from '/security/connection.mjs';
import {run} from '/transport.mjs';

const {selected, alternate, canary, sampleText} = fixtures;
const params = new URLSearchParams(location.search);
const base = (origin, scenario) => origin + '/' + scenario + '/v1';
const audit = async () => (await fetch('/security/audit')).json();

function expect(condition, message) {
  if (!condition) throw new Error(message);
}

function choose(client, address) {
  client.propose(address);
  client.confirm();
}

function configured(account, scenario, remember = false) {
  const client = connection(account);
  choose(client, base(selected, scenario));
  client.setKey(canary, remember);
  return client;
}

function storageHasKey(storage) {
  return Object.values(storage).some(value => value.includes(canary));
}

function modelRun(origin, scenario) {
  return run({base: base(origin, scenario), key: canary, model: 'mock-model'});
}

function exportRequest({model, messages}) {
  return JSON.stringify({model, messages});
}

function platformResult({runId, source, text}) {
  return JSON.stringify({runId, source, result: text});
}

async function inNewTab(mode) {
  const id = crypto.randomUUID();
  const channel = new BroadcastChannel(id);
  let deadline;
  try {
    const reply = new Promise((resolve, reject) => {
      deadline = setTimeout(() => reject(new Error('Independent browser tab did not respond')), 4500);
      channel.onmessage = event => resolve(event.data);
    });
    const [result] = await Promise.all([reply, (async () => {
      const response = await fetch('/security/open?' + new URLSearchParams({mode, id}), {method: 'POST'});
      expect(response.ok, 'Chrome could not open an independent test tab');
    })()]);
    expect(result.ok, result.error ?? 'Independent tab assertion failed');
  } finally {
    clearTimeout(deadline);
    channel.close();
  }
}

async function tabTask(mode) {
  if (mode === 'fixed') {
    expect((await modelRun(selected, 'fixed-allowed')).ok, 'Fixed source request failed');
    const other = await modelRun(alternate, 'fixed-denied');
    expect(other.code === 'fetch_or_io_error', 'Fixed policy allowed an unlisted model');
    const {model} = await audit();
    expect(!model.alternate?.some(entry => entry.path.includes('fixed-denied')), 'Unlisted model received a request');
  } else if (mode === 'frame') {
    async function frameDocument(path) {
      const frame = document.createElement('iframe');
      frame.src = path;
      const loaded = new Promise(resolve => frame.addEventListener('load', resolve, {once: true}));
      document.body.append(frame);
      await loaded;
      const doc = frame.contentDocument;
      frame.remove();
      return doc;
    }
    const control = await frameDocument('/security/frame-control');
    expect(control?.title === 'Frame control', 'Unrestricted control could not be framed');
    expect(await frameDocument('/security/fixed') === null, 'Protected page was framed');
  } else if (mode === 'isolated') {
    expect(!storageHasKey(sessionStorage), 'Independent tab inherited session Key');
    expect((await connection('tab-isolation').request()).ok, 'Other tab cannot read saved address');
  } else if (mode === 'refresh') {
    if (!params.has('reloaded')) {
      configured('refresh-default', 'refresh-default');
      configured('refresh-opt-in', 'refresh-opt-in', true);
      params.set('reloaded', '1');
      history.replaceState(null, '', '?' + params);
      location.reload();
      return false;
    }
    expect((await connection('refresh-default').request()).ok, 'Default address did not survive reload');
    expect((await connection('refresh-opt-in').request()).ok, 'Remembered address did not survive reload');
    const {model} = await audit();
    const posts = model.selected.filter(entry => entry.method === 'POST');
    expect(posts.some(entry => entry.path.includes('refresh-default') && !entry.hasAuthorization),
      'Default Key survived a real reload');
    expect(posts.some(entry => entry.path.includes('refresh-opt-in') && entry.authorized),
      'Opt-in Key was lost on reload');
  }
  return true;
}

const cases = [
  ['Only the selected model receives Authorization; platform gets its own cookie', async () => {
    document.cookie = 'poc_session=synthetic; SameSite=Strict; path=/';
    const control = await fetch(selected + '/cookie-control', {credentials: 'include'});
    expect(control.ok, 'Cross-origin cookie control failed');
    const client = configured('primary', 'selected-target');
    expect(!storageHasKey(sessionStorage) && !storageHasKey(localStorage), 'Default Key was persisted');
    const result = await client.request();
    expect(result.ok, 'Selected model call failed');
    const extra = {key: canary, headers: {Authorization: 'Bearer ' + canary}};
    const exportText = exportRequest({model: 'mock-model', messages: [{role: 'user', content: 'Reply OK.'}], ...extra});
    const platformBody = platformResult({runId: 'synthetic-run', source: 'DIRECT', text: result.text, ...extra});
    expect(!exportText.includes(canary) && !platformBody.includes(canary), 'Export or result contained Key');
    const response = await fetch('/security/run', {method: 'POST', body: platformBody});
    expect(response.status === 204, 'Platform rejected the synthetic run');
    const {model, platform} = await audit();
    expect(model.selected.some(entry => entry.path === '/cookie-control' && entry.cookie), 'Control cookie was absent');
    const posts = model.selected.filter(entry => entry.path.includes('selected-target') && entry.method === 'POST');
    expect(posts.length === 1 && posts[0].authorized && !posts[0].cookie, 'Model credential boundary failed');
    expect(platform.some(entry => entry.path === '/security/run' && entry.cookie && !entry.leaked), 'Platform cookie boundary failed');
  }],
  ['Changing Base URL pauses requests and drops the old Key before confirmation', async () => {
    const client = configured('switch-target', 'original-target', true);
    expect((await client.request()).ok, 'Original target request failed');
    client.propose(base(selected, 'original-target') + '/');
    expect((await client.request()).ok, 'Equivalent address disabled the original Key');
    client.propose(base(alternate, 'new-target'));
    expect(!storageHasKey(sessionStorage), 'Old session Key remained after proposing a new target');
    expect((await client.request()).code === 'target_not_confirmed', 'Pending target sent a request');
    let rejected = false;
    try { client.setKey(canary); } catch (error) { rejected = error.message === 'confirm_target_first'; }
    expect(rejected, 'Pending target accepted a Key before confirmation');
    let {model} = await audit();
    expect(!model.alternate?.some(entry => entry.path.includes('new-target')), 'Unconfirmed target saw traffic');
    client.confirm();
    expect((await client.request()).ok, 'Confirmed target could not run without Key');
    client.setKey(canary);
    expect((await client.request()).ok, 'Rebound target failed');
    ({model} = await audit());
    const original = model.selected.filter(entry => entry.path.includes('original-target') && entry.method === 'POST');
    const rebound = model.alternate.filter(entry => entry.path.includes('new-target') && entry.method === 'POST');
    expect(original.length === 2 && original.every(entry => entry.authorized), 'Equivalent URL lost its binding');
    expect(rebound.length === 2 && !rebound[0].hasAuthorization && rebound[1].authorized, 'Old Key crossed target boundary');
  }],
  ['Clear, remove, logout and switch account clear the active and remembered Key', async () => {
    const client = configured('lifecycle', 'life-first', true);
    expect(storageHasKey(sessionStorage), 'Opt-in did not create a session copy');
    client.clear();
    expect(!storageHasKey(sessionStorage) && (await client.request()).ok, 'Explicit clear failed');

    client.setKey(canary, true);
    client.remove();
    expect(!storageHasKey(sessionStorage) && (await client.request()).code === 'target_not_confirmed',
      'Connection removal left active state');
    expect(localStorage.getItem('poc02:config:lifecycle') === null, 'Connection removal left the address');

    choose(client, base(selected, 'life-first'));
    client.setKey(canary, true);
    client.logout();
    expect(!storageHasKey(sessionStorage) && (await client.request()).code === 'target_not_confirmed',
      'Logout left active state');
    expect((await connection('lifecycle').request()).ok, 'Logging back in lost the saved address');

    const switching = configured('lifecycle', 'life-first', true);
    switching.switchAccount('second-user');
    expect(!storageHasKey(sessionStorage) && (await switching.request()).code === 'target_not_confirmed',
      'Second account inherited the previous state');
    choose(switching, base(alternate, 'life-second'));
    expect((await switching.request()).ok, 'Second account could not run its own connection');
    switching.switchAccount('lifecycle');
    expect((await switching.request()).ok, 'First account address was lost after switching back');
    const {model} = await audit();
    const first = model.selected.filter(entry => entry.path.includes('life-first') && entry.method === 'POST');
    const second = model.alternate.filter(entry => entry.path.includes('life-second') && entry.method === 'POST');
    expect(first.length === 3 && second.length === 1 && [...first, ...second].every(entry => !entry.hasAuthorization),
      'Cleared Key was used in a subsequent request');
  }],
  ['Real reload drops the default Key and retains only the opt-in session copy', () => inNewTab('refresh')],
  ['Independently opened tab reads the address without inheriting the session Key', async () => {
    const client = configured('tab-isolation', 'tab-isolation', true);
    try {
      await inNewTab('isolated');
      const {model} = await audit();
      const posts = model.selected.filter(entry => entry.path.includes('tab-isolation') && entry.method === 'POST');
      expect(posts.length === 1 && !posts[0].hasAuthorization, 'Independent tab sent Authorization');
    } finally {
      client.clear();
    }
  }],
  ['Cross-origin redirect stops at the selected model; error object drops reflected text', async () => {
    const redirect = await modelRun(selected, 'redirect');
    expect(redirect.code === 'fetch_or_io_error', 'Redirect was followed');
    const failure = await modelRun(selected, 'error');
    expect(failure.code === 'http_error' && failure.status === 401 && !JSON.stringify(failure).includes(canary),
      'Normalized error kept the reflected response');
    const {model} = await audit();
    expect(model.selected.some(entry => entry.path === '/redirect/v1/chat/completions' && entry.authorized),
      'Redirect control did not reach the selected model');
    expect(!model.alternate?.some(entry => entry.path.includes('redirect-destination')), 'Redirect target saw traffic');
  }],
  ['Model HTML and Markdown remain exact plain text and trigger no resource requests', async () => {
    const result = await modelRun(selected, 'plain-text');
    expect(result.ok, 'Plain-text model fixture failed');
    const view = document.createElement('pre');
    view.textContent = result.text;
    document.body.append(view);
    expect(view.textContent === sampleText && view.childElementCount === 0, 'Renderer parsed or changed the original text');
    await new Promise(resolve => setTimeout(resolve, 100));
    const {platform} = await audit();
    expect(!platform.some(entry => entry.path === '/security/render-probe'), 'Render marker reached the platform');
    view.remove();
  }],
  ['Fixed CSP permits the listed origin and blocks the second local port', () => inNewTab('fixed')],
  ['Frame ancestors policy blocks embedding while the control can be framed', () => inNewTab('frame')],
  ['Scoped CSP allows both listed loopback hosts and blocks an unlisted loopback alias', async () => {
    const alias = alternate.replace('127.0.0.1', 'localhost');
    expect((await modelRun(alias, 'localhost-source')).ok, 'Scoped policy did not permit localhost');
    const events = [];
    const listener = event => events.push(event.effectiveDirective);
    document.addEventListener('securitypolicyviolation', listener);
    try {
      const denied = alternate.replace('127.0.0.1', '127.0.0.2') + '/excluded-scope';
      const probes = [['script', 'src', 'data:text/javascript,void%200'], ['object', 'data', '/security/policy-object']];
      try { await fetch(denied); } catch { /* Blocked by CSP. */ }
      for (const [tag, attribute, value] of probes) {
        const node = document.createElement(tag);
        node[attribute] = value;
        document.body.append(node);
      }
      await new Promise(resolve => setTimeout(resolve, 100));
      for (const directive of ['connect-src', 'script-src-elem', 'object-src']) {
        expect(events.includes(directive), 'No browser violation for ' + directive);
      }
      const response = await fetch(location.pathname);
      const csp = response.headers.get('Content-Security-Policy');
      expect(csp.includes("frame-ancestors 'none'") && csp.includes('https:'), 'Candidate CSP headers are incomplete');
    } finally {
      document.removeEventListener('securitypolicyviolation', listener);
    }
  }],
  ['Platform traffic and audit logs contain no canary; model traffic omits cookies and URL Key', async () => {
    const {platform, model} = await audit();
    expect(platform.length > 0 && platform.every(entry => !entry.leaked), 'Platform observed the Key');
    expect(!storageHasKey(localStorage) && !JSON.stringify({platform, model}).includes(canary),
      'Persistent configuration or audit log exposed the Key');
    const all = Object.values(model).flat();
    expect(all.length > 0 && all.every(entry => !entry.canaryInUrl &&
      (!entry.cookie || entry.path === '/cookie-control')), 'Model URL or cookie crossed the boundary');
  }],
];

if (params.has('mode')) {
  const channel = new BroadcastChannel(params.get('id'));
  try {
    if (await tabTask(params.get('mode'))) channel.postMessage({ok: true});
  } catch (error) {
    channel.postMessage({ok: false, error: error.message});
  } finally {
    channel.close();
  }
} else {
  const output = document.querySelector('#results');
  const results = [];
  for (const [name, test] of cases) {
    let error;
    try { await test(); } catch (failure) {
      error = failure.message.includes(canary) ? 'Canary found in a test error' : failure.message;
    }
    results.push({name, error});
    output.textContent += (error ? 'FAIL ' : 'PASS ') + name + (error ? ' — ' + error : '') + '\n';
  }
  if (params.has('autorun')) await fetch('/results', {method: 'POST', body: JSON.stringify(results)});
}
