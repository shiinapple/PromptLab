<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { usePractice } from '../practice/usePractice'
import { runTitle, statusSymbol } from '../practice/labels'
import SettingsPanel from '../practice/SettingsPanel.vue'
import type { EditorPrefs, Settings } from '../practice/settings'
import { readSettings } from '../practice/settingsApi'
import ProblemPanel from '../practice/ProblemPanel.vue'
import PromptPanel from '../practice/PromptPanel.vue'
import DirectRunSection from '../practice/DirectRunSection.vue'
import ManualRunSection from '../practice/ManualRunSection.vue'
import CheckList from '../practice/CheckList.vue'
import HistoryPanel from '../practice/HistoryPanel.vue'
import LoginForm from '../account/LoginForm.vue'
import ModelSettings from '../model/ModelSettings.vue'
import OnboardingBanner from '../guide/OnboardingBanner.vue'
import { dismissGuide, guideDismissed } from '../guide/guide'
import { getLocale, t, type Locale } from '../i18n/locale'

const {
  model: { store, transport, connection, syncConnection, ready },
  feedback: { busy, error, notice, action },
  direct: { text: directText, stage, seconds, pending, save, stop, lastFailure },
  manual: { raw, confirmed, edit, importFile, grade },
  problems, problem, historical, publicCase, run, runGrading, slot, prompt, restoreTemplate, preview, requestJson, snapshotDraft, mode, loading, settingsOpen,
  load, selectProblem, selectCase, setMode, freeze, copyText, download,
  accountMode, profile, authRequired, conflict, draftSaving, historyRevision, openRun, signedIn, signOut, useServerDraft, overwriteDraft, copyDraftToCurrent, changeLocale,
} = usePractice()

const currentLocale = computed(() => getLocale())
const otherLocale = computed<Locale>(() => (currentLocale.value === 'en' ? 'zh-CN' : 'en'))

const title = computed(() => run.value ? runTitle(run.value, stage.value, !!pending.value, runGrading.value) : '')
const failed = computed(() => run.value?.status === 'FAILED'
  || run.value?.verdict === 'FORMAT_ERROR' || run.value?.verdict === 'WRONG_ANSWER')
const symbol = computed(() => (run.value ? statusSymbol(run.value.verdict, failed.value) : ''))

// 引导：未配置模型时自动显示，关闭后按账号记住；undefined 表示按默认规则决定。
const guideOpen = ref<boolean>()
const guideUser = computed(() => (accountMode.value ? profile.value?.userId : undefined))
const showGuide = computed(() => guideOpen.value ?? (!ready() && !guideDismissed(guideUser.value)))
watch(guideUser, () => { guideOpen.value = undefined })
function closeGuide() {
  dismissGuide(guideUser.value)
  guideOpen.value = false
}

// 关闭设置后把焦点还给触发按钮，避免键盘用户丢失位置。
async function closeSettings() {
  settingsOpen.value = false
  await nextTick()
  document.getElementById('settings-toggle')?.focus()
}

const prefsOpen = ref(false)
const editorPrefs = ref<EditorPrefs>({})
// 账号登录后读取一次编辑器偏好；失败时保持默认值，不影响练习。
watch(profile, async current => {
  if (!current) return
  try {
    editorPrefs.value = (await readSettings()).editorPrefs
  } catch {
    editorPrefs.value = {}
  }
}, { immediate: true })
function onPrefsSaved(saved: Settings) {
  editorPrefs.value = saved.editorPrefs
}
</script>

