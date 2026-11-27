<script setup lang="ts">
import { t } from '../i18n/locale'

defineEmits<{ configure: []; useManual: []; close: [] }>()
</script>

<template>
  <section class="panel guide" :aria-label="t('开始练习', 'Get started')">
    <div class="section-heading">
      <h2>{{ t('开始练习', 'Get started') }}</h2>
      <button type="button" @click="$emit('close')">{{ t('关闭引导', 'Close guide') }}</button>
    </div>
    <div class="paths">
      <div>
        <h3>{{ t('配置模型直连', 'Connect a model') }}</h3>
        <p class="help">{{ t('填写 Base URL、Model ID 和 API Key。Key 默认只保存在本页内存，不发送到平台。',
          'Enter the base URL, model ID and API key. The key stays in page memory by default and is never sent to the platform.') }}</p>
        <button type="button" @click="$emit('configure')">{{ t('配置模型直连', 'Connect a model') }}</button>
      </div>
      <div>
        <h3>{{ t('无需配置，手动运行（D0）', 'No setup: manual run (D0)') }}</h3>
        <p class="help">{{ t('冻结请求，在你自己的工具中运行，再把结果导入或粘贴回来判题。',
          'Freeze the request, run it in your own tool, then import or paste the result to grade it.') }}</p>
        <button type="button" @click="$emit('useManual')">{{ t('使用手动运行', 'Use manual run') }}</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.guide { margin-bottom: var(--space-4); }
.paths { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-4); }
.paths h3 { margin-top: 0; }
@media (max-width: 48rem) { .paths { grid-template-columns: minmax(0, 1fr); } }
</style>
