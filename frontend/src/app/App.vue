<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, shallowRef } from 'vue'
import { ApiError, createRun, readProblem, readRun, submitResult, type Check, type Problem, type Run, type RunMode, type RunResult } from '../practice/api'
import { checkOutput, importResponse, makeRequest } from '../practice/d0'
import { assembleMessages } from '../practice/messages'
import { browserStorage, ConnectionStore, validModel } from '../model/connection'
import { DirectTransport, ModelError } from '../model/direct'
import { english, modelMessage, t } from '../model/copy'
import ModelSettings from '../model/ModelSettings.vue'

const problem = ref<Problem>()
const run = ref<Run>()
const prompt = ref('')
const raw = ref('')
const confirmed = ref(false)
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const notice = ref('')
let retry: { body: string; key: string } | undefined
const settings = ref(false)
const store = new ConnectionStore(browserStorage('localStorage'), browserStorage('sessionStorage'), location.origin)
store.selectAccount('local-practice')
const connection = shallowRef(store.profile)
const mode = ref<RunMode>(store.profile && validModel(store.profile.model) ? 'DIRECT' : 'D0')
const transport = new DirectTransport(location.origin)
const directText = ref('')
const directStage = ref<'request' | 'saving'>()
const seconds = ref(0)
const snapshotDraft = ref<string>()
const directRequest = shallowRef<ReturnType<typeof makeRequest>>()
const pendingResult = shallowRef<{ id: string; result: RunResult }>()
let controller: AbortController | undefined

function syncConnection() {
  connection.value = store.profile
}

function setMode(value: RunMode) {
  mode.value = value
  error.value = ''
  notice.value = ''
}

function guardKey(text: string) {
  const key = store.getKey(store.profile?.base)
  if (key && text.includes(key)) throw new Error(modelMessage(new ModelError('CREDENTIAL')))
}


const publicCase = computed(() => problem.value?.cases[0])
const preview = computed(() => {
  if (!problem.value || !publicCase.value) return {}
  try {
    return { messages: assembleMessages(problem.value, { user: prompt.value }, publicCase.value.variables) }
  } catch (err) {
    const message = (err as Error).message
    return { error: message === 'EMPTY_SLOT' ? '请填写 Prompt。'
      : message === 'MISSING_VARIABLE' ? '请保留 {{requirement}}，以便带入用例。'
        : 'Prompt 含有未定义的变量。' }
  }
})
const requestJson = computed(() => {
  const current = run.value
  if (!current) return ''
  const request = current.mode === 'D0' ? makeRequest(current.messages)
    : directRequest.value ?? { messages: current.messages }
  return JSON.stringify(request, null, 2)
})
const titles = {
  PENDING: t('等待导入', 'Awaiting import'), COMPLETED: t('已完成', 'Completed'), FAILED: t('执行失败', 'Request failed'),
  CANCELED: t('已取消', 'Canceled'), EXPIRED: t('已过期', 'Expired'), PASSED: t('通过', 'Passed'),
  FORMAT_ERROR: t('格式错误', 'Format error'), WRONG_ANSWER: t('答案错误', 'Wrong answer'),
}
const runTitle = computed(() => {
  const current = run.value
  if (!current) return ''
  if (current.mode === 'DIRECT' && current.status === 'PENDING') {
    if (directStage.value === 'request') return t('正在请求模型', 'Requesting model')
    if (directStage.value === 'saving') return t('正在保存结果', 'Saving result')
    if (pendingResult.value) return t('结果待保存', 'Result pending save')
    return t('直连未结束', 'Direct run unfinished')
  }
  return titles[current.verdict ?? current.status]
})
const checkLabels: Record<string, string> = {
  json: 'JSON 语法', schema: '输出结构', 'case-count': '用例数量',
  'unique-lengths': '长度不重复', 'boundary-mapping': '边界判断',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await readProblem(english ? 'en' : 'zh-CN')
    problem.value = data
    prompt.value = data.defaultSlots.user
    const runId = new URL(location.href).searchParams.get('run')
    if (runId) {
      run.value = await readRun(runId)
      mode.value = run.value.mode
    }
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    loading.value = false
  }
}

