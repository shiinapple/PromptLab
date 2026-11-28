<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from './api'
import { checkPrefs, DEFAULT_PREFS, type Locale, type Settings } from './settings'
import { readSettings, saveSettings } from './settingsApi'
import { t } from '../model/copy'
import { setLocale } from '../i18n/locale'
import AccountDeletion from './AccountDeletion.vue'

const emit = defineEmits<{ saved: [settings: Settings]; close: [] }>()

const locale = ref<Locale>('zh-CN')
const fontSize = ref<number>(DEFAULT_PREFS.fontSize)
const tabSize = ref<number>(DEFAULT_PREFS.tabSize)
const wordWrap = ref<boolean>(DEFAULT_PREFS.wordWrap)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

function apply(settings: Settings) {
  locale.value = settings.locale
  fontSize.value = settings.editorPrefs.fontSize ?? DEFAULT_PREFS.fontSize
  tabSize.value = settings.editorPrefs.tabSize ?? DEFAULT_PREFS.tabSize
  wordWrap.value = settings.editorPrefs.wordWrap ?? DEFAULT_PREFS.wordWrap
}

function message(err: unknown) {
  return err instanceof ApiError ? err.message : (err as Error).message
}

onMounted(async () => {
  try {
    apply(await readSettings())
  } catch (err) {
    error.value = message(err)
  } finally {
    loading.value = false
  }
})

async function save() {
  error.value = ''
  notice.value = ''
  const size = Number(fontSize.value)
  const tab = Number(tabSize.value)
  const invalid = checkPrefs(size, tab)
  if (invalid === 'fontSize') {
    error.value = t('字号须为 12–32 的整数。', 'Font size must be a whole number from 12 to 32.')
    return
  }
  if (invalid === 'tabSize') {
    error.value = t('Tab 宽度须为 2–8 的整数。', 'Tab size must be a whole number from 2 to 8.')
    return
  }
  busy.value = true
  try {
    const saved = await saveSettings({
      locale: locale.value,
      editorPrefs: { fontSize: size, tabSize: tab, wordWrap: wordWrap.value },
    })
    apply(saved)
    setLocale(saved.locale)
    notice.value = t('偏好已保存。', 'Preferences saved.')
    emit('saved', saved)
  } catch (err) {
    error.value = message(err)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="panel" aria-labelledby="settings-heading">
    <div class="section-heading">
      <h2 id="settings-heading">{{ t('偏好设置', 'Preferences') }}</h2>
      <button type="button" @click="$emit('close')">{{ t('关闭', 'Close') }}</button>
    </div>
    <p v-if="loading" role="status">{{ t('正在读取偏好…', 'Loading preferences…') }}</p>
    <form v-else @submit.prevent="save">
      <label class="field">
        {{ t('语言', 'Language') }}
        <select v-model="locale" :disabled="busy">
          <option value="zh-CN">简体中文</option>
          <option value="en">English</option>
        </select>
      </label>
      <label class="field">
        {{ t('编辑器字号', 'Editor font size') }}
        <input v-model.number="fontSize" type="number" min="12" max="32" step="1" :disabled="busy" />
      </label>
      <label class="field">
        {{ t('Tab 宽度', 'Tab size') }}
        <input v-model.number="tabSize" type="number" min="2" max="8" step="1" :disabled="busy" />
      </label>
      <label class="field">
        <input v-model="wordWrap" type="checkbox" :disabled="busy" />
        {{ t('自动换行', 'Wrap long lines') }}
      </label>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="help" role="status">{{ notice }}</p>
      <button type="submit" :disabled="busy">{{ t('保存偏好', 'Save preferences') }}</button>
    </form>
    <AccountDeletion />
  </section>
</template>
