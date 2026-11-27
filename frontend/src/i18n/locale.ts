import { createI18n } from 'vue-i18n'

export type Locale = 'zh-CN' | 'en'

const DEFAULT: Locale = 'zh-CN'
const STORAGE_KEY = 'promptlab.locale'

/** 界面语言的唯一状态。词条在 M5-02 迁入 messages；当前 t() 仍按中英文参数取值。 */
export const i18n = createI18n({
  legacy: false,
  locale: DEFAULT,
  fallbackLocale: DEFAULT,
  messages: { 'zh-CN': {}, en: {} },
})

export function isLocale(value: unknown): value is Locale {
  return value === 'zh-CN' || value === 'en'
}

/** 优先级：URL（仅本次加载）> 已登录的服务端设置 > 本地存储 > 缺省 zh-CN。 */
export function pickLocale(sources: { url?: unknown; server?: unknown; stored?: unknown }): Locale {
  if (isLocale(sources.url)) return sources.url
  if (isLocale(sources.server)) return sources.server
  if (isLocale(sources.stored)) return sources.stored
  return DEFAULT
}

export const getLocale = (): Locale => i18n.global.locale.value as Locale

/** 双语文案：读取响应式语言状态，模板与计算属性会随切换自动更新。 */
export const t = (zh: string, en: string) => (getLocale() === 'en' ? en : zh)

function readStored(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

/** persist 为 false 时只改内存与 html[lang]，用于 URL 参数这类仅本次有效的来源。 */
export function setLocale(next: Locale, options: { persist?: boolean } = {}) {
  i18n.global.locale.value = next
  if (typeof document !== 'undefined') document.documentElement.lang = next
  if (options.persist === false) return
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 隐私模式等场景无法写入时，语言仍在本次页面内生效。
  }
}

/** URL 中的 locale 是否有效，有效时服务端设置不覆盖本次加载的语言。 */
export function urlPinsLocale(search: string): boolean {
  return isLocale(new URLSearchParams(search).get('locale'))
}

/** 页面加载时调用一次：决定初始语言，不把 URL 参数写回本地存储。 */
export function initLocale(search: string) {
  const url = new URLSearchParams(search).get('locale')
  setLocale(pickLocale({ url, stored: readStored() }), { persist: false })
}