<template>
  <header class="site-header">
    <div>
      <span class="brand">Prompt Practice</span>
      <button id="settings-toggle" type="button" :disabled="busy" :aria-expanded="settingsOpen" aria-controls="model-settings" @click="settingsOpen = !settingsOpen">
        {{ t('模型设置', 'Model settings') }}
      </button>
      <button type="button" :disabled="busy" @click="guideOpen = true">{{ t('查看引导', 'Show guide') }}</button>
    </div>
    <div>
      <button type="button" :disabled="busy || loading" :lang="otherLocale" @click="changeLocale(otherLocale)">
        {{ otherLocale === 'en' ? 'English' : '中文' }}
      </button>
      <template v-if="accountMode && profile">
        <button type="button" :disabled="busy" :aria-expanded="prefsOpen" aria-controls="settings-panel" @click="prefsOpen = !prefsOpen">
          {{ t('偏好设置', 'Preferences') }}
        </button>
        <span class="meta">{{ profile.email }}</span>
        <button type="button" :disabled="busy" @click="signOut">{{ t('退出', 'Sign out') }}</button>
      </template>
    </div>
  </header>
  <main>
    <h1>{{ problem?.title ?? t('Prompt 练习', 'Prompt practice') }}</h1>
    <LoginForm v-if="accountMode && authRequired" @signed-in="signedIn" />
    <p v-if="loading" role="status">{{ t('正在加载题目…', 'Loading problem…') }}</p>
    <div v-if="conflict" class="feedback error" role="alert" aria-live="assertive">
      {{ t(`草稿在其他地方已更新（服务器 revision ${conflict.serverRevision}）。`,
        `The draft changed elsewhere (server revision ${conflict.serverRevision}).`) }}
      <button type="button" :disabled="busy" @click="useServerDraft">{{ t('使用服务器版本', 'Use server draft') }}</button>
      <button type="button" :disabled="busy" @click="overwriteDraft">{{ t('用本地编辑覆盖', 'Overwrite with my edits') }}</button>
    </div>
    <div v-if="error" class="feedback error" role="alert" aria-live="assertive">{{ error }}</div>
    <p v-if="notice" class="feedback" role="status">{{ notice }}</p>
    <button v-if="!problem && !loading" type="button" @click="load">{{ t('重新加载', 'Reload') }}</button>
    <OnboardingBanner v-if="showGuide" @configure="settingsOpen = true; guideOpen = false"
      @use-manual="setMode('D0'); closeGuide()" @close="closeGuide" />
    <div id="model-settings" :hidden="!settingsOpen">
      <ModelSettings v-if="settingsOpen" :store="store" :transport="transport"
        @saved="syncConnection" @close="closeSettings" />
    </div>
    <SettingsPanel v-if="accountMode && prefsOpen && profile" id="settings-panel"
      @saved="onPrefsSaved" @close="prefsOpen = false" />
    <nav v-if="problems.length" class="run-options" :aria-label="t('题目列表', 'Problems')">
      <button v-for="item in problems" :key="item.problemId" type="button" :disabled="loading"
        :aria-pressed="problem?.problemId === item.problemId" @click="selectProblem(item.problemId)">
        {{ item.problemId }} · {{ item.title }}
      </button>
    </nav>
    <div v-if="problem && publicCase && !(accountMode && authRequired)" class="workspace">
      <ProblemPanel :problem="problem" :public-case="publicCase" :historical="historical" :loading="loading"
        @current="selectProblem(problem.problemId)" @select-case="selectCase" />

      <div class="activity">
        <PromptPanel v-model="prompt" :editor-prefs="editorPrefs" :role="slot?.role" :preview="preview" :mode="mode" :connection="connection" :busy="busy || loading"
          :settings-open="settingsOpen" :historical="historical" :has-run="!!run" @update:mode="setMode"
          @restore="restoreTemplate" @configure="settingsOpen = true" @run="freeze" />
        <p v-if="accountMode && historical" class="help">
          <button type="button" :disabled="busy || loading" @click="copyDraftToCurrent">{{ t('复制到当前版本', 'Copy to current version') }}</button>
        </p>
        <HistoryPanel v-if="accountMode" :problem-id="problem.problemId" :revision="historyRevision" :busy="busy || loading"
          @open="openRun" />

        <section v-if="run" class="panel" aria-labelledby="run-heading">
          <div class="section-heading">
            <h2 id="run-heading">{{ t('本次运行', 'Current run') }} ·
              {{ run.mode === 'DIRECT' ? t('直连', 'Direct') : t('手动', 'Manual') }} · {{ run.caseId }}</h2>
            <span id="run-status" class="mode" role="status" aria-live="polite" :class="{ passed: run.verdict === 'PASSED', failed }" :data-symbol="symbol">
              {{ title }}
            </span>
          </div>
          <p class="meta" id="run-id">{{ run.problemId }} · v{{ run.version }} · {{ run.caseId }} · {{ run.runId }}</p>
          <p v-if="run.mode === 'DIRECT' && run.reported" class="meta">
            {{ run.reported.modelId }} · {{ run.reported.durationMs }} ms
          </p>
          <details>
            <summary>{{ t('冻结请求', 'Frozen request') }}</summary>
            <pre id="frozen-request">{{ requestJson }}</pre>
          </details>
          <p v-if="snapshotDraft !== undefined && problem.problemId === run.problemId && problem.version === run.version && prompt !== snapshotDraft" class="help">
            {{ t('当前草稿有改动，本次运行仍使用冻结的请求。', 'The draft has changed. This run uses the frozen request.') }}
          </p>
          <DirectRunSection v-if="run.mode === 'DIRECT'" :run="run" :selected-mode="mode" :busy="busy" :error="error"
            :stage="stage" :seconds="seconds" :unsaved="!!pending" :text="directText" :failure="lastFailure"
            @stop="stop" @retry-save="action(save)" @use-manual="setMode('D0')" />
          <ManualRunSection v-if="run.mode === 'D0' && run.status === 'PENDING'" v-model:confirmed="confirmed"
            :raw="raw" :busy="busy" @copy-request="copyText(requestJson, () => t('请求已复制。', 'Request copied.'))"
            @download="download" @import-file="importFile" @edit="edit" @grade="grade" />
          <template v-if="run.rawOutput !== null">
            <div class="section-heading">
              <h3>{{ t('模型原始正文', 'Original model output') }}</h3>
              <button type="button" :disabled="busy" @click="copyText(run.rawOutput, () => t('正文已复制。', 'Output copied.'))">{{ t('复制正文', 'Copy output') }}</button>
            </div>
            <pre id="graded-raw">{{ run.rawOutput }}</pre>
          </template>
          <CheckList v-if="run.checks" :checks="run.checks" />
        </section>
      </div>
    </div>
  </main>
