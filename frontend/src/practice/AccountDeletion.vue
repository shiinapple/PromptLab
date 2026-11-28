<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError } from './api'
import { readDeletion, requestDeletion, withdrawDeletion, type DeletionStatus } from './account'
import { canWithdraw } from './deletion'
import { t } from '../model/copy'

const status = ref<DeletionStatus>({ status: 'NONE', requestedAt: null, effectiveAt: null })
const loading = ref(true)
const busy = ref(false)
const confirming = ref(false)
const understood = ref(false)
const error = ref('')
const notice = ref('')

const pending = computed(() => status.value.status === 'PENDING')
const withdrawable = computed(() => canWithdraw(status.value, Date.now()))
const effectiveText = computed(() =>
  status.value.effectiveAt ? new Date(status.value.effectiveAt).toLocaleString() : '',
)

function message(err: unknown) {
  return err instanceof ApiError ? err.message : (err as Error).message
}

async function load() {
  try {
    status.value = await readDeletion()
  } catch (err) {
    error.value = message(err)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startConfirm() {
  error.value = ''
  notice.value = ''
  understood.value = false
  confirming.value = true
}

async function submit() {
  if (!understood.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    status.value = await requestDeletion()
    confirming.value = false
    notice.value = t('已提交申请，3 天反悔期内可以撤回。', 'Request submitted. You can withdraw it during the 3-day grace period.')
  } catch (err) {
    error.value = message(err)
  } finally {
    busy.value = false
  }
}

async function withdraw() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await withdrawDeletion()
    status.value = { status: 'NONE', requestedAt: null, effectiveAt: null }
    notice.value = t('已撤回删除申请，账号保持不变。', 'The deletion request was withdrawn. Your account is unchanged.')
  } catch (err) {
    error.value = message(err)
    await load()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="account-deletion" aria-labelledby="deletion-heading">
    <h3 id="deletion-heading">{{ t('删除账号', 'Delete account') }}</h3>
    <p class="help">
      {{ t(
        '提交后进入 3 天反悔期，期间可以撤回。到期后，账号、草稿、设置、运行记录和归档会从在线数据中删除，无法恢复。已有备份可能在 7 天保留窗口内仍含该账号数据。',
        'After you submit, a 3-day grace period starts and you can withdraw. When it ends, your account, drafts, settings, runs, and archives are deleted from live data and cannot be recovered. Existing backups may still contain this account for their 7-day retention window.',
      ) }}
    </p>
    <p v-if="loading" role="status">{{ t('正在读取删除状态…', 'Loading deletion status…') }}</p>

    <template v-else-if="pending">
      <p class="help" role="status">
        {{ t('已申请删除，将于', 'Deletion requested. It takes effect at') }} {{ effectiveText }}
        {{ t('生效。', '.') }}
      </p>
      <button v-if="withdrawable" type="button" :disabled="busy" @click="withdraw">
        {{ t('撤回删除申请', 'Withdraw deletion request') }}
      </button>
      <p v-else class="help" role="status">{{ t('反悔期已结束，删除正在处理中。', 'The grace period has ended. Deletion is in progress.') }}</p>
    </template>

    <template v-else-if="confirming">
      <label class="field">
        <input v-model="understood" type="checkbox" :disabled="busy" />
        {{ t('我已了解到期后数据无法恢复，且备份可能在 7 天内仍保留该数据。', 'I understand that data cannot be recovered after the grace period, and backups may keep it for 7 days.') }}
      </label>
      <div class="actions">
        <button type="button" :disabled="busy" @click="confirming = false">{{ t('取消', 'Cancel') }}</button>
        <button type="button" :disabled="busy || !understood" @click="submit">
          {{ t('确认提交删除申请', 'Confirm deletion request') }}
        </button>
      </div>
    </template>

    <button v-else-if="!loading" type="button" :disabled="busy" @click="startConfirm">
      {{ t('申请删除账号', 'Request account deletion') }}
    </button>

    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="help" role="status">{{ notice }}</p>
  </section>
</template>
