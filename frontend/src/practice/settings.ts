export type Locale = 'zh-CN' | 'en'
export type EditorPrefs = { fontSize?: number; tabSize?: number; wordWrap?: boolean }
export type Settings = { locale: Locale; editorPrefs: EditorPrefs; updatedAt: string | null }

export const DEFAULT_PREFS = { fontSize: 14, tabSize: 4, wordWrap: true } as const

/** 未保存过的字段使用默认值，与后端首次读取的默认行为保持一致。 */
export function effectivePrefs(prefs: EditorPrefs) {
  return { ...DEFAULT_PREFS, ...prefs }
}

/** 与后端白名单一致：fontSize 为 12–32 的整数，tabSize 为 2–8 的整数。返回首个不合法的字段。 */
export function checkPrefs(fontSize: number, tabSize: number): 'fontSize' | 'tabSize' | null {
  if (!Number.isInteger(fontSize) || fontSize < 12 || fontSize > 32) return 'fontSize'
  if (!Number.isInteger(tabSize) || tabSize < 2 || tabSize > 8) return 'tabSize'
  return null
}