</template>

<style>
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--color-page);
  color: var(--color-ink);
  font: var(--font-size-body) / var(--line-height-base) var(--font-family-base);
}
.site-header { background: var(--color-card); border-bottom: 1px solid var(--color-muted); }
.site-header div { max-width: var(--content-width); margin: auto; padding: var(--space-3) var(--space-5); display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.brand { font-weight: var(--font-weight-title); color: var(--color-primary); }
main { max-width: var(--content-width); margin: auto; padding: var(--space-6) var(--space-5); }
h1 { margin: 0 0 var(--space-5); font-size: var(--font-size-title); line-height: var(--line-height-title); letter-spacing: var(--tracking-title); }
h2 { margin: 0 0 var(--space-3); font-size: var(--font-size-heading); letter-spacing: var(--tracking-tight); }
h3 { margin: var(--space-5) 0 var(--space-2); font-size: var(--font-size-control); }
p { margin: 0 0 var(--space-3); }
.workspace { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.8fr); gap: var(--space-5); align-items: start; }
.activity { display: grid; gap: var(--space-4); min-width: 0; }
.panel { background: var(--color-card); padding: var(--space-5); border: 1px solid var(--color-muted); border-radius: var(--radius-list); box-shadow: var(--shadow-card); min-width: 0; }
.case-text, pre { margin: 0 0 var(--space-3); padding: var(--space-3); border-radius: var(--radius-control); background: var(--color-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
.meta, .help { color: var(--color-muted-ink); font-size: var(--font-size-message); overflow-wrap: anywhere; }
textarea { display: block; width: 100%; resize: vertical; padding: var(--space-3); border: 1px solid var(--color-line); border-radius: var(--radius-control); font: inherit; color: inherit; background: var(--color-field); }
details { margin: var(--space-3) 0; }
summary { cursor: pointer; color: var(--color-primary); }
details pre { margin-top: var(--space-2); }
.actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: var(--space-2); margin-top: var(--space-4); }
.run-options { display: inline-flex; flex-wrap: wrap; gap: var(--space-1); margin: var(--space-2) 0 var(--space-3); padding: var(--space-1); background: var(--color-secondary); border: 1px solid var(--color-muted); border-radius: var(--radius-list); }
.run-options button { background: transparent; border-color: transparent; color: var(--color-muted-ink); }
.run-options button[aria-pressed='true'] { background: var(--color-card); color: var(--color-ink); font-weight: var(--font-weight-medium); box-shadow: var(--shadow-card); }
.actions.export { justify-content: flex-start; margin-bottom: var(--space-4); }
button { font: inherit; cursor: pointer; color: inherit; background: var(--color-field); padding: var(--space-2) var(--space-3); border: 1px solid var(--color-line); border-radius: var(--radius-control); }
button.primary { color: var(--color-on-primary); background: var(--color-primary); border-color: var(--color-primary); font-weight: var(--font-weight-medium); }
button.primary:enabled:active { background: var(--color-primary-pressed); }
button:disabled { color: var(--color-disabled-ink); cursor: default; }
button.primary:disabled { color: var(--color-on-primary-disabled); background: var(--color-primary-disabled); border-color: var(--color-primary-disabled); }
button:focus-visible, textarea:focus-visible, select:focus-visible, summary:focus-visible, input:focus-visible { outline: var(--focus-ring-width) solid var(--color-primary); outline-offset: var(--focus-ring-offset); }
input[type='checkbox'] { accent-color: var(--color-primary); }
@media (prefers-reduced-motion: no-preference) {
  button { transition: transform var(--dur-fast) var(--ease-out), background-color var(--dur-fast) var(--ease-out); }
  button:enabled:active { transform: scale(var(--scale-pressed)); }
}
.mode { color: var(--color-primary); background: var(--color-success-surface); padding: var(--space-1) var(--space-2); border-radius: var(--radius-pill); font-size: var(--font-size-message); font-weight: var(--font-weight-medium); }
#run-status[data-symbol]::before { content: attr(data-symbol) ' '; }
#run-status:not(.passed) { color: var(--color-secondary-ink); background: var(--color-secondary); }
#run-status.failed { color: var(--color-error-text); background: var(--color-error-surface); }
.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); }
.field-error, .feedback.error { color: var(--color-error-text); }
.feedback { border-radius: var(--radius-control); padding: var(--space-2) var(--space-3); background: var(--color-card); }
.feedback.error { background: var(--color-error-surface); }
.checks li:not(.field-error) span { color: var(--color-success-text); }
.confirm, .output-label { display: block; margin: var(--space-2) 0; }
input[type='file'] { max-width: 100%; }
.checks { list-style: none; padding: 0; margin: 0; }
.checks li { display: grid; grid-template-columns: var(--check-label-width) minmax(0, 1fr); gap: var(--space-2); border-top: 1px solid var(--color-muted); padding: var(--space-2) 0; }
pre { max-width: 100%; overflow-x: auto; }
/* 窄屏断点 768px（48rem）：与 tokens.css 注释及 app/useNarrow.ts 保持一致。 */
@media (max-width: 48rem) {
  main { padding: var(--space-5) var(--space-4); }
  .site-header div { flex-wrap: wrap; padding: var(--space-3) var(--space-4); }
  .workspace { grid-template-columns: minmax(0, 1fr); gap: var(--space-4); }
  .panel { padding: var(--space-4); }
  .actions { justify-content: flex-start; }
}
</style>
