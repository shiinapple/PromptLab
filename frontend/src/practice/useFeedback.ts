import { computed, ref, shallowRef } from 'vue'
import { errorMessage } from './api'

export type FeedbackMessage = () => string

/** 页面级的忙碌状态、错误和提示。展示时按当前语言生成文案。 */
export function useFeedback() {
  const busy = ref(false)
  const errorText = shallowRef<FeedbackMessage>()
  const noticeText = shallowRef<FeedbackMessage>()
  const error = computed(() => errorText.value?.() ?? '')
  const notice = computed(() => noticeText.value?.() ?? '')

  function clear() {
    errorText.value = undefined
    noticeText.value = undefined
  }

  async function action(operation: () => Promise<void>) {
    busy.value = true
    clear()
    try {
      await operation()
    } catch (err) {
      errorText.value = () => errorMessage(err)
    } finally {
      busy.value = false
    }
  }

  return { busy, error, notice, errorText, noticeText, clear, action }
}

export type Feedback = ReturnType<typeof useFeedback>
