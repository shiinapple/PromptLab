import { getJson, send } from './api'
import type { EditorPrefs, Locale, Settings } from './settings'

export const readSettings = () => getJson<Settings>('/api/settings')

export const saveSettings = (patch: { locale: Locale; editorPrefs: EditorPrefs }) =>
  send<Settings>('PATCH', '/api/settings', patch)
