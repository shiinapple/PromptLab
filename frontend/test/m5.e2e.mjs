import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { modelMock } from './model-mock.mjs'

const q = JSON.stringify
const node = selector => `document.querySelector(${q(selector)})`
const text = selector => `${node(selector)}?.textContent.trim()`
const value = selector => `${node(selector)}?.value`
const button = label => `[...document.querySelectorAll('button')].find(el => el.textContent.trim() === ${q(label)})`
const runId = "new URL(location.href).searchParams.get('run')"

async function status(browser, label) {
  await browser.waitFor(`${text('#run-status')} === ${q(label)}`, label)
}

async function chooseLocale(browser, locale, { keyboard = false } = {}) {
  const label = locale === 'en' ? 'English' : '中文'
  if (keyboard) await activate(browser, button(label))
  else await browser.clickText(label)
  await browser.waitFor(`document.documentElement.lang === ${q(locale)} && ${text('h1')} === ${q(
    locale === 'en' ? 'Requirements to Test Cases' : '需求转测试用例',
  )}`, `localized problem (${locale})`)
  assert.ok(await browser.page(`document.querySelector('nav button[aria-pressed=true]').textContent.includes(${text('h1')})`))
}

async function key(browser, name, modifiers = 0) {
  const code = name === ' ' ? 'Space' : name
  const windowsVirtualKeyCode = { Tab: 9, Enter: 13, Space: 32, Escape: 27 }[code]
  const params = { key: name, code, windowsVirtualKeyCode, modifiers }
  await browser.cdp('Input.dispatchKeyEvent', {
    ...params, type: name === ' ' || name === 'Enter' ? 'keyDown' : 'rawKeyDown',
    ...(name === ' ' || name === 'Enter' ? { text: name === ' ' ? ' ' : '\r' } : {}),
  })
  await browser.cdp('Input.dispatchKeyEvent', { ...params, type: 'keyUp' })
}

async function tabTo(browser, target, backwards = false) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await browser.page(`document.activeElement === (${target})`)) return
    await key(browser, 'Tab', backwards ? 8 : 0)
  }
  assert.fail(`Keyboard could not reach ${target}; focus: ${await browser.page('document.activeElement.outerHTML.slice(0, 250)')}`)
}

async function activate(browser, target, name = 'Enter') {
  await tabTo(browser, target)
  await key(browser, name)
}

async function type(browser, selector, content) {
  await tabTo(browser, node(selector))
  await browser.cdp('Input.insertText', { text: content })
}

async function viewport(browser, width, height) {
  await browser.cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width === 375 })
  assert.deepEqual(await browser.page('[innerWidth, innerHeight]'), [width, height])
}

async function noOverflow(browser, stage) {
  const [content, window] = await browser.page('[document.documentElement.scrollWidth, innerWidth]')
  assert.ok(content <= window, `${stage}: document ${content}px, viewport ${window}px`)
}

async function inViewport(browser, target) {
  assert.equal(await browser.page(`(() => {
    const el = ${target};
    el.scrollIntoView({ block: 'center', inline: 'nearest' });
    const { left, right, top, bottom, width, height } = el.getBoundingClientRect();
    return width > 0 && height > 0 && left >= 0 && right <= innerWidth && top >= 0 && bottom <= innerHeight
      && el.contains(document.elementFromPoint((left + right) / 2, (top + bottom) / 2));
  })()`), true, `visible and unobstructed: ${target}`)
}

