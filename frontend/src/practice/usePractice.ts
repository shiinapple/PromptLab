import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiError, authListeners, createRun, readProblem, readProblems, readRun, type Grading, type Problem, type ProblemSummary, type Run, type RunMode } from './api'
import { detectAccountMode, logout as endSession, me, readDraft, writeDraft, type Profile } from './account'
import { makeRequest } from './d0'
import { assembleMessages } from './messages'
import { editableSlot } from './slot'
import { previewError } from './labels'
import { useFeedback } from './useFeedback'
import { useDirectRun } from './useDirectRun'
import { useManualRun } from './useManualRun'
import { useConnection } from '../model/useConnection'
import { validModel } from '../model/connection'
import { english, t } from '../model/copy'

/** 草稿在服务端的冲突信息：保留本地文本，等待用户选择采用哪一方。 */
export type DraftConflict = { problemId: string; version: number; caseId: string; text: string; serverRevision: number }

/** 一次练习：读题、编辑草稿、冻结运行，再交给直连或手动流程。 */
export function usePractice() {
  const model = useConnection()
  const feedback = useFeedback()
  const problems = ref<ProblemSummary[]>([])
  const problem = ref<Problem>()
  const runGrading = ref<Grading>()
  // local-practice 模式的草稿只保存在内存中；账号模式使用服务端草稿。
  const drafts = new Map<string, string>()
  const locale = english ? 'en' : 'zh-CN'
  const run = ref<Run>()
  const prompt = ref('')
  const loading = ref(false)
  const settingsOpen = ref(false)
  const mode = ref<RunMode>(model.ready() ? 'DIRECT' : 'D0')
  const snapshotDraft = ref<string>()
  const direct = useDirectRun(run, feedback.error, model.store, model.transport)
  const manual = useManualRun(run, feedback, model.guardKey)
  // 同一份意图重试时复用幂等键，避免网络抖动创建出两条运行。
  let retry: { body: string; key: string } | undefined

  // 账号模式状态
  const accountMode = ref(false)
  const profile = ref<Profile>()
  const authRequired = ref(false)
  const conflict = ref<DraftConflict>()
  const draftSaving = ref(false)
  const historyRevision = ref(0)
  const revisions = new Map<string, number>()
  const saved = new Map<string, string>()
  let saveTimer: ReturnType<typeof setTimeout> | undefined
  let pendingSave: (() => Promise<void>) | undefined

  const selectedCaseId = ref<string>()
  const publicCase = computed(() => problem.value?.cases.find(item => item.caseId === selectedCaseId.value))
  const historical = computed(() => !!problem.value && problem.value.version < problem.value.publishedVersion)
  const slot = computed(() => problem.value && editableSlot(problem.value))
  const preview = computed(() => {
    if (!problem.value || !publicCase.value || !slot.value) return {}
    try {
      const slots = { [slot.value.name]: prompt.value }
      return { messages: assembleMessages(problem.value, slots, publicCase.value.variables) }
    } catch (err) {
      return { error: previewError((err as Error).message, slot.value.requiredVariables) }
    }
  })

  function restoreTemplate() {
    if (problem.value && slot.value) prompt.value = problem.value.defaultSlots[slot.value.name]
  }
  const requestJson = computed(() => {
    const current = run.value
    if (!current) return ''
    const request = current.mode === 'D0' ? makeRequest(current.messages)
      : direct.request.value ?? { messages: current.messages }
    return JSON.stringify(request, null, 2)
  })

  function setMode(value: RunMode) {
    mode.value = value
    feedback.clear()
  }

  const keyOf = (id: string, version: number) => `${id}/${version}`

  /** 在账号模式下，把服务端草稿（或题目默认值）放进编辑框，并记下 revision。 */
  async function applyServerDraft(data: Problem, caseId: string) {
    const key = keyOf(data.problemId, data.version)
    const name = editableSlot(data).name
    const { draft } = await readDraft(data.problemId, data.version)
    revisions.set(key, draft?.revision ?? 0)
    const text = draft?.slots[name] ?? data.defaultSlots[name]
    prompt.value = text
    saved.set(key, text)
    return { caseId: draft?.caseId ?? caseId }
  }

  async function displayProblem(data: Problem, caseId = data.cases[0]?.caseId) {
    await flushDraft()
    if (problem.value) drafts.set(keyOf(problem.value.problemId, problem.value.version), prompt.value)
    problem.value = data
    selectedCaseId.value = caseId
    conflict.value = undefined
    if (accountMode.value) {
      const restored = await applyServerDraft(data, caseId)
      selectedCaseId.value = caseId ?? restored.caseId
      return
    }
    prompt.value = drafts.get(keyOf(data.problemId, data.version)) ?? data.defaultSlots[editableSlot(data).name]
  }

  /** 把尚未发出的草稿立即写入服务端，切换题目前调用。 */
  async function flushDraft() {
    if (saveTimer !== undefined) {
      clearTimeout(saveTimer)
      saveTimer = undefined
      await pendingSave?.()
      pendingSave = undefined
    }
  }

  function scheduleSave() {
    if (!accountMode.value || !problem.value || !selectedCaseId.value) return
    const target = problem.value
    const caseId = selectedCaseId.value
    const text = prompt.value
    if (saved.get(keyOf(target.problemId, target.version)) === text) return
    if (saveTimer !== undefined) clearTimeout(saveTimer)
    const save = () => saveDraft(target, caseId, text)
    pendingSave = save
    saveTimer = setTimeout(() => {
      saveTimer = undefined
      void save()
    }, 600)
  }

  async function saveDraft(target: Problem, caseId: string, text: string) {
    const key = keyOf(target.problemId, target.version)
    if (saved.get(key) === text) return
    const name = editableSlot(target).name
    draftSaving.value = true
    try {
      const { draft } = await writeDraft(target.problemId, {
        version: target.version, caseId, slots: { [name]: text }, revision: revisions.get(key) ?? 0,
      })
      revisions.set(key, draft.revision)
      saved.set(key, text)
      if (conflict.value?.problemId === target.problemId && conflict.value.version === target.version) conflict.value = undefined
    } catch (err) {
      if (err instanceof ApiError && err.code === 'DRAFT_REVISION_CONFLICT') {
        conflict.value = { problemId: target.problemId, version: target.version, caseId, text,
          serverRevision: Number(err.details.serverRevision ?? 0) }
      } else {
        feedback.error.value = (err as Error).message
      }
    } finally {
      draftSaving.value = false
    }
  }

  /** 冲突时采用服务端版本，丢弃本地编辑。 */
  async function useServerDraft() {
    const current = conflict.value
    if (!current || !problem.value) return
    await feedback.action(async () => {
      const { draft } = await readDraft(current.problemId, current.version)
      const key = keyOf(current.problemId, current.version)
      revisions.set(key, draft?.revision ?? 0)
      if (problem.value?.problemId === current.problemId && problem.value.version === current.version) {
        prompt.value = draft?.slots[editableSlot(problem.value).name] ?? problem.value.defaultSlots[editableSlot(problem.value).name]
        saved.set(key, prompt.value)
      }
      conflict.value = undefined
      feedback.notice.value = t('已采用服务器上的草稿。', 'Loaded the server draft.')
    })
  }

  /** 冲突时以本地编辑覆盖服务端版本。 */
  async function overwriteDraft() {
    const current = conflict.value
    if (!current || !problem.value) return
    revisions.set(keyOf(current.problemId, current.version), current.serverRevision)
    saved.delete(keyOf(current.problemId, current.version))
    conflict.value = undefined
    await feedback.action(async () => {
      if (problem.value) await saveDraft(problem.value, current.caseId, current.text)
      if (!conflict.value) feedback.notice.value = t('已用本地编辑覆盖草稿。', 'Overwrote the server draft.')
    })
  }

  /** 旧版本草稿复制到当前版本，由用户点击触发。 */
  async function copyDraftToCurrent() {
    if (!problem.value || !historical.value) return
    const text = prompt.value
    await feedback.action(async () => {
      const current = await readProblem(problem.value!.problemId, locale)
      await displayProblem(current)
      prompt.value = text
      await flushDraft()
      feedback.notice.value = t('已复制到当前版本。', 'Copied to the current version.')
    })
  }

  watch(prompt, () => {
    if (accountMode.value) scheduleSave()
  })

  // 运行状态变化（判题、直连完成）后刷新历史列表。
  watch(() => run.value?.status, () => {
    historyRevision.value++
  })

  async function load() {
    loading.value = true
    feedback.error.value = ''
    try {
      const url = new URL(location.href)
      const runId = url.searchParams.get('run')
      const [list, restored] = await Promise.all([readProblems(locale), runId ? readRun(runId) : undefined])
      problems.value = list.items
      const id = restored?.problemId ?? url.searchParams.get('problem') ?? problems.value[0]?.problemId
      if (!id) throw new Error(t('没有可用题目。', 'No problems available.'))
      const data = await readProblem(id, locale, restored?.version)
      await displayProblem(data, restored?.caseId)
      if (restored) {
        run.value = restored
        runGrading.value = data.cases.find(item => item.caseId === restored.caseId)?.grading
        mode.value = restored.mode
      }
    } catch (err) {
      if (!authRequired.value) feedback.error.value = (err as Error).message
    } finally {
      loading.value = false
    }
  }

  /** 从历史列表打开一次运行，并切换到它的题目版本。 */
  async function openRun(runId: string) {
    if (loading.value) return
    loading.value = true
    feedback.clear()
    try {
      const restored = await readRun(runId)
      const data = await readProblem(restored.problemId, locale, restored.version)
      await displayProblem(data, restored.caseId)
      run.value = restored
      runGrading.value = data.cases.find(item => item.caseId === restored.caseId)?.grading
      mode.value = restored.mode
      const url = new URL(location.href)
      url.searchParams.set('run', restored.runId)
      url.searchParams.set('problem', restored.problemId)
      history.replaceState(null, '', url)
    } catch (err) {
      feedback.error.value = (err as Error).message
    } finally {
      loading.value = false
    }
  }

  async function selectProblem(id: string) {
    if (loading.value || (id === problem.value?.problemId && !historical.value)) return
    loading.value = true
    feedback.clear()
    try {
      await displayProblem(await readProblem(id, locale))
      const url = new URL(location.href)
      url.searchParams.set('problem', id)
      url.searchParams.delete('run')
      history.replaceState(null, '', url)
    } catch (err) {
      feedback.error.value = (err as Error).message
    } finally {
      loading.value = false
    }
  }

  function selectCase(id: string) {
    if (loading.value || !problem.value?.cases.some(item => item.caseId === id)) return
    selectedCaseId.value = id
    scheduleSave()
  }

  async function freezeRun(selected: Problem, caseId: string, text: string, selectedMode: RunMode) {
    model.guardKey(text)
    const body = JSON.stringify([selected.problemId, selected.version, caseId, text, selectedMode])
    if (retry?.body !== body) retry = { body, key: crypto.randomUUID() }
    const frozen = await createRun(selected, caseId, text, selectedMode, retry.key)
    retry = undefined
    run.value = frozen
    runGrading.value = selected.cases.find(item => item.caseId === frozen.caseId)?.grading
    snapshotDraft.value = text
    manual.reset()
    direct.reset()
    const url = new URL(location.href)
    if (problem.value?.problemId === frozen.problemId) {
      url.searchParams.set('run', frozen.runId)
      url.searchParams.set('problem', frozen.problemId)
    }
    history.replaceState(null, '', url)
    historyRevision.value++
    return frozen
  }

  async function freeze() {
    if (loading.value || historical.value || !problem.value || !publicCase.value || !preview.value.messages || feedback.busy.value) return
    // await 之后页面状态可能已经变了，先把本次要用的值固定下来。
    const selected = problem.value
    const caseId = publicCase.value.caseId
    const text = prompt.value
    const selectedMode = mode.value
    const profile = model.connection.value
    if (selectedMode === 'DIRECT' && (!profile || !validModel(profile.model) || settingsOpen.value)) return
    await feedback.action(async () => {
      const frozen = await freezeRun(selected, caseId, text, selectedMode)
      if (selectedMode === 'DIRECT' && profile && frozen.status === 'PENDING') {
        await direct.start(frozen, profile)
      } else if (selectedMode === 'D0') {
        feedback.notice.value = t('运行已建立，可以复制或下载请求。', 'Run created. Copy or download the request.')
      }
    })
  }

  async function copyText(text: string, success: string) {
    await feedback.action(async () => {
      model.guardKey(text)
      await navigator.clipboard.writeText(text)
      feedback.notice.value = success
    })
  }

  function download() {
    if (!run.value) return
    try {
      model.guardKey(requestJson.value)
    } catch (err) {
      feedback.error.value = (err as Error).message
      return
    }
    const url = URL.createObjectURL(new Blob([requestJson.value], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `request-${run.value.caseId}-${run.value.runId}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  /** 账号模式的登录成功后调用：读取身份并重新加载题目与草稿。 */
  async function signedIn() {
    authRequired.value = false
    profile.value = await me().catch(() => undefined)
    await load()
  }

  async function signOut() {
    await feedback.action(async () => {
      await flushDraft().catch(() => undefined)
      await endSession().catch(() => undefined)
      profile.value = undefined
      authRequired.value = true
      problem.value = undefined
      run.value = undefined
      prompt.value = ''
      conflict.value = undefined
      saved.clear()
      revisions.clear()
    })
  }

  const onAuthRequired = () => {
    if (!accountMode.value) return
    authRequired.value = true
    loading.value = false
  }

  onMounted(async () => {
    document.documentElement.lang = english ? 'en' : 'zh-CN'
    authListeners.add(onAuthRequired)
    accountMode.value = await detectAccountMode().catch(() => false)
    if (!accountMode.value) {
      void load()
      return
    }
    profile.value = await me().catch(() => undefined)
    if (profile.value) void load()
    else authRequired.value = true
  })

  onUnmounted(() => {
    authListeners.delete(onAuthRequired)
    if (saveTimer !== undefined) clearTimeout(saveTimer)
  })

  return {
    model, feedback, direct, manual,
    problems, problem, historical, publicCase, run, runGrading, slot, prompt, restoreTemplate, preview, requestJson, snapshotDraft, mode, loading, settingsOpen,
    accountMode, profile, authRequired, conflict, draftSaving, historyRevision,
    load, selectProblem, selectCase, setMode, freeze, copyText, download,
    openRun, signedIn, signOut, useServerDraft, overwriteDraft, copyDraftToCurrent,
  }
}
