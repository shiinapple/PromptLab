import { ref, type Ref } from 'vue'
import { ApiError, readRun, submitResult, type Run } from './api'
import { checkOutput, importResponse } from './d0'
import type { Feedback } from './useFeedback'
import { t } from '../model/copy'

/** 手动运行（D0）：导入或粘贴正文，确认后提交判题。 */
export function useManualRun(run: Ref<Run | undefined>, feedback: Feedback, guardKey: (text: string) => void) {
  const raw = ref('')
  const confirmed = ref(false)

  function reset() {
    raw.value = ''
    confirmed.value = false
  }

  /** 正文一有变化就要求重新确认。 */
  function edit(value: string) {
    raw.value = value
    confirmed.value = false
  }

  async function importFile(file: File) {
    if (feedback.busy.value) return
    reset()
    await feedback.action(async () => {
      raw.value = await importResponse(file)
      feedback.notice.value = t('已提取完整正文，请确认它属于当前运行。', 'Output imported. Confirm it belongs to this run.')
    })
  }

  async function grade() {
    if (!run.value || run.value.status !== 'PENDING' || !confirmed.value || feedback.busy.value) return
    const id = run.value.runId
    await feedback.action(async () => {
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

  return { raw, confirmed, reset, edit, importFile, grade }
}
