<script setup lang="ts">
import type { Run, RunMode } from './api'
import type { DirectStage } from './useDirectRun'
import { ModelError } from '../model/direct'
import { modelMessage, t } from '../model/copy'

defineProps<{
  run: Run
  /** 页面当前选中的运行方式，可能已经和这条运行的方式不同。 */
  selectedMode: RunMode
  busy: boolean
  error: string
  stage: DirectStage | undefined
  seconds: number
  unsaved: boolean
  text: string
}>()
defineEmits<{ stop: []; retrySave: []; useManual: [] }>()
</script>

<template>
  <p v-if="run.failureCode && !error" class="help">{{ modelMessage(new ModelError(run.failureCode)) }}</p>
  <div v-if="stage === 'request' || unsaved || (run.status !== 'COMPLETED' && selectedMode !== 'D0')" class="actions">
    <template v-if="stage === 'request'">
      <span class="meta">{{ seconds }} s</span>
      <button type="button" @click="$emit('stop')">{{ t('停止请求', 'Stop request') }}</button>
    </template>
    <button v-if="unsaved && !busy" type="button" @click="$emit('retrySave')">
      {{ t('重新保存结果', 'Retry saving result') }}
    </button>
    <button v-if="!busy && run.status !== 'COMPLETED' && selectedMode !== 'D0'" type="button" @click="$emit('useManual')">
      {{ t('转手动运行', 'Switch to manual run') }}
    </button>
  </div>
  <p v-if="!busy && run.status === 'PENDING' && !unsaved" class="help">
    {{ t('刷新会结束浏览器内的请求。可新建直连运行，或切换手动运行。',
      'Reloading ends browser requests. Start a new direct run or switch to manual.') }}
  </p>
  <template v-if="text && run.rawOutput === null">
    <h3>{{ stage === 'request' ? t('模型输出', 'Model output')
      : t('本页收到的正文', 'Text received in this page') }}</h3>
    <pre id="direct-text">{{ text }}</pre>
  </template>
</template>
