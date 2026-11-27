<script setup lang="ts">
import { t } from '../i18n/locale'

defineProps<{ raw: string; busy: boolean }>()
const emit = defineEmits<{ copyRequest: []; download: []; importFile: [file: File]; edit: [value: string]; grade: [] }>()
const confirmed = defineModel<boolean>('confirmed', { required: true })

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 清空后，同一个文件可以再选一次
  if (file) emit('importFile', file)
}

function paste(event: ClipboardEvent) {
  if (!event.clipboardData) return
  const target = event.target as HTMLTextAreaElement
  const text = event.clipboardData.getData('text/plain')
  // Textareas normalize CRLF. Preserve clipboard text for the usual whole-response paste.
  if (target.value === '' || (target.selectionStart === 0 && target.selectionEnd === target.value.length)) {
    event.preventDefault()
    emit('edit', text)
  }
}
</script>

<template>
  <ol class="steps" :aria-label="t('手动运行步骤', 'Manual run steps')">
    <li>{{ t('复制或下载请求。', 'Copy or download the request.') }}</li>
    <li>{{ t('在外部工具中执行，并把 Model ID 替换为你使用的模型。', 'Run it in your tool and replace the model ID with yours.') }}</li>
    <li>{{ t('核对下方的用例与运行 ID 一致后，导入响应 JSON，或粘贴完整正文。', 'Check that the case and run ID match, then import the response JSON or paste the complete output.') }}</li>
  </ol>
  <div class="actions export">
    <button type="button" :disabled="busy" @click="$emit('copyRequest')">{{ t('复制请求', 'Copy request') }}</button>
    <button type="button" :disabled="busy" @click="$emit('download')">{{ t('下载请求', 'Download request') }}</button>
    <label class="file-label" for="response-file">{{ t('导入响应 JSON', 'Import response JSON') }}</label>
    <input id="response-file" type="file" accept=".json,application/json" :disabled="busy" @change="selectFile">
  </div>
  <label class="output-label" for="raw">{{ t('模型原始正文', 'Original model output') }}</label>
  <textarea id="raw" :value="raw" rows="5" :disabled="busy" spellcheck="false"
    @input="$emit('edit', ($event.target as HTMLTextAreaElement).value)" @paste="paste" />
  <label class="confirm"><input id="confirm" v-model="confirmed" type="checkbox" :disabled="busy || !raw.trim()">
    {{ t('我确认正文完整，并且来自当前运行和用例', 'I confirm the output is complete and comes from this run and case') }}
  </label>
  <div class="actions">
    <button id="grade" type="button" class="primary" :disabled="busy || !confirmed || !raw.trim()" @click="$emit('grade')">{{ t('提交判题', 'Grade output') }}</button>
  </div>
</template>

<style scoped>
.steps { margin: 0 0 var(--space-3); padding-left: var(--space-5); color: var(--color-muted-ink); font-size: var(--font-size-message); }
.steps li { margin-bottom: var(--space-1); }
</style>