async function action(operation: () => Promise<void>) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await operation()
  } catch (err) {
    error.value = (err as Error).message
  } finally {
    busy.value = false
  }
}

async function freezeRun(selected: Problem, caseId: string, user: string, selectedMode: RunMode) {
  guardKey(user)
  const body = JSON.stringify([selected.problemId, selected.version, caseId, user, selectedMode])
  if (retry?.body !== body) retry = { body, key: crypto.randomUUID() }
  const frozen = await createRun(selected, caseId, user, selectedMode, retry.key)
  retry = undefined
  run.value = frozen
  snapshotDraft.value = user
  raw.value = ''
  confirmed.value = false
  directText.value = ''
  directRequest.value = undefined
  pendingResult.value = undefined
  const url = new URL(location.href)
  url.searchParams.set('run', frozen.runId)
  history.replaceState(null, '', url)
  return frozen
}

async function freeze() {
  if (!problem.value || !publicCase.value || !preview.value.messages || busy.value) return
  const selected = problem.value
  const caseId = publicCase.value.caseId
  const user = prompt.value
  const selectedMode = mode.value
  const profile = connection.value
  if (selectedMode === 'DIRECT' && (!profile || !validModel(profile.model) || settings.value)) return
  await action(async () => {
    const frozen = await freezeRun(selected, caseId, user, selectedMode)
    if (selectedMode === 'DIRECT' && profile && frozen.status === 'PENDING') {
      await runDirect(frozen, profile)
    } else if (selectedMode === 'D0') {
      notice.value = t('运行已建立，可以复制或下载请求。', 'Run created. Copy or download the request.')
    }
  })
}

async function runDirect(frozen: Run, profile: NonNullable<typeof connection.value>) {
  directRequest.value = makeRequest(frozen.messages, profile.model, profile.stream)
  const request = new AbortController()
  controller = request
  directStage.value = 'request'
  const started = performance.now()
  seconds.value = 0
  const clock = setInterval(() => { seconds.value = Math.floor((performance.now() - started) / 1000) }, 250)
  let result: RunResult
  try {
    const key = store.getKey(profile.base)
    const response = await transport.complete({ base: profile.base, key }, {
      model: profile.model, stream: profile.stream, messages: frozen.messages,
    }, {
      signal: request.signal,
      onText: text => { directText.value = text },
    })
    request.signal.throwIfAborted()
    directText.value = response
    result = {
      status: 'COMPLETED',
      rawOutput: response,
      reported: { modelId: profile.model, finishReason: 'stop', durationMs: Math.round(performance.now() - started) },
    }
  } catch (err) {
    const failure = request.signal.aborted ? new ModelError('CANCELED')
      : err instanceof ModelError ? err : new ModelError('PROTOCOL_ERROR')
    error.value = modelMessage(failure)
    result = failure.code === 'CANCELED' ? { status: 'CANCELED' }
      : { status: 'FAILED', failureCode: failure.failureCode }
  } finally {
    clearInterval(clock)
    controller = undefined
  }
  pendingResult.value = { id: frozen.runId, result }
  await saveDirectResult()
}

async function saveDirectResult() {
  const attempt = pendingResult.value
  if (!attempt) return
  directStage.value = 'saving'
  try {
    run.value = await submitResult(attempt.id, attempt.result)
    pendingResult.value = undefined
  } catch (err) {
    if (err instanceof ApiError && (err.code === 'RUN_EXPIRED' || err.code === 'RESULT_CONFLICT')) {
      run.value = await readRun(attempt.id).catch(() => run.value)
      if (run.value?.status !== 'PENDING') pendingResult.value = undefined
    }
    error.value = [error.value, t('保存结果失败。', 'Could not save the result.'), (err as Error).message].filter(Boolean).join(' ')
  } finally {
    directStage.value = undefined
  }
}