async function configure(browser, mock, { keyboard = false, english = false, stream = false } = {}) {
  await browser.cdp('Page.bringToFront')
  if (keyboard) await activate(browser, node('#settings-toggle'))
  else await browser.page("document.querySelector('#settings-toggle').click()")
  await browser.waitFor("document.activeElement.id === 'model-name'", 'settings focus moves into form')
  const edit = keyboard ? (selector, input) => type(browser, selector, input) : browser.change
  for (const [selector, input] of [['#model-base', mock.origin + '/api'], ['#model-id', 'example-model']]) {
    if (await browser.page(value(selector)) !== input) await edit(selector, input)
  }
  const streamChecked = await browser.page("document.querySelector('#model-stream').checked")
  if (streamChecked !== stream) {
    if (keyboard) await activate(browser, node('#model-stream'), ' ')
    else await browser.page("document.querySelector('#model-stream').click()")
  }
  const save = english ? 'Save connection' : '保存连接'
  if (keyboard) await activate(browser, button(save))
  else await browser.clickText(save)
  await browser.waitFor(`${text('#model-feedback')} === ${q(english ? 'Connection settings saved.' : '连接设置已保存。')}`, 'mock connection saved')
  if (keyboard) await key(browser, 'Escape')
  else await browser.clickText(english ? 'Back to practice' : '返回练习')
  await browser.waitFor("document.activeElement.id === 'settings-toggle'", 'settings returns focus to trigger')
}

async function startDirect(browser, english, keyboard = false) {
  const before = await browser.page(runId)
  const label = english ? 'Run and grade' : '运行并判题'
  if (keyboard) await activate(browser, button(label))
  else await browser.clickText(label)
  await browser.waitFor(`Boolean(${runId}) && ${runId} !== ${q(before)}`, 'direct run frozen')
}

async function startD0(browser, label, keyboard = false) {
  const before = await browser.page(runId)
  if (keyboard) await activate(browser, button(label))
  else await browser.clickText(label)
  await browser.waitFor(`Boolean(${runId}) && ${runId} !== ${q(before)} && !!document.querySelector('#raw')`, 'new D0 run frozen')
}

async function submitD0(browser, raw, { keyboard = false, mobile = false } = {}) {
  if (keyboard) {
    await type(browser, '#raw', raw)
    await activate(browser, node('#confirm'), ' ')
    await activate(browser, node('#grade'))
  } else {
    await browser.page(`(() => {
      const clipboardData = new DataTransfer();
      clipboardData.setData('text/plain', ${q(raw)});
      document.querySelector('#raw').dispatchEvent(new ClipboardEvent('paste', { clipboardData, cancelable: true }));
    })()`)
    await browser.page("document.querySelector('#confirm').click()")
    if (mobile) await inViewport(browser, node('#grade'))
    await browser.page("document.querySelector('#grade').click()")
  }
  await browser.waitFor(`${text('#graded-raw')} === ${q(raw.trim())}`, 'graded original response')
}

function requests(browser, start, predicate) {
  return browser.requests.slice(start).filter(({ url, method }) => predicate(new URL(url), method))
}

