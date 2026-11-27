<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../practice/account'
import { ApiError } from '../practice/api'
import { t } from '../model/copy'

const emit = defineEmits<{ 'signed-in': [] }>()
const email = ref('')
const password = ref('')
const pending = ref(false)
const error = ref('')

async function submit() {
  pending.value = true
  error.value = ''
  try {
    await login(email.value.trim(), password.value)
    password.value = ''
    emit('signed-in')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : (err as Error).message
    password.value = ''
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <section class="panel login" aria-labelledby="login-heading">
    <h2 id="login-heading">{{ t('登录', 'Sign in') }}</h2>
    <form @submit.prevent="submit">
      <label class="field">
        <span>{{ t('邮箱', 'Email') }}</span>
        <input v-model="email" type="email" autocomplete="username" required :disabled="pending">
      </label>
      <label class="field">
        <span>{{ t('密码', 'Password') }}</span>
        <input v-model="password" type="password" autocomplete="current-password" required :disabled="pending">
      </label>
      <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
      <div class="actions">
        <button class="primary" type="submit" :disabled="pending || !email || !password">
          {{ t('登录', 'Sign in') }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.login { max-width: 22rem; margin: var(--space-6) auto; }
.field { display: grid; gap: var(--space-1); margin-bottom: var(--space-3); }
.field input { padding: var(--space-2) var(--space-3); border: 1px solid var(--color-line); border-radius: var(--radius-control); font: inherit; color: inherit; background: var(--color-field); }
.feedback { border-radius: var(--radius-control); padding: var(--space-2) var(--space-3); }
.feedback.error { color: var(--color-error-text); background: var(--color-error-surface); }
</style>
