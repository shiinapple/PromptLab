import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'
import { setTimeout as sleep } from 'node:timers/promises'

// M4-05：两个预建账号在独立浏览器上下文中的隔离、冲突、退出与重新登录。
// 账号模式使用打包 JAR 的默认 profile，不走 local-practice。

const root = fileURLToPath(new URL('../../', import.meta.url))
const temp = await mkdtemp(join(tmpdir(), 'promptlab-m4-e2e-'))
const children = []
let socket

const ACCOUNTS = {
  alice: { email: 'alice.m4@example.test', password: 'alice-m4-password-1' },
  bob: { email: 'bob.m4@example.test', password: 'bob-m4-password-22' },
}

function start(command, args, options = {}) {
  const child = spawn(command, args, { ...options, stdio: ['pipe', 'pipe', 'pipe'] })
  const entry = { child, command, log: '' }
  children.push(entry)
  child.on('error', err => { entry.log += err.message })
  for (const stream of [child.stdout, child.stderr]) {
    stream.on('data', data => { entry.log = (entry.log + data).slice(-12_000) })
  }
  return child
}

async function until(check, label, attempts = 150) {
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

/** 通过 --ops create-user 写入预建账号；密码只经 stdin 传入。 */
async function createUser(jar, database, account) {
  const child = spawn('java', ['-jar', jar, '--ops', 'create-user', account.email], {
    env: { ...process.env, PROMPT_PRACTICE_DB_PATH: database }, stdio: ['pipe', 'pipe', 'pipe'],
  })
  let out = ''
  child.stdout.on('data', data => { out += data })
  child.stderr.on('data', data => { out += data })
  child.stdin.end(`${account.password}\n`)
  const [code] = await once(child, 'exit')
  assert.equal(code, 0, `create-user failed: ${out.slice(-400)}`)
}

try {
  const jar = join(root, 'backend/target/prompt-practice-0.1.0-SNAPSHOT.jar')
  const apiPort = await freePort()
  const api = `http://127.0.0.1:${apiPort}`
  const database = join(temp, 'practice.sqlite3')
  await createUser(jar, database, ACCOUNTS.alice)
  await createUser(jar, database, ACCOUNTS.bob)

  start('java', ['-jar', jar, `--server.port=${apiPort}`, `--prompt-practice.database.path=${database}`], { cwd: temp })
  await until(async () => (await fetch(`${api}/api/health`)).ok, 'Spring readiness')

  const webPort = await freePort()
  const origin = `http://127.0.0.1:${webPort}`
  const app = `${origin}/?problem=002`
  const frontend = join(root, 'frontend')
  start(process.execPath, ['--input-type=module', '-e',
    'import { preview } from "vite"; await preview({ preview: { port: Number(process.env.TEST_PORT), strictPort: true } });'],
  { cwd: frontend, env: { ...process.env, PROMPT_PRACTICE_API_PORT: String(apiPort), TEST_PORT: String(webPort) } })
  await until(async () => (await fetch(`${origin}/api/health`)).ok, 'built Vite preview and proxy')

  // 账号模式下匿名访问业务接口必须 401。
  assert.equal((await fetch(`${origin}/api/me`)).status, 401)
  assert.equal((await fetch(`${origin}/api/problems/002/draft`)).status, 401)

  const chrome = process.env.CHROME_BIN ?? (process.platform === 'darwin'
    ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome')
  const cdpPort = await freePort()
  start(chrome, ['--headless', '--disable-background-networking', '--no-first-run',
    `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${join(temp, 'chrome')}`, 'about:blank'])
  const browserSocket = await until(async () => {
    const info = await (await fetch(`http://127.0.0.1:${cdpPort}/json/version`)).json()
    return info.webSocketDebuggerUrl
  }, 'Chrome browser endpoint')
  socket = new WebSocket(browserSocket)
  await once(socket, 'open')

  let sequence = 0
  const pending = new Map()
  socket.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data)
    const entry = pending.get(message.id)
    if (!entry) return
    pending.delete(message.id)
    if (message.error) entry.reject(new Error(JSON.stringify(message.error)))
    else entry.resolve(message.result)
  })
  function send(method, params = {}, sessionId) {
    const id = ++sequence
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(`DevTools timeout: ${method}`)) }, 15_000)
      pending.set(id, {
        resolve: value => { clearTimeout(timer); resolve(value) },
        reject: err => { clearTimeout(timer); reject(err) },
      })
      socket.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }))
    })
  }

  /** 为一个独立的浏览器上下文（独立 Cookie）打开页面。 */
  async function openContext() {
    const { browserContextId } = await send('Target.createBrowserContext')
    const { targetId } = await send('Target.createTarget', { url: 'about:blank', browserContextId })
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
    await send('Runtime.enable', {}, sessionId)
    await send('Page.enable', {}, sessionId)
    const context = {
      sessionId,
      async page(expression) {
        const response = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId)
        if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description ?? 'Chrome evaluation error')
        return response.result.value
      },
      async waitFor(expression, label) {
        return until(() => context.page(expression), label)
      },
      async go(url) {
        await send('Page.navigate', { url }, sessionId)
      },
      async change(id, value) {
        await context.page(`(() => { const node = document.querySelector(${JSON.stringify(id)});
          node.value = ${JSON.stringify(value)}; node.dispatchEvent(new Event('input', { bubbles: true })); })()`)
      },
      async clickText(text) {
        await context.page(`(() => { const node = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === ${JSON.stringify(text)});
          if (!node) throw new Error('missing button: ' + ${JSON.stringify(text)}); node.click(); })()`)
      },
    }
    return context
  }

  async function signIn(context, account) {
    await context.waitFor("document.querySelector('#login-heading')?.textContent === '登录'", 'login form')
    await context.change('input[type=email]', account.email)
    await context.change('input[type=password]', account.password)
    await context.clickText('登录')
    await until(async () => (await context.page("document.querySelector('#prompt') !== null")), 'signed-in workspace', 60)
      .catch(async err => { throw new Error(`${err.message}; page=${await context.page("document.body.innerText.slice(0, 300)")}`) })
  }

  const alice = await openContext()
  const bob = await openContext()
  await alice.go(app)
  await alice.waitFor("document.querySelector('#login-heading')?.textContent === '登录'", 'anonymous visitor sees login')
  assert.equal(await alice.page("document.querySelector('#prompt')"), null)

  // Alice 登录，修改草稿并等待服务端保存。
  await signIn(alice, ACCOUNTS.alice)
  const aliceDraft = 'Alice 的草稿 {{requirement}}'
  await alice.waitFor("document.querySelector('#prompt') !== null", 'prompt visible')
  await alice.change('#prompt', aliceDraft)
  await sleep(1500)
  await alice.go(app)
  await alice.waitFor(`document.querySelector('#prompt')?.value === ${JSON.stringify(aliceDraft)}`, 'draft survives reload')

  // Bob 登录：看不到 Alice 的草稿，也看不到历史和归档。
  await bob.go(app)
  await signIn(bob, ACCOUNTS.bob)
  await bob.waitFor("document.querySelector('#prompt') !== null", 'bob prompt')
  assert.notEqual(await bob.page("document.querySelector('#prompt').value"), aliceDraft)
  assert.equal(await bob.page("document.querySelector('#history-heading') !== null"), true)
  assert.match(await bob.page("document.querySelector('#history-heading').closest('section').textContent"), /暂无运行记录/)

  // Alice 创建一次 D0 运行并归档；Bob 的归档列表保持为空。
  await alice.clickText('创建运行')
  await until(() => alice.page("document.querySelector('#run-status')?.textContent.trim() === '等待导入'"), 'alice pending run', 60)
    .catch(async err => { throw new Error(`${err.message}; status=${await alice.page("document.querySelector('#run-status')?.textContent")}; alert=${await alice.page("document.querySelector('[role=alert]')?.textContent")}; run=${await alice.page("new URL(location.href).search")}`) })
  // 只有已判题（COMPLETED）的运行可以归档，所以先提交一份结果。
  await alice.waitFor("document.querySelector('#raw') !== null", 'manual response input')
  await alice.change('#raw', 'not json at all')
  await alice.waitFor("document.querySelector('#confirm') !== null", 'confirm control')
  await alice.page("document.querySelector('#confirm').click()")
  await alice.waitFor("document.querySelector('#grade')?.disabled === false", 'grade enabled')
  await alice.page("document.querySelector('#grade').click()")
  await alice.waitFor("document.querySelector('#grade') === null", 'run graded')
  await until(() => alice.page("[...document.querySelectorAll('button')].some(b => b.textContent.trim() === '归档')"), 'archive button', 60)
    .catch(async err => { throw new Error(`${err.message}; history=${await alice.page("document.querySelector('#history-heading').closest('section').outerHTML.slice(0, 700)")}; runs=${await alice.page("fetch('/api/problems/002/runs').then(r => r.status + ' ' + r.text())")}; status=${await alice.page("document.querySelector('#run-status')?.textContent")}`) })
  await alice.clickText('归档')
  await alice.waitFor("document.querySelector('[data-archived=true]') !== null", 'alice archived run listed')
  await alice.waitFor("[...document.querySelectorAll('button')].some(b => b.textContent.trim() === '取消归档')", 'unarchive offered')
  await bob.go(app)
  await bob.waitFor("document.querySelector('#history-heading') !== null", 'bob reloaded')
  assert.equal(await bob.page("document.querySelector('[data-archived=true]')"), null)
  const bobArchive = await bob.page(`fetch('/api/submissions').then(r => r.json())`)
  assert.deepEqual(bobArchive.items, [])

  // 冲突：Alice 的两个浏览器上下文都读到同一 revision，先保存者成功，后保存者看到冲突提示。
  const aliceSecond = await openContext()
  await aliceSecond.go(app)
  await signIn(aliceSecond, ACCOUNTS.alice)
  await aliceSecond.waitFor("document.querySelector('#prompt')?.value !== undefined && document.querySelector('#prompt')?.value !== ''", 'second context loaded')
  await alice.change('#prompt', 'Alice 上下文一的新版本 {{requirement}}')
  await sleep(1500)
  await aliceSecond.change('#prompt', 'Alice 上下文二的冲突版本 {{requirement}}')
  await aliceSecond.waitFor("document.querySelector('[role=alert]')?.textContent.includes('草稿在其他地方已更新')", 'conflict banner in second context')
  assert.equal(await aliceSecond.page("document.querySelector('#prompt').value"), 'Alice 上下文二的冲突版本 {{requirement}}')
  await aliceSecond.clickText('使用服务器版本')
  await aliceSecond.waitFor("document.querySelector('#prompt')?.value === 'Alice 上下文一的新版本 {{requirement}}'", 'server draft adopted')

  // 退出：原会话失效，重新登录后草稿仍在。
  await alice.clickText('退出')
  await alice.waitFor("document.querySelector('#login-heading')?.textContent === '登录'", 'logged out shows login')
  assert.equal(await alice.page(`fetch('/api/me').then(r => r.status)`), 401)
  await signIn(alice, ACCOUNTS.alice)
  await alice.waitFor("document.querySelector('#prompt')?.value === 'Alice 上下文一的新版本 {{requirement}}'", 'draft after re-login')

  // 偏好设置：保存字号后刷新，输入框与编辑器字号都从服务端恢复。
  await alice.clickText('偏好设置')
  await alice.waitFor("document.querySelector('#settings-panel input[type=number]') !== null", 'settings form loaded')
  await alice.change('#settings-panel input[type=number]', '18')
  await alice.clickText('保存偏好')
  await alice.waitFor("document.querySelector('#settings-panel [role=status]')?.textContent.includes('偏好已保存')", 'preferences saved')
  assert.equal(await alice.page("document.querySelector('#prompt').style.fontSize"), '18px')
  await alice.go(app)
  await alice.waitFor("document.querySelector('#prompt')?.style.fontSize === '18px'", 'font size restored after reload')
  await alice.clickText('偏好设置')
  await alice.waitFor("document.querySelector('#settings-panel input[type=number]')?.value === '18'", 'saved font size shown after reload')

  // 错误密码给出统一提示，不泄露账号是否存在。
  await bob.clickText('退出')
  await bob.waitFor("document.querySelector('#login-heading')?.textContent === '登录'", 'bob logged out')
  await bob.change('input[type=email]', ACCOUNTS.bob.email)
  await bob.change('input[type=password]', 'wrong-password-value')
  await bob.clickText('登录')
  await bob.waitFor("document.querySelector('[role=alert]')?.textContent.includes('邮箱或密码不正确')", 'uniform credential error')

  console.log('PASS M4 multi-account browser: isolation, archive, revision conflict, logout and re-login')
} finally {
  socket?.close()
  for (const { child } of children) child.kill()
  await Promise.all(children.map(({ child }) => (child.exitCode === null ? once(child, 'exit') : null)))
  await rm(temp, { recursive: true, force: true })
}
