import { ref } from 'vue'

/** 页面级的忙碌状态、错误和提示。 */
export function useFeedback() {
  const busy = ref(false)
  const error = ref('')
  const notice = ref('')

  function clear() {
    error.value = ''
    notice.value = ''
  }

  async function action(operation: () => Promise<void>) {
    busy.value = true
    clear()
    try {
      await operation()
    } catch (err) {
      error.value = (err as Error).message
    } finally {
      busy.value = false
    }
  }

  return { busy, error, notice, clear, action }
}

export type Feedback = ReturnType<typeof useFeedback>
