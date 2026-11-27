<script setup lang="ts">
import type { Problem } from './api'
import { t } from '../model/copy'

defineProps<{ problem: Problem; publicCase: Problem['cases'][number] }>()
</script>

<template>
  <aside class="panel">
    <h2>{{ t('题目说明', 'Problem') }}</h2>
    <p>{{ problem.description }}</p>
    <h3>{{ t('公开用例', 'Public case') }}</h3>
    <p v-for="(value, name) in publicCase.variables" :key="name" class="case-text">{{ value }}</p>
    <p class="meta">题目 {{ problem.problemId }} · v{{ problem.version }} · {{ publicCase.caseId }}</p>
  </aside>
</template>
