<script setup lang="ts">
import { english, t } from '../model/copy'

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
  <p class="help"><template v-if="english">Replace <code>YOUR_MODEL</code> in your model tool and run the request. Import the response or paste the complete output.</template>
    <template v-else>在模型工具中替换 <code>YOUR_MODEL</code> 并执行请求，再导入响应或粘贴完整正文。</template></p>
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
    我确认正文完整，并且来自当前运行和用例
  </label>
  <div class="actions">
    <button id="grade" type="button" class="primary" :disabled="busy || !confirmed || !raw.trim()" @click="$emit('grade')">{{ t('提交判题', 'Grade output') }}</button>
  </div>
</template>
