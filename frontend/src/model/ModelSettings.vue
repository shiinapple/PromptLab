<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { ConnectionStore, normalizeBase, validKey, validModel } from './connection'
import { DirectTransport, ModelError } from './direct'
import { modelMessage, t } from './copy'

const props = defineProps<{ store: ConnectionStore; transport: DirectTransport }>()
const emit = defineEmits<{ saved: []; close: [] }>()
const name = ref(props.store.profile?.name ?? '')
const base = ref(props.store.profile?.base ?? '')
const model = ref(props.store.profile?.model ?? '')
const stream = ref(props.store.profile?.stream ?? true)
const key = ref('')
const remember = ref(props.store.remember)
const hasKey = ref(Boolean(props.store.getKey(props.store.profile?.base)))
const models = ref<string[]>([])
const feedback = ref('')
const invalid = ref(false)
const busy = ref(false)
const savedProfile = shallowRef(props.store.profile)
const savedRemember = ref(props.store.remember)
let controller: AbortController | undefined

const endpoint = computed(() => {
  try {
    return normalizeBase(base.value, location.origin)
  } catch {
    return ''
  }
})
const targetChanged = computed(() => Boolean(savedProfile.value && endpoint.value !== savedProfile.value.base))
const displayEndpoint = computed(() => {
  const active = props.store.getKey(props.store.profile?.base)
  return [key.value.trim(), active].some(value => value && endpoint.value.includes(value)) ? '' : endpoint.value
})
const canQuery = computed(() => {
  const current = savedProfile.value
  return Boolean(current && current.base === endpoint.value && !key.value
    && current.model === model.value.trim() && current.stream === stream.value
    && current.name === name.value.trim() && savedRemember.value === remember.value)
})

function editBase() {
  key.value = ''
  models.value = []
  feedback.value = ''
}

function report(message: string, error = false) {
  feedback.value = message
  invalid.value = error
}

function save() {
  const nextKey = key.value.trim()
  const previousKey = props.store.getKey(props.store.profile?.base)
  const profile = { name: name.value.trim(), base: endpoint.value, model: model.value.trim(), stream: stream.value }
  if (!profile.base || (profile.model && !validModel(profile.model)) || !validKey(nextKey)) {
    report(modelMessage(new ModelError('CONFIG')), true)
    return
  }
  if ([nextKey, previousKey].some(secret => secret && [profile.name, profile.base, profile.model].some(value => value.includes(secret)))) {
    report(modelMessage(new ModelError('CREDENTIAL')), true)
    return
  }
  if (targetChanged.value && !window.confirm(t(
    `改用 ${profile.base}？已保存的 Key 会清除，请为新地址重新填写。`,
    `Switch to ${profile.base}? The saved key will be cleared. Enter a key separately for the new address.`,
  ))) return
  if (targetChanged.value) {
    remember.value = false
    key.value = ''
  }
  const persisted = props.store.save(profile, key.value.trim(), remember.value)
  base.value = profile.base
  key.value = ''
  remember.value = props.store.remember
  hasKey.value = Boolean(props.store.getKey(profile.base))
  savedProfile.value = props.store.profile
  savedRemember.value = props.store.remember
  emit('saved')
  report(persisted ? t('连接设置已保存。', 'Connection settings saved.')
    : t('浏览器存储不可用。设置保留在当前页面。', 'Browser storage is unavailable. Settings apply to this page.'))
}

function clear() {
  props.store.clearKey()
  key.value = ''
  hasKey.value = false
  remember.value = false
  models.value = []
  savedProfile.value = props.store.profile
  savedRemember.value = props.store.remember
  emit('saved')
  report(t('已清除 API Key。', 'API key cleared.'))
}

function remove() {
  props.store.remove()
  clear()
  name.value = ''
  base.value = ''
  model.value = ''
  stream.value = true
  report(t('已删除连接。', 'Connection deleted.'))
}

function chooseModel(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (value) model.value = value
}

