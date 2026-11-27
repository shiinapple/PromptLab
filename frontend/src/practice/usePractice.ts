import { computed, onMounted, ref } from 'vue'
import { createRun, readProblem, readRun, type Problem, type Run, type RunMode } from './api'
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

/** 一次练习：读题、编辑草稿、冻结运行，再交给直连或手动流程。 */
export function usePractice() {
  const model = useConnection()
  const feedback = useFeedback()
  const problem = ref<Problem>()
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

  const publicCase = computed(() => problem.value?.cases[0])
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

  async function load() {
    loading.value = true
    feedback.error.value = ''
    try {
      const data = await readProblem(english ? 'en' : 'zh-CN')
      problem.value = data
      restoreTemplate()
      const runId = new URL(location.href).searchParams.get('run')
      if (runId) {
        run.value = await readRun(runId)
        mode.value = run.value.mode
      }
    } catch (err) {
      feedback.error.value = (err as Error).message
    } finally {
      loading.value = false
    }
  }

  async function freezeRun(selected: Problem, caseId: string, text: string, selectedMode: RunMode) {
    model.guardKey(text)
    const body = JSON.stringify([selected.problemId, selected.version, caseId, text, selectedMode])
    if (retry?.body !== body) retry = { body, key: crypto.randomUUID() }
    const frozen = await createRun(selected, caseId, text, selectedMode, retry.key)
    retry = undefined
    run.value = frozen
    snapshotDraft.value = text
    manual.reset()
    direct.reset()
    const url = new URL(location.href)
    url.searchParams.set('run', frozen.runId)
    history.replaceState(null, '', url)
    return frozen
  }

  async function freeze() {
    if (!problem.value || !publicCase.value || !preview.value.messages || feedback.busy.value) return
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

  onMounted(() => {
    document.documentElement.lang = english ? 'en' : 'zh-CN'
    void load()
  })

  return {
    model, feedback, direct, manual,
    problem, publicCase, run, slot, prompt, restoreTemplate, preview, requestJson, snapshotDraft, mode, loading, settingsOpen,
    load, setMode, freeze, copyText, download,
  }
}