async function verifyLocale(browser, mock, origin) {
  const first = await browser.page(`fetch('/api/settings').then(r => r.json())`)
  assert.equal(first.locale, 'zh-CN')

  for (const path of ['/api/problems', '/api/problems/002']) {
    for (const locale of ['', 'fr']) {
      const result = await browser.page(`fetch(${q(`${path}?locale=${locale}`)}).then(async r => [r.status, (await r.json()).code])`)
      assert.deepEqual(result, [400, 'INVALID_LOCALE'], path)
    }
  }

  for (const locale of ['', 'fr', 42]) {
    const result = await browser.page(`(async () => {
      const { token, headerName } = await fetch('/api/auth/csrf').then(r => r.json());
      const response = await fetch('/api/settings', {
        method: 'PATCH', headers: { [headerName]: token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ locale: ${q(locale)} })
      });
      const { code, messageKey } = await response.json();
      return [response.status, code, messageKey];
    })()`)
    assert.equal(result[0], 400, 'invalid settings locale HTTP status')
    assert.equal(result[1], 'INVALID_LOCALE')
    assert.match(result[2], /^errors\./)
  }

  await browser.clickText('用例 2')
  await browser.clickText('创建运行')
  await status(browser, '等待导入')
  const frozenId = await browser.page(runId)
  const frozen = await browser.page(text('#frozen-request'))
  const record = await browser.page(`fetch('/api/runs/' + ${runId}).then(r => r.json())`)
  assert.equal(record.caseId, 'case-2')
  const draft = '未保存  Fibonacci 🍎\n  {{requirement}}\t  '

  // Hold the editor's existing debounce across both switches. Network timing must
  // not decide whether an independently scheduled autosave lands in this window.
  await browser.page(`(() => {
    const schedule = window.setTimeout;
    window.setTimeout = function (callback, delay, ...args) {
      if (delay !== 600) return schedule.call(window, callback, delay, ...args);
      window.releaseDraftSave = () => {
        window.setTimeout = schedule;
        delete window.releaseDraftSave;
        callback(...args);
      };
      return -1;
    };
  })()`)
  const start = browser.requests.length
  const modelRequests = mock.records.length
  await browser.change('#prompt', draft)
  await browser.waitFor("typeof window.releaseDraftSave === 'function'", 'unsaved draft is pending')
  const preview = await browser.page(text('#preview'))
  for (const locale of ['en', 'zh-CN']) {
    await chooseLocale(browser, locale)
    assert.equal(await browser.page(value('#prompt')), draft)
    assert.match(await browser.page(text('#problem-body .meta:last-child')), /case-2$/)
    assert.equal(await browser.page(text('#preview')), preview)
    assert.equal(await browser.page(runId), frozenId)
    assert.equal(await browser.page(text('#frozen-request')), frozen)
  }
  assert.deepEqual(requests(browser, start, url => url.pathname.endsWith('/draft')), [])
  assert.deepEqual(requests(browser, start, (url, method) => url.origin === origin && url.pathname.startsWith('/api/runs')
    && method === 'POST'), [])
  assert.equal(mock.records.length, modelRequests)
  assert.deepEqual(await browser.page(`fetch('/api/runs/' + ${runId}).then(r => r.json())`), record)
  await browser.page('window.releaseDraftSave()')
  await browser.waitFor(`fetch('/api/problems/002/draft').then(r => r.json()).then(({ draft }) => draft?.slots.user === ${q(draft)})`, 'ordinary draft autosave')

  await chooseLocale(browser, 'en')
  assert.equal((await browser.page("fetch('/api/settings').then(r => r.json())")).locale, 'en')
  const beforeReload = browser.requests.length
  await browser.page("localStorage.setItem('promptlab.locale', 'zh-CN')")
  await browser.cdp('Page.reload')
  await browser.waitFor("document.documentElement.lang === 'en' && document.querySelector('nav button[aria-pressed=true]')?.textContent.includes('Requirements to Test Cases')", 'server locale wins after reload')
  assert.ok(requests(browser, beforeReload, (url, method) => url.pathname === '/api/settings' && method === 'GET').length > 0)
  const afterReload = await browser.page(value('#prompt'))
  await browser.page(`(() => {
    const fetch = window.fetch;
    window.fetch = (path, options) => path === '/api/settings' && options?.method === 'PATCH'
      ? Promise.resolve(new Response('gateway unavailable', { status: 503 }))
      : fetch(path, options);
    window.restoreSettingsFetch = () => { window.fetch = fetch; delete window.restoreSettingsFetch; };
  })()`)
  await chooseLocale(browser, 'zh-CN')
  assert.match(await browser.page(text('[role=alert]')), /语言已切换，但偏好保存失败/)
  assert.equal(await browser.page(value('#prompt')), afterReload)
  assert.equal((await browser.page("fetch('/api/settings').then(r => r.json())")).locale, 'en')
  await browser.page('window.restoreSettingsFetch()')
  await chooseLocale(browser, 'en')
  await chooseLocale(browser, 'zh-CN')
}

