<script setup lang="ts">
import { computed } from 'vue'
import type { Run, RunMode } from './api'
import type { DirectStage } from './useDirectRun'
import { ModelError } from '../model/direct'
import { modelMessage } from '../model/copy'
import { failureHints } from '../model/hints'
import { t } from '../i18n/locale'

const props = defineProps<{
  run: Run
  /** 页面当前选中的运行方式，可能已经和这条运行的方式不同。 */
  selectedMode: RunMode
  busy: boolean
  error: string
  stage: DirectStage | undefined
  seconds: number
  unsaved: boolean
  text: string
  /** 本页最近一次直连失败的原因；刷新后的历史失败只按 run.failureCode 推断。 */
  failure?: ModelError
}>()
defineEmits<{ stop: []; retrySave: []; useManual: [] }>()

const failed = computed(() => props.failure ?? (props.run.failureCode ? new ModelError(props.run.failureCode) : undefined))
const hints = computed(() => (failed.value && !props.busy && props.run.status !== 'COMPLETED' ? failureHints(failed.value) : []))
</script>

<template>
  <p v-if="run.failureCode && !error" class="help">{{ modelMessage(new ModelError(run.failureCode)) }}</p>
  <div v-if="stage === 'request' || unsaved || (run.status !== 'COMPLETED' && selectedMode !== 'D0')" class="actions">
    <template v-if="stage === 'request'">
      <span class="meta" role="status" aria-live="polite">{{ seconds }} s</span>
      <button type="button" @click="$emit('stop')">{{ t('停止请求', 'Stop request') }}</button>
    </template>
    <button v-if="unsaved && !busy" type="button" @click="$emit('retrySave')">
      {{ t('重新保存结果', 'Retry saving result') }}
    </button>
    <button v-if="!busy && run.status !== 'COMPLETED' && selectedMode !== 'D0'" type="button" @click="$emit('useManual')">
      {{ t('转手动运行', 'Switch to manual run') }}
    </button>
  </div>
  <ul v-if="hints.length" class="hints" :aria-label="t('排查要点', 'Troubleshooting')">
    <li v-for="hint in hints" :key="hint">{{ hint }}</li>
  </ul>
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

<style scoped>
.hints { margin: 0 0 var(--space-3); padding-left: var(--space-5); font-size: var(--font-size-message); color: var(--color-muted-ink); }
.hints li { margin-bottom: var(--space-1); }
</style>
