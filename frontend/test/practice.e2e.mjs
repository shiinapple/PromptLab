import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { access, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'
import { setTimeout as sleep } from 'node:timers/promises'
import { randomUUID } from 'node:crypto'
import { modelMock } from './model-mock.mjs'

const root = fileURLToPath(new URL('../../', import.meta.url))
const temp = await mkdtemp(join(tmpdir(), 'promptlab-m2-e2e-'))
const children = []
const mocks = []
let socket

function start(command, args, options = {}) {
  const child = spawn(command, args, { ...options, stdio: ['ignore', 'pipe', 'pipe'] })
  const entry = { child, command, log: '' }
  children.push(entry)
  child.on('error', err => { entry.log += err.message })
  for (const stream of [child.stdout, child.stderr]) {
    stream.on('data', data => { entry.log = (entry.log + data).slice(-12_000) })
  }
  return child
}

async function until(check, label, attempts = 100) {
  for (let i = 0; i < attempts; i++) {
    try {
      const value = await check()
      if (value) return value
    } catch (err) {
      if (!(err instanceof TypeError && err.message === 'fetch failed')) throw err
    }
    await sleep(100)
  }
  throw new Error(`Timed out: ${label}`)
}

async function freePort() {
  const listener = createServer().listen(0, '127.0.0.1')
  await once(listener, 'listening')
  const { port } = listener.address()
  await new Promise((resolve, reject) => listener.close(err => err ? reject(err) : resolve()))
  return port
}

try {
  const apiPort = await freePort()
  start('java', ['-jar', join(root, 'backend/target/prompt-practice-0.1.0-SNAPSHOT.jar'),
    '--spring.profiles.active=local-practice', `--server.port=${apiPort}`,
    `--prompt-practice.database.path=${join(temp, 'practice.sqlite3')}`], { cwd: temp })
  await until(async () => (await fetch(`http://127.0.0.1:${apiPort}/api/health`)).ok, 'Spring readiness')
  const webPort = await freePort()
  const origin = `http://127.0.0.1:${webPort}`
  const frontend = join(root, 'frontend')
  const snippet = await readFile(join(root, 'deploy/nginx/csp.conf'), 'utf8')
  const policy = snippet.match(/add_header Content-Security-Policy "([^"]+)" always;/)[1]
  function startPreview(port, csp) {
    start(process.execPath, ['--input-type=module', '-e',
      'import { preview } from "vite"; await preview({ preview: { port: Number(process.env.TEST_PORT), strictPort: true, headers: { "Content-Security-Policy": process.env.TEST_CSP } } });'],
    { cwd: frontend, env: {
      ...process.env, PROMPT_PRACTICE_API_PORT: String(apiPort), TEST_CSP: csp, TEST_PORT: String(port),
    } })
  }
  startPreview(webPort, policy)
  await until(async () => (await fetch(`${origin}/api/problems/002`)).ok, 'built Vite preview and proxy')
  assert.equal((await fetch(origin)).headers.get('content-security-policy'), policy)

  const chrome = process.env.CHROME_BIN ?? (process.platform === 'darwin'
    ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome')
  const cdpPort = await freePort()
  start(chrome, ['--headless', '--disable-background-networking', '--no-first-run',
    `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${join(temp, 'chrome')}`, 'about:blank'])
  async function chromeJson(path, options) {
    return (await fetch(`http://127.0.0.1:${cdpPort}${path}`, options)).json()
  }
  const tab = await until(async () => (await chromeJson('/json/list'))
    .find(page => page.type === 'page'), 'Chrome devtools')
  socket = new WebSocket(tab.webSocketDebuggerUrl)
  await once(socket, 'open')

  let sequence = 0
  async function send(method, params = {}) {
    const id = ++sequence
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        socket.removeEventListener('message', receive)
        reject(new Error(`DevTools timeout: ${method}`))
      }, 10_000)
      function receive({ data }) {
        const message = JSON.parse(data)
        if (message.id !== id) return
        clearTimeout(timer)
        socket.removeEventListener('message', receive)
        if (message.error) reject(new Error(JSON.stringify(message.error)))
        else resolve(message.result)
      }
      socket.addEventListener('message', receive)
      socket.send(JSON.stringify({ id, method, params }))
    })
  }
  async function page(expression) {
    const response = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description ?? 'Chrome evaluation error')
    return response.result.value
  }
  async function waitFor(expression, label) {
    return until(() => page(expression), label)
  }
  async function change(id, value) {
    await page(`(() => { const node = document.querySelector(${JSON.stringify(id)});
      node.value = ${JSON.stringify(value)}; node.dispatchEvent(new Event('input', { bubbles: true })); })()`)
  }
  async function clickText(text) {
    await page(`[...document.querySelectorAll('button')].find(button => button.textContent.trim() === ${JSON.stringify(text)}).click()`)
  }
  async function fileInput(filename) {
    const { root: document } = await send('DOM.getDocument')
    const { nodeId } = await send('DOM.querySelector', { nodeId: document.nodeId, selector: '#response-file' })
    await send('DOM.setFileInputFiles', { nodeId, files: [filename] })
  }
  async function confirmGrade(verdict) {
    assert.equal(await page("document.querySelector('#grade').disabled"), true)
    await page("document.querySelector('#confirm').click()")
    await waitFor("document.querySelector('#grade')?.disabled === false", 'confirmation enables submission')
    await page("document.querySelector('#grade').click()")
    await waitFor(`document.querySelector('#run-status')?.textContent.trim() === ${JSON.stringify(verdict)}`, verdict)
  }

  const network = []
  const networkHeaders = new Map()
  socket.addEventListener('message', ({ data }) => {
    const { method, params } = JSON.parse(data)
    if (method === 'Network.requestWillBeSent') network.push(params)
    if (method === 'Network.requestWillBeSentExtraInfo') networkHeaders.set(params.requestId, params.headers)
  })
  await send('Network.enable', { maxPostDataSize: 2_000_000 })
  await send('Page.navigate', { url: origin })
  await waitFor("document.querySelector('h1')?.textContent === '需求转测试用例'", 'localized problem')
  await change('#prompt', '没有必需变量')
  assert.equal(await page("document.querySelector('button.primary').disabled"), true)
  assert.match(await page("document.querySelector('.field-error').textContent"), /requirement/)
  await change('#prompt', '版本一 {{requirement}}')
  const preview = await page("JSON.parse(document.querySelector('#preview').textContent)")
  await clickText('创建运行')
  await waitFor("document.querySelector('#run-status')?.textContent.trim() === '等待导入'", 'frozen run')
  const firstRun = await page("new URL(location.href).searchParams.get('run')")
  const request = await page("JSON.parse(document.querySelector('#frozen-request').textContent)")
  assert.deepEqual(request, { model: 'YOUR_MODEL', messages: preview, stream: false })
  await send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: temp })
  await clickText('下载请求')
  const requestFile = join(temp, `request-case-1-${firstRun}.json`)
  await until(() => access(requestFile).then(() => true, () => false), 'request file')
  assert.deepEqual(JSON.parse(await readFile(requestFile, 'utf8')), request)
  await send('Browser.grantPermissions', { origin, permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'] })
  await send('Page.bringToFront')
  await clickText('复制请求')
  await waitFor("document.querySelector('[role=status]')?.textContent === '请求已复制。'", 'copied request')
  assert.deepEqual(JSON.parse(await page('navigator.clipboard.readText()')), request)
  console.log('PASS preview, frozen snapshot, downloaded and copied request agree')

  const fixtures = JSON.parse(await readFile(join(root, 'content/problems/002/v1/fixtures/case-1.json'), 'utf8'))
  const raw = ' \r\n' + fixtures[0].rawOutput + '\n\t '
  const failedFile = join(temp, 'failed.json')
  const successFile = join(temp, 'response.json')
  const envelope = (content, reason) => JSON.stringify({ choices: [{ finish_reason: reason, message: { content } }] })
  await writeFile(failedFile, envelope(raw, 'length'))
  await fileInput(failedFile)
  await waitFor("document.querySelector('[role=alert]')?.textContent.includes('未正常结束')", 'truncation rejected')
  assert.equal(await page("document.querySelector('#raw').value"), '')
  assert.equal(await page("document.querySelector('#run-status').textContent.trim()"), '等待导入')
  await writeFile(successFile, envelope(raw, 'stop'))
  await fileInput(successFile)
  await waitFor("document.querySelector('#confirm')?.disabled === false", 'file imported')
  await confirmGrade('通过')
  assert.equal(await page("document.querySelector('#graded-raw').textContent"), raw)
  console.log('PASS truncated envelope never grades, valid file retains original text')

  await change('#prompt', '版本二 {{requirement}}')
  await clickText('新建运行')
  await waitFor(`new URL(location.href).searchParams.get('run') !== ${JSON.stringify(firstRun)}`, 'new run')
  const second = await page("new URL(location.href).searchParams.get('run')")
  assert.match(await page("JSON.parse(document.querySelector('#frozen-request').textContent).messages[0].content"), /^版本二/)
  async function paste(text) {
    await page(`(() => { const data = new DataTransfer(); data.setData('text/plain', ${JSON.stringify(text)});
      document.querySelector('#raw').dispatchEvent(new ClipboardEvent('paste', { clipboardData: data })); })()`)
  }
  await paste(raw)
  await confirmGrade('通过')
  assert.equal(await page("document.querySelector('#graded-raw').textContent"), raw)
  const previous = await (await fetch(`${origin}/api/runs/${firstRun}`)).json()
  const current = await (await fetch(`${origin}/api/runs/${second}`)).json()
  assert.deepEqual([current.rawOutput, current.verdict, current.checks], [previous.rawOutput, previous.verdict, previous.checks])
  assert.match(previous.messages[0].content, /^版本一/)
  console.log('PASS paste and file preserve the same result on independent immutable runs')

  await clickText('新建运行')
  await waitFor("document.querySelector('#raw') !== null", 'third run')
  await paste(fixtures[3].rawOutput)
  await confirmGrade('答案错误')
  assert.match(await page("document.querySelector('.checks .field-error').textContent"), /长度 5 应为 false/)
  await send('Page.reload')
  await waitFor("document.querySelector('#run-status')?.textContent.trim() === '答案错误'", 'reload saved verdict')
  assert.equal(await page("document.querySelector('#graded-raw').textContent"), fixtures[3].rawOutput)
  await clickText('新建运行')
  await waitFor("document.querySelector('#raw') !== null", 'fourth run')
  await paste(fixtures[1].rawOutput)
  await confirmGrade('格式错误')
  assert.equal(await page("document.querySelector('#graded-raw').textContent"), fixtures[1].rawOutput)
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  assert.equal(await page("document.documentElement.scrollWidth === window.innerWidth"), true)
  await clickText('复制正文')
  await waitFor("document.querySelector('[role=status]')?.textContent === '正文已复制。'", 'copied original answer')
  assert.equal(await page('navigator.clipboard.readText()'), fixtures[1].rawOutput)
  console.log('PASS wrong answer, format error, result after refresh and mobile layout')

  await send('Emulation.clearDeviceMetricsOverride')
  let modelOutput = raw
  const primary = await modelMock(origin, () => modelOutput)
  const other = await modelMock(origin, () => modelOutput)
  mocks.push(primary, other)
  const canary = 'test-only-' + randomUUID()
  const keyPresent = record => record.headers.authorization === 'Bearer ' + canary
  const lastModel = mock => mock.records.filter(row => row.method === 'GET' || row.method === 'POST').at(-1)
  const runId = () => page("new URL(location.href).searchParams.get('run')")
  async function startDirect() {
    const before = await runId()
    await clickText('运行并判题')
    await waitFor(`new URL(location.href).searchParams.get('run') !== ${JSON.stringify(before)}`, 'new direct run')
  }
  const persisted = async () => (await fetch(`${origin}/api/runs/${await runId()}`)).json()
  const status = text => waitFor(`document.querySelector('#run-status')?.textContent.trim() === ${JSON.stringify(text)}`, text)
  const resultState = ({ rawOutput, verdict, checks }) => [rawOutput, verdict, checks]
  const saveConnection = () => clickText('保存连接')
  const settingsOpen = () => clickText('模型设置')
  const modelTest = async () => {
    await clickText('测试连接')
    await waitFor("document.querySelector('#model-feedback')?.textContent.includes('连接成功')", 'connection test')
  }
  await settingsOpen()
  await change('#model-base', primary.origin + '/api///')
  await change('#model-key', canary)
  await saveConnection()
  assert.equal(await page("document.querySelector('#model-base').value"), primary.origin + '/api')
  await page("document.cookie = 'practice_test=present; SameSite=Lax'; fetch('/api/health').then(response => response.ok)")
  primary.behavior('list-error')
  await clickText('获取模型列表')
  await waitFor("document.querySelector('#model-feedback')?.textContent.includes('手填')", 'manual model after list error')
  primary.behavior('normal')
  await clickText('获取模型列表')
  await waitFor("document.querySelector('#available-models')?.options.length === 2", 'optional models list')
  await change('#model-id', 'example-model')
  await page("document.querySelector('#model-stream').click()")
  await saveConnection()
  await modelTest()
  assert.equal(keyPresent(lastModel(primary)), true)
  assert.ok(network.some(row => row.request.url.startsWith(origin + '/api/') &&
    Object.values(networkHeaders.get(row.requestId) ?? {}).some(value => value.includes('practice_test=present'))))
  assert.equal(lastModel(primary).headers.cookie, undefined)
  assert.equal(lastModel(primary).headers.referer, undefined)
  assert.equal(await page(`JSON.stringify(localStorage).includes(${JSON.stringify(canary)})`), false)
  assert.equal(await page(`JSON.stringify(sessionStorage).includes(${JSON.stringify(canary)})`), false)
  await clickText('返回练习')
  await change('#prompt', 'M2 中文 {{requirement}}')
  await clickText('浏览器直连')
  const directPreview = await page("JSON.parse(document.querySelector('#preview').textContent)")
  await startDirect()
  await status('通过')
  const ordinaryRun = await persisted()
  assert.equal(ordinaryRun.mode, 'DIRECT')
  assert.equal(ordinaryRun.reported.modelId, 'example-model')
  assert.equal(ordinaryRun.reported.finishReason, 'stop')
  assert.ok(Number.isInteger(ordinaryRun.reported.durationMs))
  assert.deepEqual(resultState(ordinaryRun), resultState(previous))
  assert.deepEqual(JSON.parse(lastModel(primary).body), { model: 'example-model', messages: directPreview, stream: false })
  assert.deepEqual(ordinaryRun.messages, directPreview)

  await settingsOpen()
  await page("document.querySelector('#model-stream').click()")
  await saveConnection()
  await clickText('返回练习')
  await startDirect()
  await status('通过')
  assert.notEqual(await runId(), ordinaryRun.runId)
  assert.deepEqual(resultState(await persisted()), resultState(previous))
  assert.deepEqual(JSON.parse(lastModel(primary).body), { model: 'example-model', messages: directPreview, stream: true })
  console.log('PASS real browser, Java and SQLite grade matching JSON, SSE, file and paste byte-for-byte')

  async function failResult({ data }) {
    const { method, params } = JSON.parse(data)
    if (method !== 'Fetch.requestPaused') return
    await send('Fetch.fulfillRequest', { requestId: params.requestId, responseCode: 503,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
      body: Buffer.from('{"code":"TEMPORARILY_UNAVAILABLE"}').toString('base64') })
  }
  socket.addEventListener('message', failResult)
  await send('Fetch.enable', { patterns: [{ urlPattern: origin + '/api/runs/*/result' }] })
  try {
    await startDirect()
    await status('结果待保存')
    assert.equal((await persisted()).status, 'PENDING')
  } finally {
    socket.removeEventListener('message', failResult)
    await send('Fetch.disable')
  }
  const postsBeforeRetry = primary.records.filter(row => row.method === 'POST').length
  await clickText('重新保存结果')
  await status('通过')
  assert.equal(primary.records.filter(row => row.method === 'POST').length, postsBeforeRetry)
  console.log('PASS retry saves the completed result without requesting the model again')

  primary.behavior('stall')
  const beforeCancel = primary.disconnected()
  await startDirect()
  await waitFor("document.querySelector('#direct-text')?.textContent.includes('cases')", 'visible streamed fragment')
  await clickText('停止请求')
  await status('已取消')
  assert.equal((await persisted()).verdict, null)
  assert.equal((await persisted()).rawOutput, null)
  assert.equal(await page("document.querySelector('#direct-text').textContent"), raw)
  await until(() => primary.disconnected() > beforeCancel, 'browser closes canceled HTTP stream')

  primary.behavior('truncated')
  await startDirect()
  await status('执行失败')
  const failed = await persisted()
  assert.deepEqual([failed.failureCode, failed.rawOutput, failed.verdict, failed.checks], ['TRUNCATED', null, null, null])
  await clickText('转手动运行')
  assert.equal(await page("document.querySelector('#prompt').value"), 'M2 中文 {{requirement}}')
  await clickText('新建运行')
  await status('等待导入')
  assert.equal((await persisted()).mode, 'D0')
  assert.equal(JSON.parse(await page("document.querySelector('#frozen-request').textContent")).model, 'YOUR_MODEL')
  assert.equal(await page(`document.querySelector('#frozen-request').textContent.includes(${JSON.stringify(canary)})`), false)
  console.log('PASS cancellation closes stream, truncated runs do not grade, manual fallback retains the draft')

  await clickText('浏览器直连')
  for (const [behavior, message] of [
    [401, '鉴权'], [404, '不存在'], [429, '额度'], [503, 'HTTP 503'],
    ['cors', '无法读取'], ['preflight', '无法读取'],
    ['redirect:' + other.origin + '/api/chat/completions', '无法读取'],
  ]) {
    primary.behavior(behavior)
    const beforePosts = primary.records.filter(row => row.method === 'POST').length
    await startDirect()
    await status('执行失败')
    assert.match(await page("document.querySelector('[role=alert]').textContent"), new RegExp(message))
    assert.equal((await persisted()).verdict, null)
    assert.equal(primary.records.filter(row => row.method === 'POST').length, beforePosts + (behavior === 'preflight' ? 0 : 1))
  }
  assert.equal(other.records.length, 0)
  assert.ok(primary.records.some(row => row.method === 'OPTIONS'
    && row.headers['access-control-request-headers'] === 'authorization,content-type'))
  console.log('PASS browser maps HTTP, preflight, unreadable CORS and rejected redirect without fallback requests')

  primary.behavior('stall')
  await startDirect()
  await waitFor("document.querySelector('#direct-text')?.textContent.includes('cases')", 'stream pending before refresh')
  const pendingId = await runId()
  const beforeReload = primary.records.length
  await send('Page.reload')
  await waitFor("document.querySelector('#run-status')?.textContent.includes('直连未结束') || document.querySelector('#run-status')?.textContent.includes('已取消')", 'restore direct run')
  assert.equal(await runId(), pendingId)
  assert.equal(primary.records.length, beforeReload)
  const afterReload = await persisted()
  assert.equal(afterReload.verdict, null)
  primary.behavior('normal')
  await settingsOpen()
  await modelTest()
  assert.equal(lastModel(primary).headers.authorization, undefined)
  await change('#model-key', canary)
  await page("document.querySelector('#remember-key').click()")
  await saveConnection()
  await send('Page.reload')
  await waitFor("document.querySelector('h1')?.textContent === '需求转测试用例'", 'refresh persisted configuration')
  await settingsOpen()
  await modelTest()
  assert.equal(keyPresent(lastModel(primary)), true)

  const isolated = await chromeJson('/json/new?' + encodeURIComponent(origin), { method: 'PUT' })
  const isolatedSocket = new WebSocket(isolated.webSocketDebuggerUrl)
  await once(isolatedSocket, 'open')
  try {
    const reply = new Promise(resolve => isolatedSocket.addEventListener('message', ({ data }) => {
      const response = JSON.parse(data)
      if (response.id === 1) resolve(response.result.result.value)
    }))
    isolatedSocket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: {
      expression: '[Object.keys(sessionStorage).length, Object.keys(localStorage).length]', returnByValue: true,
    } }))
    assert.deepEqual(await reply, [0, 1])
  } finally {
    isolatedSocket.close()
    await fetch(`http://127.0.0.1:${cdpPort}/json/close/${isolated.id}`)
  }

  await change('#model-base', other.origin + '/v1')
  await change('#model-key', canary)
  await send('Page.enable')
  async function confirmTarget(accept) {
    const dialog = new Promise(resolve => {
      function opening({ data }) {
        if (JSON.parse(data).method !== 'Page.javascriptDialogOpening') return
        socket.removeEventListener('message', opening)
        resolve()
      }
      socket.addEventListener('message', opening)
    })
    const saving = saveConnection()
    await dialog
    await send('Page.handleJavaScriptDialog', { accept })
    await saving
  }
  await confirmTarget(false)
  assert.equal(other.records.length, 0)
  assert.equal(await page("JSON.stringify(localStorage).includes(location.hostname + ':' + " + new URL(primary.origin).port + ")"), true)
  await confirmTarget(true)
  assert.equal(await page('Object.keys(sessionStorage).length'), 0)
  await modelTest()
  assert.equal(lastModel(other).headers.authorization, undefined)
  await change('#model-key', canary)
  await saveConnection()
  await clickText('清除 Key')
  await modelTest()
  assert.equal(lastModel(other).headers.authorization, undefined)
  console.log('PASS Key is memory-only by default, opt-in survives same-tab refresh, independent tab and target switch clear access')

  await change('#model-key', canary)
  await saveConnection()
  await clickText('返回练习')
  other.behavior('echo')
  await startDirect()
  await status('执行失败')
  assert.equal((await persisted()).verdict, null)
  assert.equal(await page(`document.body.textContent.includes(${JSON.stringify(canary)})`), false)
  other.behavior('normal')
  modelOutput = `<img src="${other.origin}/probe"><script type="application/json">{"probe":1}</script>
[probe](${other.origin}/probe)`
  await startDirect()
  await status('格式错误')
  assert.equal(await page("document.querySelector('#graded-raw').textContent"), modelOutput)
  assert.equal(await page("document.querySelector('#graded-raw').children.length"), 0)
  assert.equal(other.records.some(row => row.url === '/probe'), false)
  assert.ok(network.some(row => row.request.url === origin + '/api/runs' && row.request.method === 'POST'))
  assert.ok(network.filter(row => row.request.url.startsWith(origin + '/')).every(row =>
    !JSON.stringify([row.request, networkHeaders.get(row.requestId)]).includes(canary)))
  assert.equal(await page(`JSON.stringify(localStorage).includes(${JSON.stringify(canary)})`), false)
  assert.equal(await page(`JSON.stringify(sessionStorage).includes(${JSON.stringify(canary)})`), false)
  await settingsOpen()
  await clickText('删除连接')
  assert.equal(await page('localStorage.length + sessionStorage.length'), 0)
  assert.ok(children.every(({ log }) => !log.includes(canary)))
  for (const file of ['practice.sqlite3', 'practice.sqlite3-wal']) {
    const bytes = await readFile(join(temp, file)).catch(error => {
      if (file.endsWith('-wal') && error.code === 'ENOENT') return Buffer.alloc(0)
      throw error
    })
    assert.equal(bytes.includes(canary), false)
  }
  console.log('PASS model content is inert text, Key echo never grades, platform and non-sensitive storage contain no canary')

  const violations = "[...window.cspViolations]"
  await page("window.cspViolations = []; document.addEventListener('securitypolicyviolation', event => window.cspViolations.push(event.effectiveDirective))")
  await page(`fetch(${JSON.stringify(other.origin.replace('127.0.0.1', '127.0.0.2') + '/probe')}).catch(() => false)`)
  await waitFor(`${violations}.includes('connect-src')`, 'candidate excludes non-approved HTTP loopback addresses')
  await page(`(() => {
    const script = document.createElement('script'); script.src = ${JSON.stringify(other.origin + '/probe')};
    document.head.append(script);
    const object = document.createElement('object'); object.data = '/api/health'; document.body.append(object);
  })()`)
  await waitFor(`${violations}.includes('script-src-elem') && ${violations}.includes('object-src')`, 'candidate denies external scripts and objects')
  assert.equal(other.records.some(row => row.url === '/probe'), false)

  const httpsModel = 'https://promptlab-csp.invalid/v1/models'
  async function checkHttpsMatching(allowed, site) {
    let intercepted = false
    async function fulfill({ data }) {
      const { method, params } = JSON.parse(data)
      if (method !== 'Fetch.requestPaused') return
      intercepted = true
      await send('Fetch.fulfillRequest', { requestId: params.requestId, responseCode: 200,
        responseHeaders: [{ name: 'Access-Control-Allow-Origin', value: site }],
        body: Buffer.from('preview-only').toString('base64') })
    }
    socket.addEventListener('message', fulfill)
    await send('Fetch.enable', { patterns: [{ urlPattern: httpsModel }] })
    try {
      assert.equal(await page(`fetch(${JSON.stringify(httpsModel)}).then(res => res.text()).catch(() => false)`),
        allowed ? 'preview-only' : false)
      assert.equal(intercepted, allowed)
    } finally {
      socket.removeEventListener('message', fulfill)
      await send('Fetch.disable')
    }
  }
  await checkHttpsMatching(true, origin)
  const fixedPort = await freePort()
  const fixed = 'http://127.0.0.1:' + fixedPort
  startPreview(fixedPort, policy.replace(/connect-src[^;]+/, `connect-src 'self' ${primary.origin}`))
  await until(async () => (await fetch(fixed)).ok, 'fixed CSP preview')
  await send('Page.navigate', { url: fixed + '/?locale=en' })
  await waitFor("document.documentElement.lang === 'en' && [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Manual run')", 'English controls')
  await checkHttpsMatching(false, fixed)
  const allowedBefore = primary.records.filter(row => row.url === '/probe').length
  await page(`fetch(${JSON.stringify(primary.origin + '/probe')}).catch(() => false)`)
  assert.equal(primary.records.filter(row => row.url === '/probe').length, allowedBefore + 1)
  await page(`fetch(${JSON.stringify(other.origin + '/probe')}).catch(() => false)`)
  assert.equal(other.records.some(row => row.url === '/probe'), false)
  await clickText('Model settings')
  assert.equal(await page("document.querySelector('#connection-title').textContent"), 'Model connection')
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  assert.equal(await page("document.documentElement.scrollWidth === window.innerWidth"), true)
  await send('Emulation.clearDeviceMetricsOverride')
  const embedFailures = []
  socket.addEventListener('message', ({ data }) => {
    const event = JSON.parse(data)
    if (event.method === 'Audits.issueAdded') embedFailures.push(event.params.issue)
  })
  await send('Audits.enable')
  await send('Page.navigate', { url: other.origin + '/embed' })
  await waitFor("document.querySelector('iframe') !== null", 'embedded control page')
  await until(() => embedFailures.some(({ details }) =>
    details.contentSecurityPolicyIssueDetails?.violatedDirective === 'frame-ancestors'), 'frame-ancestors blocks embedding')
  console.log('PASS candidate vs fixed CSP in Chrome, simulated HTTPS matching, resource restrictions and iframe denial')

} catch (error) {
  for (const { command, log } of children) console.error(command, log.slice(-2200))
  throw error
} finally {
  socket?.close()
  await Promise.all(children.reverse().map(async ({ child }) => {
    if (child.pid && child.exitCode === null && child.signalCode === null) {
      const exit = once(child, 'exit')
      child.kill()
      await exit
    }
  }))
  await Promise.all(mocks.map(mock => mock.close()))
  await rm(temp, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
}