async function verifyGuide(browser, mock, raw, origin) {
  assert.equal(await browser.page(text('.guide h2')), '开始练习')
  assert.equal(await browser.page(`!!${button('配置模型直连')}`), true)
  await browser.clickText('使用手动运行')
  await startD0(browser, '新建运行')
  await status(browser, '等待导入')
  await browser.cdp('Page.bringToFront')
  await browser.browserCdp('Browser.grantPermissions', { origin, permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] })
  const frozen = JSON.parse(await browser.page(text('#frozen-request')))
  await activate(browser, button('复制请求'))
  await browser.waitFor("[...document.querySelectorAll('[role=status]')].some(el => el.textContent.trim() === '请求已复制。')", 'D0 exported request')
    .catch(async error => {
      throw new Error(`${error.message}; focused=${await browser.page('document.hasFocus()')}; alert=${await browser.page(text('[role=alert]'))}; status=${await browser.page(text('[role=status]'))}`)
    })
  assert.deepEqual(JSON.parse(await browser.page('navigator.clipboard.readText()')), frozen)
  await submitD0(browser, raw)
  await status(browser, '通过')

  await browser.clickText('查看引导')
  await browser.clickText('关闭引导')
  await browser.cdp('Page.reload')
  await status(browser, '通过')
  assert.equal(await browser.page("document.querySelector('.guide')"), null)
  await browser.clickText('查看引导')
  assert.equal(await browser.page(text('.guide h2')), '开始练习')
  await browser.clickText('关闭引导')

  await configure(browser, mock)
  const draft = await browser.page(value('#prompt'))
  for (const [behavior, explanation] of [['preflight', '无法读取'], [401, '拒绝鉴权']]) {
    const before = mock.records.length
    mock.behavior(behavior)
    await browser.clickText('浏览器直连')
    await startDirect(browser, false)
    await status(browser, '执行失败')
    assert.match(await browser.page(text('[role=alert]')), new RegExp(explanation))
    assert.ok(await browser.page(text('.hints')))
    const records = mock.records.slice(before)
    assert.ok(records.some(row => row.method === 'OPTIONS'))
    assert.equal(records.filter(row => row.method === 'POST').length, behavior === 401 ? 1 : 0)
    const failed = await browser.page(runId)
    await browser.clickText('转手动运行')
    assert.equal(await browser.page(value('#prompt')), draft)
    await startD0(browser, '新建运行')
    await status(browser, '等待导入')
    assert.ok(await browser.page("document.querySelector('#raw') !== null"))
    assert.notEqual(await browser.page(runId), failed)
  }
}

async function disclosure(browser, expanded) {
  assert.deepEqual(await browser.page(`(() => {
    const toggle = document.querySelector('[aria-controls=problem-body]');
    const panel = document.getElementById(toggle.getAttribute('aria-controls'));
    return [toggle.getAttribute('aria-expanded'), panel.getClientRects().length > 0];
  })()`), [String(expanded), expanded])
}

async function verifyResponsive(browser, app) {
  for (const [width, height] of [[375, 812], [1280, 800]]) {
    await viewport(browser, width, height)
    await browser.go(app)
    await browser.waitFor("document.querySelector('#prompt') && document.querySelector('#history-heading')", 'workspace and history loaded')
    await browser.clickText('查看引导')
    for (const part of ['nav', '.guide', '#history-heading']) {
      assert.ok(await browser.page(`${node(part)}?.getClientRects().length > 0`), part)
      await noOverflow(browser, `${width}px ${part}`)
    }
    const toggle = node('[aria-controls=problem-body]')
    const mobile = width === 375
    await disclosure(browser, !mobile)
    if (mobile) {
      await inViewport(browser, toggle)
      await browser.page(`${toggle}.click()`)
      await disclosure(browser, true)
      await noOverflow(browser, 'expanded problem')
      await browser.page(`${toggle}.click()`)
      await disclosure(browser, false)
      await inViewport(browser, button('English'))
      await chooseLocale(browser, 'en')
    }
    await browser.clickText(mobile ? 'Close guide' : '关闭引导')
    await browser.clickText(mobile ? 'Manual run' : '手动运行')
    const create = button(mobile ? 'Create run' : '创建运行')
    await inViewport(browser, create)
    await browser.page(`${create}.click()`)
    await status(browser, mobile ? 'Awaiting import' : '等待导入')
    await noOverflow(browser, `${width}px D0 panel`)
    for (const target of [button(mobile ? 'Copy request' : '复制请求'), button(mobile ? 'Download request' : '下载请求'), node('#response-file')]) {
      await inViewport(browser, target)
    }
    await submitD0(browser, JSON.stringify({ cases: [], long: 'x'.repeat(4096) }), { mobile })
    await noOverflow(browser, `${width}px long JSON result`)
    await inViewport(browser, node('#settings-toggle'))
    await browser.page("document.querySelector('#settings-toggle').click()")
    assert.ok(await browser.page("document.querySelector('#model-settings')?.getClientRects().length > 0"))
    await noOverflow(browser, `${width}px model settings`)
    await browser.clickText(mobile ? 'Back to practice' : '返回练习')
    if (mobile) await chooseLocale(browser, 'zh-CN')
  }
}

