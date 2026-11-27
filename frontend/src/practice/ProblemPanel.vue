<script setup lang="ts">
import type { Problem } from './api'
import { t } from '../model/copy'

defineProps<{ problem: Problem; publicCase: Problem['cases'][number]; historical: boolean; loading: boolean }>()
defineEmits<{ current: []; selectCase: [id: string] }>()
</script>

<template>
  <aside class="panel">
    <h2>{{ t('题目说明', 'Problem') }}</h2>
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
    <p class="meta">题目 {{ problem.problemId }} · v{{ problem.version }} · {{ publicCase.caseId }}</p>
  </aside>
</template>
