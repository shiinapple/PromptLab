<script setup lang="ts">
import { ref } from 'vue'
import type { Problem } from './api'
import { t } from '../i18n/locale'
import { useNarrow } from '../app/useNarrow'

defineProps<{ problem: Problem; publicCase: Problem['cases'][number]; historical: boolean; loading: boolean }>()
defineEmits<{ current: []; selectCase: [id: string] }>()

// 窄屏默认折叠题目说明，桌面端始终展开。
const narrow = useNarrow()
const expanded = ref(!narrow.value)
</script>

<template>
  <aside class="panel">
    <h2>
      <button type="button" class="disclosure" aria-controls="problem-body" :aria-expanded="expanded || !narrow"
        :disabled="!narrow" @click="expanded = !expanded">
        {{ t('题目说明', 'Problem') }}
        <span v-if="narrow" aria-hidden="true">{{ expanded ? '▴' : '▾' }}</span>
      </button>
    </h2>
    <div id="problem-body" v-show="expanded || !narrow">
      <p>{{ problem.description }}</p>
      <p v-if="historical" class="help">
        {{ t('旧版本', 'Previous version') }}
        <button type="button" :disabled="loading" @click="$emit('current')">{{ t('切回当前版本', 'Switch to current version') }}</button>
      </p>
      <h3>{{ t('公开用例', 'Public cases') }}</h3>
      <div v-if="problem.cases.length > 1" class="run-options" role="group" :aria-label="t('公开用例', 'Public cases')">
        <button v-for="(item, index) in problem.cases" :key="item.caseId" type="button" :disabled="loading"
          :aria-pressed="publicCase.caseId === item.caseId" @click="$emit('selectCase', item.caseId)">
          {{ t('用例', 'Case') }} {{ index + 1 }}
        </button>
      </div>
      <p v-for="(value, name) in publicCase.variables" :key="name" class="case-text">{{ value }}</p>
      <p class="meta">{{ t('题目', 'Problem') }} {{ problem.problemId }} · v{{ problem.version }} · {{ publicCase.caseId }}</p>
    </div>
  </aside>
</template>

<style scoped>
.disclosure { all: unset; cursor: pointer; color: inherit; font: inherit; font-weight: var(--font-weight-medium); }
.disclosure:disabled { cursor: default; }
.disclosure:focus-visible { outline: var(--focus-ring-width) solid var(--color-primary); outline-offset: var(--focus-ring-offset); }
</style>