async function focusVisible(browser, target) {
  assert.equal(await browser.page(`(() => {
    const el = ${target};
    const style = getComputedStyle(el);
    return el === document.activeElement && el.matches(':focus-visible')
      && style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0;
  })()`), true, `visible keyboard focus: ${target}`)
}

async function accessibleControls(browser, ids) {
  const { nodes } = await browser.cdp('Accessibility.getFullAXTree')
  for (const id of ids) {
    const { nodeId } = await browser.cdp('DOM.getDocument').then(({ root }) =>
      browser.cdp('DOM.querySelector', { nodeId: root.nodeId, selector: '#' + id }))
    assert.notEqual(nodeId, 0, id)
    const { node: { backendNodeId } } = await browser.cdp('DOM.describeNode', { nodeId })
    assert.ok(nodes.some(ax => ax.backendDOMNodeId === backendNodeId && !ax.ignored && ax.name?.value), id)
  }
}

async function labels(browser, fields) {
  for (const field of fields) {
    assert.ok(await browser.page(`[...document.getElementById(${q(field)}).labels].some(label => label.textContent.trim())`), field)
  }
}

async function verifyKeyboard(browser, mock, raw, origin) {
  await browser.cdp('Page.bringToFront')
  await viewport(browser, 1280, 800)
  await activate(browser, button('002 · 需求转测试用例'))
  await browser.waitFor(`${text('h1')} === '需求转测试用例'`, 'selected problem by keyboard')
  await chooseLocale(browser, 'en', { keyboard: true })
  const addition = '\nUse JSON only.  '
  await tabTo(browser, node('#prompt'))
  await focusVisible(browser, node('#prompt'))
  await type(browser, '#prompt', addition)
  const draft = await browser.page(value('#prompt'))
  assert.ok(draft.includes(addition))
  await activate(browser, button('Manual run'), ' ')
  await startD0(browser, 'Create run', true)
  await status(browser, 'Awaiting import')
  await labels(browser, ['prompt', 'raw', 'confirm', 'response-file'])
  await accessibleControls(browser, ['prompt', 'raw', 'confirm', 'response-file', 'grade'])
  await browser.browserCdp('Browser.grantPermissions', { origin, permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] })
  await activate(browser, button('Copy request'))
  await browser.waitFor("[...document.querySelectorAll('[role=status]')].some(el => el.textContent.trim() === 'Request copied.')", 'keyboard copied D0 request')
  assert.deepEqual(JSON.parse(await browser.page('navigator.clipboard.readText()')), JSON.parse(await browser.page(text('#frozen-request'))))
  await submitD0(browser, raw, { keyboard: true })
  await status(browser, 'Passed')

  mock.behavior('normal')
  await configure(browser, mock, { english: true, keyboard: true })
  await activate(browser, button('Direct connection'))
  await startDirect(browser, true, true)
  await status(browser, 'Passed')
  assert.equal(JSON.parse(mock.records.filter(row => row.method === 'POST').at(-1).body).stream, false)
  assert.equal(await browser.page(text('#graded-raw')), raw.trim())

  await configure(browser, mock, { english: true, keyboard: true, stream: true })
  await startDirect(browser, true, true)
  await status(browser, 'Passed')
  assert.equal(JSON.parse(mock.records.filter(row => row.method === 'POST').at(-1).body).stream, true)

  mock.behavior('stall')
  const disconnected = mock.disconnected()
  await startDirect(browser, true, true)
  await browser.waitFor(`${text('#direct-text')}?.includes('cases')`, 'partial SSE response')
  await activate(browser, button('Stop request'), ' ')
  await status(browser, 'Canceled')
  assert.equal(mock.disconnected(), disconnected + 1)

  mock.behavior(401)
  await startDirect(browser, true, true)
  await status(browser, 'Request failed')
  await activate(browser, button('Switch to manual run'))
  assert.equal(await browser.page(value('#prompt')), draft)
  await startD0(browser, 'New run', true)
  await status(browser, 'Awaiting import')
  await submitD0(browser, raw, { keyboard: true })
  await status(browser, 'Passed')
}

