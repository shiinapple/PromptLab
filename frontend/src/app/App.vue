<script setup lang="ts">
import { computed } from 'vue'
import { usePractice } from '../practice/usePractice'
import { runTitle } from '../practice/labels'
import ProblemPanel from '../practice/ProblemPanel.vue'
import PromptPanel from '../practice/PromptPanel.vue'
import DirectRunSection from '../practice/DirectRunSection.vue'
import ManualRunSection from '../practice/ManualRunSection.vue'
import CheckList from '../practice/CheckList.vue'
import HistoryPanel from '../practice/HistoryPanel.vue'
import LoginForm from '../account/LoginForm.vue'
import ModelSettings from '../model/ModelSettings.vue'
import { t } from '../model/copy'

const {
  model: { store, transport, connection, syncConnection },
  feedback: { busy, error, notice, action },
  direct: { text: directText, stage, seconds, pending, save, stop },
  manual: { raw, confirmed, edit, importFile, grade },
  problems, problem, historical, publicCase, run, runGrading, slot, prompt, restoreTemplate, preview, requestJson, snapshotDraft, mode, loading, settingsOpen,
  load, selectProblem, selectCase, setMode, freeze, copyText, download,
  accountMode, profile, authRequired, conflict, draftSaving, historyRevision, openRun, signedIn, signOut, useServerDraft, overwriteDraft, copyDraftToCurrent,
} = usePractice()

const title = computed(() => run.value ? runTitle(run.value, stage.value, !!pending.value, runGrading.value) : '')
const failed = computed(() => run.value?.status === 'FAILED'
  || run.value?.verdict === 'FORMAT_ERROR' || run.value?.verdict === 'WRONG_ANSWER')
</script>

<template>
  <header class="site-header">
    <div>
      <span class="brand">Prompt Practice</span>
      <button type="button" :disabled="busy" :aria-expanded="settingsOpen" aria-controls="model-settings" @click="settingsOpen = !settingsOpen">
        {{ t('模型设置', 'Model settings') }}
      </button>
    </div>
    <div v-if="accountMode && profile">
      <span class="meta">{{ profile.email }}</span>
      <button type="button" :disabled="busy" @click="signOut">{{ t('退出', 'Sign out') }}</button>
    </div>
  </header>
  <main>
    <h1>{{ problem?.title ?? 'Prompt 练习' }}</h1>
    <LoginForm v-if="accountMode && authRequired" @signed-in="signedIn" />
    <p v-if="loading" role="status">{{ t('正在加载题目…', 'Loading problem…') }}</p>
    <div v-if="conflict" class="feedback error" role="alert">
      {{ t('草稿在其他地方已更新（服务器 revision', 'The draft changed elsewhere (server revision') }} {{ conflict.serverRevision }}）。
      <button type="button" :disabled="busy" @click="useServerDraft">{{ t('使用服务器版本', 'Use server draft') }}</button>
      <button type="button" :disabled="busy" @click="overwriteDraft">{{ t('用本地编辑覆盖', 'Overwrite with my edits') }}</button>
    </div>
    <div v-if="error" class="feedback error" role="alert">{{ error }}</div>
    <p v-if="notice" class="feedback" role="status">{{ notice }}</p>
    <button v-if="!problem && !loading" type="button" @click="load">{{ t('重新加载', 'Reload') }}</button>
    <ModelSettings v-if="settingsOpen" id="model-settings" :store="store" :transport="transport"
      @saved="syncConnection" @close="settingsOpen = false" />
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
        <PromptPanel v-model="prompt" :role="slot?.role" :preview="preview" :mode="mode" :connection="connection" :busy="busy || loading"
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
            <span id="run-status" class="mode" :class="{ passed: run.verdict === 'PASSED', failed }">
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
            :stage="stage" :seconds="seconds" :unsaved="!!pending" :text="directText"
            @stop="stop" @retry-save="action(save)" @use-manual="setMode('D0')" />
          <ManualRunSection v-if="run.mode === 'D0' && run.status === 'PENDING'" v-model:confirmed="confirmed"
            :raw="raw" :busy="busy" @copy-request="copyText(requestJson, t('请求已复制。', 'Request copied.'))"
            @download="download" @import-file="importFile" @edit="edit" @grade="grade" />
          <template v-if="run.rawOutput !== null">
            <div class="section-heading">
              <h3>{{ t('模型原始正文', 'Original model output') }}</h3>
              <button type="button" :disabled="busy" @click="copyText(run.rawOutput, t('正文已复制。', 'Output copied.'))">{{ t('复制正文', 'Copy output') }}</button>
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
@media (max-width: 48rem) {
  .workspace { grid-template-columns: minmax(0, 1fr); gap: var(--space-4); }
  main, .panel { padding: var(--space-4); }
}
</style>
