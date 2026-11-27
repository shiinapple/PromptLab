<script setup lang="ts">
import type { Problem } from './api'
import { t } from '../model/copy'

defineProps<{ problem: Problem; publicCase: Problem['cases'][number]; historical: boolean; loading: boolean }>()
defineEmits<{ current: [] }>()
</script>

<template>
  <aside class="panel">
    <h2>{{ t('题目说明', 'Problem') }}</h2>
    <p>{{ problem.description }}</p>
    <p v-if="historical" class="help">
      {{ t('旧版本', 'Previous version') }}
      <button type="button" :disabled="loading" @click="$emit('current')">{{ t('切回当前版本', 'Switch to current version') }}</button>
    </p>
    <h3>{{ t('公开用例', 'Public case') }}</h3>
    <p v-for="(value, name) in publicCase.variables" :key="name" class="case-text">{{ value }}</p>
    <p class="meta">题目 {{ problem.problemId }} · v{{ problem.version }} · {{ publicCase.caseId }}</p>
  </aside>
</template>