async function verifyCombined(browser, mock) {
  await viewport(browser, 375, 812)
  await browser.cdp('Page.reload')
  await status(browser, 'Passed')
  const draft = await browser.page(value('#prompt'))
  // Reach both sides of the disclosure and settings using forward and reverse Tab.
  const toggle = node('[aria-controls=problem-body]')
  await tabTo(browser, toggle)
  await focusVisible(browser, toggle)
  await key(browser, ' ')
  await disclosure(browser, true)
  await tabTo(browser, node('#prompt'))
  await tabTo(browser, toggle, true)
  await key(browser, 'Enter')
  await disclosure(browser, false)
  await chooseLocale(browser, 'zh-CN', { keyboard: true })
  await chooseLocale(browser, 'en', { keyboard: true })
  assert.equal(await browser.page(value('#prompt')), draft)
  await noOverflow(browser, 'keyboard and locale on narrow screen')

  await activate(browser, node('#settings-toggle'))
  await labels(browser, ['model-name', 'model-base', 'model-id', 'model-key', 'remember-key', 'model-stream'])
  await accessibleControls(browser, ['model-name', 'model-base', 'model-id', 'model-key', 'remember-key', 'model-stream'])
  assert.equal(await browser.page(`${node('#settings-toggle')}.getAttribute('aria-expanded')`), 'true')
  // Settings is inline. Tabbing through it must reach the workspace.
  assert.equal(await browser.page(`${node('#settings-toggle')}.getAttribute('aria-controls')`), 'model-settings')
  await tabTo(browser, node('#prompt'))
  await tabTo(browser, node('#model-name'), true)
  await key(browser, 'Escape')
  assert.equal(await browser.page("document.activeElement.id"), 'settings-toggle')
  await focusVisible(browser, node('#settings-toggle'))
  assert.equal(await browser.page(`${node('#settings-toggle')}.getAttribute('aria-expanded')`), 'false')

  await browser.page(`(() => {
    window.liveUpdates = [];
    new MutationObserver(() => {
      for (const el of document.querySelectorAll('[role=status], [role=alert]')) {
        const entry = [el.getAttribute('role'), el.getAttribute('aria-live'), el.textContent.trim()];
        if (!window.liveUpdates.some(previous => previous.join() === entry.join())) window.liveUpdates.push(entry);
      }
    }).observe(document.querySelector('main'), { subtree: true, childList: true, characterData: true });
  })()`)
  await activate(browser, button('Direct connection'))
  mock.behavior('stall')
  await startDirect(browser, true, true)
  await status(browser, 'Requesting model')
  await browser.waitFor("[...document.querySelectorAll('[role=status]')].some(el => /^[1-9]\\d* s$/.test(el.textContent.trim()))", 'elapsed status updates')
  await activate(browser, button('Stop request'))
  await status(browser, 'Canceled')
  mock.behavior(401)
  await startDirect(browser, true, true)
  await status(browser, 'Request failed')
  const updates = await browser.page('window.liveUpdates')
  for (const message of ['Requesting model', 'Canceled', 'Request failed']) {
    assert.ok(updates.some(([role, live, label]) => role === 'status' && live === 'polite' && label === message), message)
  }
  assert.ok(updates.some(([role, live, label]) => role === 'alert' && live === 'assertive' && label.includes('denied access')))
  await noOverflow(browser, 'narrow screen with live model error')
  await tabTo(browser, button('Switch to manual run'))
  await focusVisible(browser, button('Switch to manual run'))
  await inViewport(browser, button('Switch to manual run'))
  await tabTo(browser, node('#settings-toggle'), true)
}

