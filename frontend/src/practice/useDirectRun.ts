import { onMounted, onUnmounted, ref, shallowRef, type Ref } from 'vue'
import { ApiError, errorMessage, readRun, submitResult, type Run, type RunResult } from './api'
import { makeRequest } from './d0'
import type { FeedbackMessage } from './useFeedback'
import type { ConnectionStore, ConnectionProfile } from '../model/connection'
import { ModelError, type DirectTransport } from '../model/direct'
import { modelMessage, t } from '../model/copy'

export type DirectStage = 'request' | 'saving'

/** 浏览器直连：发请求、接收流、停止，再把结果交给平台保存。 */
export function useDirectRun(run: Ref<Run | undefined>, error: Ref<FeedbackMessage | undefined>, store: ConnectionStore, transport: DirectTransport) {
  const text = ref('')
  const stage = ref<DirectStage>()
  const seconds = ref(0)
  const request = shallowRef<ReturnType<typeof makeRequest>>()
  // 已经拿到、但平台还没保存成功的结果。重试只重新保存，不会再次请求模型。
  const pending = shallowRef<{ id: string; result: RunResult }>()
  const lastFailure = shallowRef<ModelError>()
  let controller: AbortController | undefined

  function reset() {
    text.value = ''
    request.value = undefined
    pending.value = undefined
    lastFailure.value = undefined
  }

  async function start(frozen: Run, profile: ConnectionProfile) {
    request.value = makeRequest(frozen.messages, profile.model, profile.stream)
    const abort = new AbortController()
    controller = abort
    stage.value = 'request'
    const started = performance.now()
    seconds.value = 0
    const clock = setInterval(() => { seconds.value = Math.floor((performance.now() - started) / 1000) }, 250)
    let result: RunResult
    try {
      const key = store.getKey(profile.base)
      const response = await transport.complete({ base: profile.base, key }, {
        model: profile.model, stream: profile.stream, messages: frozen.messages,
      }, {
        signal: abort.signal,
        onText: received => { text.value = received },
      })
      abort.signal.throwIfAborted()
      text.value = response
      result = {
        status: 'COMPLETED',
        rawOutput: response,
        reported: { modelId: profile.model, finishReason: 'stop', durationMs: Math.round(performance.now() - started) },
      }
    } catch (err) {
      const failure = abort.signal.aborted ? new ModelError('CANCELED')
        : err instanceof ModelError ? err : new ModelError('PROTOCOL_ERROR')
      lastFailure.value = failure
      error.value = () => modelMessage(failure)
      result = failure.code === 'CANCELED' ? { status: 'CANCELED' }
        : { status: 'FAILED', failureCode: failure.failureCode }
    } finally {
      clearInterval(clock)
      controller = undefined
    }
    pending.value = { id: frozen.runId, result }
    await save()
  }

  async function save() {
    const attempt = pending.value
    if (!attempt) return
    stage.value = 'saving'
    try {
      run.value = await submitResult(attempt.id, attempt.result)
      pending.value = undefined
    } catch (err) {
      if (err instanceof ApiError && (err.code === 'RUN_EXPIRED' || err.code === 'RESULT_CONFLICT')) {
        run.value = await readRun(attempt.id).catch(() => run.value)
        if (run.value?.status !== 'PENDING') pending.value = undefined
      }
      const requestError = error.value
      error.value = () => [requestError?.(), t('保存结果失败。', 'Could not save the result.'), errorMessage(err)].filter(Boolean).join(' ')
    } finally {
      stage.value = undefined
    }
  }

  function stop() {
    controller?.abort()
  }

  onMounted(() => window.addEventListener('pagehide', stop))
  onUnmounted(() => {
    window.removeEventListener('pagehide', stop)
    stop()
  })

  return { text, stage, seconds, request, pending, lastFailure, reset, start, save, stop }
}
