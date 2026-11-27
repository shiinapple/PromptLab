<script setup lang="ts">
import type { RunMode } from './api'
import type { Message } from './messages'
import type { ConnectionProfile } from '../model/connection'
import { computed } from 'vue'
import { validModel } from '../model/connection'
import { t } from '../model/copy'
import type { EditorPrefs } from './settings'
import { effectivePrefs } from './settings'

const props = defineProps<{
  editorPrefs?: EditorPrefs
  preview: { messages?: Message[]; error?: string }
  /** 编辑槽所在消息的角色。 */
  role: string | undefined
  mode: RunMode
  connection: ConnectionProfile | undefined
  busy: boolean
  historical: boolean
  settingsOpen: boolean
  hasRun: boolean
}>()
defineEmits<{ restore: []; run: []; configure: []; 'update:mode': [value: RunMode] }>()
const prompt = defineModel<string>({ required: true })
const promptStyle = computed(() => {
  const prefs = effectivePrefs(props.editorPrefs ?? {})
  return { fontSize: `${prefs.fontSize}px`, tabSize: prefs.tabSize, whiteSpace: prefs.wordWrap ? 'pre-wrap' : 'pre' }
})
</script>

<template>
  <section class="panel">
    <h2><label for="prompt">{{ role === 'system' ? 'System Prompt' : 'Prompt' }}</label></h2>
    <p class="help" id="prompt-hint">{{ t('编辑后可随时恢复模板；未保存的内容切换语言时保留。', 'You can restore the template at any time. Unsaved text stays when you switch language.') }}</p>
    <textarea id="prompt" v-model="prompt" rows="6" spellcheck="false" :style="promptStyle"
      :aria-describedby="preview.error ? 'prompt-hint prompt-error' : 'prompt-hint'" :aria-invalid="Boolean(preview.error)" />
    <p v-if="preview.error" id="prompt-error" class="field-error" role="alert">{{ preview.error }}</p>
    <details>
      <summary>{{ t('请求预览', 'Request preview') }}</summary>
      <pre id="preview">{{ preview.messages ? JSON.stringify(preview.messages, null, 2) : t('请先修正 Prompt。', 'Fix the prompt first.') }}</pre>
    </details>
    <div class="run-options" role="group" :aria-label="t('运行方式', 'Run mode')">
      <button type="button" :aria-pressed="mode === 'DIRECT'" :disabled="busy" @click="$emit('update:mode', 'DIRECT')">
        {{ t('浏览器直连', 'Direct connection') }}
      </button>
      <button type="button" :aria-pressed="mode === 'D0'" :disabled="busy" @click="$emit('update:mode', 'D0')">
        {{ t('手动运行', 'Manual run') }}
      </button>
    </div>
    <p v-if="mode === 'DIRECT'" class="help">
      <template v-if="connection && validModel(connection.model)">
        {{ connection.name || connection.model }} · {{ connection.stream ? t('流式输出', 'Streaming') : t('普通响应', 'JSON response') }}
      </template>
      <button v-else type="button" :disabled="busy" @click="$emit('configure')">{{ t('配置模型', 'Configure model') }}</button>
    </p>
    <div class="actions">
      <button type="button" @click="$emit('restore')">{{ t('恢复模板', 'Restore template') }}</button>
      <button class="primary" type="button"
        :disabled="historical || !!preview.error || busy || (mode === 'DIRECT' && (settingsOpen || !connection || !validModel(connection.model)))"
        @click="$emit('run')">
        {{ mode === 'DIRECT' ? t('运行并判题', 'Run and grade')
          : hasRun ? t('新建运行', 'New run') : t('创建运行', 'Create run') }}
      </button>
    </div>
  </section>
</template>
