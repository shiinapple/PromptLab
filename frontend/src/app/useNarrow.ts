import { onUnmounted, ref } from 'vue'

/** 与 tokens.css 及 App.vue 中的窄屏断点保持一致（768px = 48rem）。 */
export const NARROW_QUERY = '(max-width: 48rem)'

/** 响应式读取是否为窄屏；无 matchMedia 的环境（如单元测试）按桌面处理。 */
export function useNarrow() {
  const list = typeof matchMedia === 'function' ? matchMedia(NARROW_QUERY) : undefined
  const narrow = ref(list?.matches ?? false)
  const onChange = (event: MediaQueryListEvent) => { narrow.value = event.matches }
  list?.addEventListener('change', onChange)
  onUnmounted(() => list?.removeEventListener('change', onChange))
  return narrow
}
