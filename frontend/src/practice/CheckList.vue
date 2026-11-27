<script setup lang="ts">
import type { Check } from './api'
import { checkLabel, describeFailure } from './labels'

defineProps<{ checks: Check[] }>()
</script>

<template>
  <ul class="checks">
    <li v-for="check in checks" :key="check.id" :class="{ 'field-error': check.status === 'FAIL' }">
      <strong>{{ checkLabel(check.id) }}</strong>
      <span>{{ check.status === 'PASS' ? '通过' : describeFailure(check) }}</span>
    </li>
  </ul>
</template>
