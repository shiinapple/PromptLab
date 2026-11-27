<script setup lang="ts">
import type { Check } from './api'
import { checkLabel, describeFailure } from './labels'
import { t } from '../i18n/locale'

defineProps<{ checks: Check[] }>()
</script>

<template>
  <ul class="checks">
    <li v-for="check in checks" :key="check.id" :class="{ 'field-error': check.status === 'FAIL' }">
      <strong>{{ checkLabel(check) }}</strong>
      <span>
        <span aria-hidden="true">{{ check.status === 'PASS' ? '✓' : '✕' }}</span>
        {{ check.status === 'PASS' ? t('通过', 'Passed') : describeFailure(check) }}
      </span>
    </li>
  </ul>
</template>