async function checkCopy(browser, english, stage) {
  const copy = await browser.page(`(() => {
    const root = document.body.cloneNode(true);
    // Public case inputs and request/response payloads are authored data.
    // Their content is checked independently in the locale and run scenarios.
    root.querySelectorAll('.case-text, pre, script, style, [lang=zh-CN]').forEach(el => el.remove());
    document.body.append(root);
    const rendered = [root.innerText, ...[...root.querySelectorAll('[aria-label], [title], [placeholder]')]
      .flatMap(el => ['aria-label', 'title', 'placeholder'].map(attr => el.getAttribute(attr)))].filter(Boolean).join('\\n');
    root.remove();
    return rendered;
  })()`)
  assert.doesNotMatch(copy, /\berrors\.[\w.]+/, stage)
  if (english) assert.doesNotMatch(copy, /\p{Script=Han}/u, stage)
}

async function verifyCopy(browser, mock, raw) {
  for (const locale of ['zh-CN', 'en']) {
    const english = locale === 'en'
    await chooseLocale(browser, locale)
    await browser.clickText(english ? 'Show guide' : '查看引导')
    await checkCopy(browser, english, `${locale} catalog, guide, editor and history`)
    await browser.clickText(english ? 'Close guide' : '关闭引导')
    await browser.page("document.querySelector('#settings-toggle').click()")
    await browser.clickText(english ? 'List models' : '获取模型列表')
    await browser.waitFor("document.querySelector('#available-models')?.options.length > 1", 'available models populated')
    await labels(browser, ['available-models'])
    await accessibleControls(browser, ['available-models', 'settings-toggle'])
    await checkCopy(browser, english, `${locale} model settings`)
    await browser.clickText(english ? 'Back to practice' : '返回练习')
    await browser.clickText(english ? 'Manual run' : '手动运行')
    await startD0(browser, english ? 'New run' : '新建运行')
    await status(browser, english ? 'Awaiting import' : '等待导入')
    await checkCopy(browser, english, `${locale} D0`)
    await submitD0(browser, raw)
    await status(browser, english ? 'Passed' : '通过')
    await checkCopy(browser, english, `${locale} D0 result and history`)
    await browser.clickText(english ? 'Direct connection' : '浏览器直连')
    for (const behavior of ['normal', 401]) {
      mock.behavior(behavior)
      await startDirect(browser, english)
      await status(browser, behavior === 'normal' ? (english ? 'Passed' : '通过') : (english ? 'Request failed' : '执行失败'))
      await checkCopy(browser, english, `${locale} direct ${behavior}`)
    }
  }
}

export async function verifyM5({ openContext, signIn, accounts, origin, app, root }) {
  const output = async caseId => {
    const fixtures = JSON.parse(await readFile(join(root, `content/problems/002/v2/fixtures/${caseId}.json`), 'utf8'))
    return fixtures[0].rawOutput
  }
  const case1 = await output('case-1')
  const case2 = await output('case-2')
  const mock = await modelMock(origin, () => case1)
  try {
    const alice = await openContext()
    await alice.go(app)
    await signIn(alice, accounts.alice)
    await verifyLocale(alice, mock, origin)
    console.log('PASS M5 A1 language switching, frozen run, unsaved draft and real settings API')
    await verifyGuide(alice, mock, case2, origin)
    console.log('PASS M5 A2 zero-config D0, preflight and HTTP 401 fallback, guide persistence')
    await verifyResponsive(alice, app)
    console.log('PASS M5 A3 375×812 and 1280×800 layouts, long JSON and reachable D0 controls')
    const bob = await openContext()
    await bob.go(origin)
    await signIn(bob, accounts.bob)
    await verifyKeyboard(bob, mock, case1, origin)
    console.log('PASS M5 A4 keyboard D0, ordinary/SSE direct, stop, fallback and settings focus')
    await verifyCombined(bob, mock)
    console.log('PASS M5 A5 narrow keyboard flow, draft, live regions, focus and tab traversal')
    mock.behavior('normal')
    await verifyCopy(bob, mock, case1)
    console.log('PASS M5 A6 zh-CN and en catalog, settings, history, D0 and direct success/failure copy')
  } finally {
    await mock.close()
  }
}