async function query(list: boolean) {
  const profile = props.store.profile
  if (busy.value || !profile || !canQuery.value || (!list && !validModel(profile.model))) return
  controller = new AbortController()
  busy.value = true
  report(list ? t('正在获取模型列表…', 'Loading models…') : t('正在测试连接…', 'Testing connection…'))
  const start = performance.now()
  try {
    const target = { base: profile.base, key: props.store.getKey(profile.base) }
    if (list) {
      models.value = await props.transport.list(target, { signal: controller.signal })
      report(models.value.length ? t('选择一个模型，或手填 Model ID 后保存。', 'Choose a model or enter a model ID and save.')
        : t('列表为空，可以手填 Model ID。', 'The list is empty. You can enter a model ID.'))
    } else {
      await props.transport.test(target, profile.model, { signal: controller.signal })
      const elapsed = Math.round(performance.now() - start)
      report(t(`连接成功，用时 ${elapsed} ms。`, `Connection succeeded in ${elapsed} ms.`))
    }
  } catch (err) {
    const tip = list ? t('可以手填 Model ID。', 'You can enter a model ID manually.') : ''
    report([modelMessage(err), tip].filter(Boolean).join(' '), true)
  } finally {
    controller = undefined
    busy.value = false
  }
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="panel connection" aria-labelledby="connection-title">
    <div class="section-heading">
      <h2 id="connection-title">{{ t('模型连接', 'Model connection') }}</h2>
      <button type="button" @click="emit('close')">{{ t('返回练习', 'Back to practice') }}</button>
    </div>
    <p class="help">{{ t('只连接你信任的服务。API Key 默认仅保留在本页内存，直接发给所选模型服务。',
      'Connect to a service you trust. By default the API key stays in page memory and is sent directly to that service.') }}</p>
    <form @submit.prevent="save">
      <fieldset :disabled="busy">
        <div class="connection-fields">
          <label>{{ t('连接名称（可选）', 'Connection name (optional)') }}
            <input id="model-name" v-model="name" type="text" maxlength="80" autocomplete="off">
          </label>
          <label>Base URL
            <input id="model-base" v-model="base" type="text" placeholder="https://api.example.com/v1" autocomplete="off"
              spellcheck="false" maxlength="2048" required @input="editBase">
          </label>
          <label>Model ID
            <input id="model-id" v-model="model" type="text" maxlength="256" spellcheck="false" autocomplete="off">
          </label>
          <label>{{ t('API Key（可选）', 'API key (optional)') }}
            <input id="model-key" v-model="key" type="password" autocomplete="off" maxlength="4096"
              :placeholder="hasKey && !targetChanged ? t('已配置', 'Configured') : undefined">
          </label>
        </div>
        <p v-if="displayEndpoint" class="meta" id="model-endpoints">
          POST <code>{{ displayEndpoint }}/chat/completions</code><br>GET <code>{{ displayEndpoint }}/models</code>
        </p>
        <p v-else-if="base" class="help">{{ t('请输入 HTTPS 或本机回环的实际 API 前缀。', 'Enter the API prefix on HTTPS or loopback.') }}</p>
        <p v-if="targetChanged" class="help">{{ t('保存新地址时会清除旧 Key。', 'Saving a different address clears the old key.') }}</p>
        <label v-if="models.length" class="output-label">{{ t('模型列表', 'Available models') }}
          <select id="available-models" :value="model" @change="chooseModel">
            <option value="">{{ t('选择或手填', 'Choose or type') }}</option>
            <option v-for="id in models" :key="id" :value="id">{{ id }}</option>
          </select>
        </label>
        <label class="confirm"><input id="remember-key" v-model="remember" type="checkbox">
          {{ t('在此标签页会话保留 API Key', 'Remember the API key for this tab session') }}
        </label>
        <label class="confirm"><input id="model-stream" v-model="stream" type="checkbox">
          {{ t('练习时使用流式输出', 'Stream output during practice') }}
        </label>
        <div class="actions connection-actions">
          <button type="submit" class="primary">{{ t('保存连接', 'Save connection') }}</button>
          <button type="button" :disabled="!canQuery" @click="query(true)">{{ t('获取模型列表', 'List models') }}</button>
          <button type="button" :disabled="!canQuery || !validModel(model.trim())" @click="query(false)">{{ t('测试连接', 'Test connection') }}</button>
          <button type="button" @click="clear">{{ t('清除 Key', 'Clear key') }}</button>
          <button type="button" :disabled="!savedProfile" @click="remove">{{ t('删除连接', 'Delete connection') }}</button>
        </div>
      </fieldset>
    </form>
    <button v-if="busy" type="button" @click="controller?.abort()">{{ t('停止请求', 'Stop request') }}</button>
    <p class="help">{{ t('连接测试会发送一条短请求，可能消耗模型额度。', 'Testing sends a short request and may use model quota.') }}</p>
    <p v-if="feedback" id="model-feedback" :class="{ 'field-error': invalid }" aria-live="polite">{{ feedback }}</p>
  </section>
</template>

<style scoped>
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
.connection { margin-bottom: var(--space-5); }
.connection-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-3); margin-bottom: var(--space-3); }
.connection-fields input, select {
  display: block; width: 100%; color: inherit; font: inherit; padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-line); background: var(--color-field); border-radius: var(--radius-control);
}
.connection-actions { justify-content: flex-start; margin-bottom: var(--space-3); }
@media (max-width: 40rem) { .connection-fields { grid-template-columns: minmax(0, 1fr); } }
</style>