async function copyText(text: string, success: string) {
  await action(async () => {
    guardKey(text)
    await navigator.clipboard.writeText(text)
    notice.value = success
  })
}

function download() {
  if (!run.value) return
  try {
    guardKey(requestJson.value)
  } catch (err) {
    error.value = (err as Error).message
    return
  }
  const url = URL.createObjectURL(new Blob([requestJson.value], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `request-${run.value.caseId}-${run.value.runId}.json`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || busy.value) return
  raw.value = ''
  confirmed.value = false
  await action(async () => {
    raw.value = await importResponse(file)
    notice.value = t('已提取完整正文，请确认它属于当前运行。', 'Output imported. Confirm it belongs to this run.')
  })
}

function editOutput(value: string) {
  raw.value = value
  confirmed.value = false
}

function paste(event: ClipboardEvent) {
  if (!event.clipboardData) return
  const target = event.target as HTMLTextAreaElement
  const text = event.clipboardData.getData('text/plain')
  // Textareas normalize CRLF. Preserve clipboard text for the usual whole-response paste.
  if (target.value === '' || (target.selectionStart === 0 && target.selectionEnd === target.value.length)) {
    event.preventDefault()
    editOutput(text)
  }
}

async function grade() {
  if (!run.value || run.value.status !== 'PENDING' || !confirmed.value || busy.value) return
  const id = run.value.runId
  await action(async () => {
    try {
      const output = checkOutput(raw.value)
      guardKey(output)
      run.value = await submitResult(id, { status: 'COMPLETED', rawOutput: output })
      confirmed.value = false
    } catch (err) {
      if (err instanceof ApiError && (err.code === 'RUN_EXPIRED' || err.code === 'RESULT_CONFLICT')) {
        run.value = await readRun(id).catch(() => run.value)
      }
      throw err
    }
  })
}

function failure(check: Check): string {
  switch (check.code) {
    case 'INVALID_JSON': {
      const location = check.actual as { line?: number; column?: number }
      return location.line && location.line > 0
        ? `第 ${location.line} 行、第 ${location.column} 列附近不是合法 JSON。` : '正文须为一个完整 JSON 对象。'
    }
    case 'TYPE': return `${check.path || '/'} 需要 ${check.expected}，实际为 ${check.actual}。`
    case 'REQUIRED': return `缺少字段 ${check.path}。`
    case 'EXTRA_FIELD': return `${check.path || '/'} 含有额外字段。`
    case 'ARRAY_SIZE': return `需要 ${check.expected} 项，实际 ${check.actual} 项。`
    case 'DUPLICATE_KEY': return '同一个长度只能出现一次。'
    case 'BOUNDARY_MAPPING': {
      const expected = check.expected as { boundaries: Record<string, boolean> }
      const actual = check.actual as { boundaries: Record<string, boolean | null>; unexpectedCount: number }
      return [...Object.entries(expected.boundaries).map(([length, value]) => {
        const received = actual.boundaries[length]
        return received === null ? `缺少长度 ${length}` : `长度 ${length} 应为 ${value}，实际为 ${received}`
      }), ...(actual.unexpectedCount ? [`另有 ${actual.unexpectedCount} 个题目未要求的长度`] : [])].join('。') + '。'
    }
    default: return check.path
  }
}

function stopRequest() {
  controller?.abort()
}

onMounted(() => {
  document.documentElement.lang = english ? 'en' : 'zh-CN'
  window.addEventListener('pagehide', stopRequest)
  void load()
})
onUnmounted(() => {
  window.removeEventListener('pagehide', stopRequest)
  stopRequest()
})
</script>

<template>
  <header class="site-header">
    <div>
      <span class="brand">Prompt Practice</span>
      <button type="button" :disabled="busy" :aria-expanded="settings" aria-controls="model-settings" @click="settings = !settings">
        {{ t('模型设置', 'Model settings') }}
      </button>
    </div>
  </header>
  <main>
    <h1>{{ problem?.title ?? 'Prompt 练习' }}</h1>
    <p v-if="loading" role="status">{{ t('正在加载题目…', 'Loading problem…') }}</p>
    <div v-if="error" class="feedback error" role="alert">{{ error }}</div>
    <p v-if="notice" class="feedback" role="status">{{ notice }}</p>
    <button v-if="!problem && !loading" type="button" @click="load">{{ t('重新加载', 'Reload') }}</button>
    <ModelSettings v-if="settings" id="model-settings" :store="store" :transport="transport"
      @saved="syncConnection" @close="settings = false" />
    <div v-if="problem && publicCase" class="workspace">
      <aside class="panel">
        <h2>{{ t('题目说明', 'Problem') }}</h2>
        <p>{{ problem.description }}</p>
        <h3>{{ t('公开用例', 'Public case') }}</h3>
        <p class="case-text">{{ publicCase.variables.requirement }}</p>
        <p class="meta">题目 {{ problem.problemId }} · v{{ problem.version }} · {{ publicCase.caseId }}</p>
      </aside>

      <div class="activity">
        <section class="panel">
          <h2><label for="prompt">Prompt</label></h2>
          <textarea id="prompt" v-model="prompt" rows="6" spellcheck="false" />
          <p v-if="preview.error" class="field-error" aria-live="polite">{{ preview.error }}</p>
          <details>
            <summary>{{ t('请求预览', 'Request preview') }}</summary>
            <pre id="preview">{{ preview.messages ? JSON.stringify(preview.messages, null, 2) : '请先修正 Prompt。' }}</pre>
          </details>
          <div class="run-options" role="group" :aria-label="t('运行方式', 'Run mode')">
            <button type="button" :aria-pressed="mode === 'DIRECT'" :disabled="busy" @click="setMode('DIRECT')">
              {{ t('浏览器直连', 'Direct connection') }}
            </button>
            <button type="button" :aria-pressed="mode === 'D0'" :disabled="busy" @click="setMode('D0')">
              {{ t('手动运行', 'Manual run') }}
            </button>
          </div>
          <p v-if="mode === 'DIRECT'" class="help">
            <template v-if="connection && validModel(connection.model)">
              {{ connection.name || connection.model }} · {{ connection.stream ? t('流式输出', 'Streaming') : t('普通响应', 'JSON response') }}
            </template>
            <button v-else type="button" :disabled="busy" @click="settings = true">{{ t('配置模型', 'Configure model') }}</button>
          </p>
          <div class="actions">
            <button type="button" @click="prompt = problem.defaultSlots.user">{{ t('恢复模板', 'Restore template') }}</button>
            <button class="primary" type="button"
              :disabled="!!preview.error || busy || (mode === 'DIRECT' && (settings || !connection || !validModel(connection.model)))"
              @click="freeze">
              {{ mode === 'DIRECT' ? t('运行并判题', 'Run and grade')
                : run ? t('新建运行', 'New run') : t('创建运行', 'Create run') }}
            </button>
          </div>
        </section>

        <section v-if="run" class="panel" aria-labelledby="run-heading">
          <div class="section-heading">
            <h2 id="run-heading">{{ t('本次运行', 'Current run') }} ·
              {{ run.mode === 'DIRECT' ? t('直连', 'Direct') : t('手动', 'Manual') }}</h2>
            <span id="run-status" class="mode" :class="{ passed: run.verdict === 'PASSED' }">
              {{ runTitle }}
            </span>
          </div>
          <p class="meta" id="run-id">{{ run.caseId }} · {{ run.runId }}</p>
          <p v-if="run.mode === 'DIRECT' && run.reported" class="meta">
            {{ run.reported.modelId }} · {{ run.reported.durationMs }} ms
          </p>
          <details>
            <summary>{{ t('冻结请求', 'Frozen request') }}</summary>
            <pre id="frozen-request">{{ requestJson }}</pre>
          </details>
          <p v-if="snapshotDraft !== undefined && prompt !== snapshotDraft" class="help">
            {{ t('当前草稿有改动，本次运行仍使用冻结的请求。', 'The draft has changed. This run uses the frozen request.') }}
          </p>
          <template v-if="run.mode === 'DIRECT'">
            <p v-if="run.failureCode && !error" class="help">{{ modelMessage(new ModelError(run.failureCode)) }}</p>
            <div v-if="directStage === 'request' || pendingResult || (run.status !== 'COMPLETED' && mode !== 'D0')" class="actions">
              <template v-if="directStage === 'request'">
                <span class="meta">{{ seconds }} s</span>
                <button type="button" @click="stopRequest">{{ t('停止请求', 'Stop request') }}</button>
              </template>
              <button v-if="pendingResult && !busy" type="button" @click="action(saveDirectResult)">
                {{ t('重新保存结果', 'Retry saving result') }}
              </button>
              <button v-if="!busy && run.status !== 'COMPLETED' && mode !== 'D0'" type="button" @click="setMode('D0')">
                {{ t('转手动运行', 'Switch to manual run') }}
              </button>
            </div>
            <p v-if="!busy && run.status === 'PENDING' && !pendingResult" class="help">
              {{ t('刷新会结束浏览器内的请求。可新建直连运行，或切换手动运行。',
                'Reloading ends browser requests. Start a new direct run or switch to manual.') }}
            </p>
            <template v-if="directText && run.rawOutput === null">
              <h3>{{ directStage === 'request' ? t('模型输出', 'Model output')
                : t('本页收到的正文', 'Text received in this page') }}</h3>
              <pre id="direct-text">{{ directText }}</pre>
            </template>
          </template>
          <template v-if="run.mode === 'D0' && run.status === 'PENDING'">
            <p class="help"><template v-if="english">Replace <code>YOUR_MODEL</code> in your model tool and run the request. Import the response or paste the complete output.</template>
              <template v-else>在模型工具中替换 <code>YOUR_MODEL</code> 并执行请求，再导入响应或粘贴完整正文。</template></p>
            <div class="actions export">
              <button type="button" :disabled="busy" @click="copyText(requestJson, t('请求已复制。', 'Request copied.'))">{{ t('复制请求', 'Copy request') }}</button>
              <button type="button" :disabled="busy" @click="download">{{ t('下载请求', 'Download request') }}</button>
              <label class="file-label" for="response-file">{{ t('导入响应 JSON', 'Import response JSON') }}</label>
              <input id="response-file" type="file" accept=".json,application/json" :disabled="busy" @change="selectFile">
            </div>
            <label class="output-label" for="raw">{{ t('模型原始正文', 'Original model output') }}</label>
            <textarea id="raw" :value="raw" rows="5" :disabled="busy" spellcheck="false"
              @input="editOutput(($event.target as HTMLTextAreaElement).value)" @paste="paste" />
            <label class="confirm"><input id="confirm" v-model="confirmed" type="checkbox" :disabled="busy || !raw.trim()">
              我确认正文完整，并且来自当前运行和用例
            </label>
            <div class="actions">
              <button id="grade" type="button" class="primary" :disabled="busy || !confirmed || !raw.trim()" @click="grade">{{ t('提交判题', 'Grade output') }}</button>
            </div>
          </template>
          <template v-if="run.rawOutput !== null">
            <div class="section-heading">
              <h3>{{ t('模型原始正文', 'Original model output') }}</h3>
              <button type="button" :disabled="busy" @click="copyText(run.rawOutput, t('正文已复制。', 'Output copied.'))">{{ t('复制正文', 'Copy output') }}</button>
            </div>
            <pre id="graded-raw">{{ run.rawOutput }}</pre>
          </template>
          <ul v-if="run.checks" class="checks">
            <li v-for="check in run.checks" :key="check.id" :class="{ 'field-error': check.status === 'FAIL' }">
              <strong>{{ checkLabels[check.id] ?? check.id }}</strong>
              <span>{{ check.status === 'PASS' ? '通过' : failure(check) }}</span>
            </li>
          </ul>
        </section>
      </div>
    </div>
  </main>
</template>

<style>
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--color-page);
  color: var(--color-ink);
  font: var(--font-size-body) / var(--line-height-base) var(--font-family-base);
}
.site-header { background: var(--color-card); border-bottom: 1px solid var(--color-muted); }
.site-header div { max-width: 72rem; margin: auto; padding: var(--space-3) var(--space-5); display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.brand { font-weight: var(--font-weight-title); color: var(--color-primary); }
main { max-width: 72rem; margin: auto; padding: var(--space-6) var(--space-5); }
h1 { margin: 0 0 var(--space-5); font-size: var(--font-size-title); line-height: var(--line-height-title); }
h2 { margin: 0 0 var(--space-3); font-size: 1.125rem; }
h3 { margin: var(--space-5) 0 var(--space-2); font-size: 0.95rem; }
p { margin: 0 0 var(--space-3); }
.workspace { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.8fr); gap: var(--space-5); align-items: start; }
.activity { display: grid; gap: var(--space-4); min-width: 0; }
.panel { background: var(--color-card); padding: var(--space-5); border: 1px solid var(--color-muted); border-radius: var(--radius-list); min-width: 0; }
.case-text, pre { margin: 0 0 var(--space-3); padding: var(--space-3); border-radius: var(--radius-control); background: var(--color-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
.meta, .help { color: var(--color-secondary-ink); font-size: 0.875rem; overflow-wrap: anywhere; }
textarea { display: block; width: 100%; resize: vertical; padding: var(--space-3); border: 1px solid var(--color-line); border-radius: var(--radius-control); font: inherit; color: inherit; background: var(--color-field); }
details { margin: var(--space-3) 0; }
summary { cursor: pointer; color: var(--color-primary); }
details pre { margin-top: var(--space-2); }
.actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: var(--space-2); margin-top: var(--space-4); }
.run-options { display: flex; flex-wrap: wrap; gap: var(--space-2); margin: var(--space-3) 0; }
.run-options [aria-pressed='true'] { background: var(--color-secondary); color: var(--color-primary); border-color: var(--color-primary); }
.actions.export { justify-content: flex-start; margin-bottom: var(--space-4); }
button { font: inherit; cursor: pointer; color: inherit; background: var(--color-field); padding: var(--space-2) var(--space-3); border: 1px solid var(--color-line); border-radius: var(--radius-control); }
button.primary { color: var(--color-card); background: var(--color-primary); border-color: var(--color-primary); }
button:disabled { opacity: 0.55; cursor: default; }
button:focus-visible, textarea:focus-visible, summary:focus-visible, input:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.mode { color: var(--color-primary); background: var(--color-success-surface); padding: var(--space-1) var(--space-2); border-radius: var(--radius-pill); font-size: 0.875rem; }
#run-status:not(.passed) { color: var(--color-secondary-ink); background: var(--color-secondary); }
.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); }
.field-error, .feedback.error { color: var(--color-error-text); }
.feedback { border-radius: var(--radius-control); padding: var(--space-2) var(--space-3); background: var(--color-card); }
.feedback.error { background: var(--color-error-surface); }
.confirm, .output-label { display: block; margin: var(--space-2) 0; }
input[type='file'] { max-width: 100%; }
.checks { list-style: none; padding: 0; margin: 0; }
.checks li { display: grid; grid-template-columns: 7rem minmax(0, 1fr); gap: var(--space-2); border-top: 1px solid var(--color-muted); padding: var(--space-2) 0; }
@media (max-width: 48rem) {
  .workspace { grid-template-columns: minmax(0, 1fr); gap: var(--space-4); }
  main, .panel { padding: var(--space-4); }
}
</style>
