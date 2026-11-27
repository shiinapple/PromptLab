<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from './api'
import { archiveRun, readArchive, readHistory, unarchive, type HistoryItem, type Page } from './account'
import { t } from '../model/copy'

const props = defineProps<{ problemId: string; revision: number; busy: boolean }>()
const emit = defineEmits<{ open: [runId: string] }>()

const runs = ref<HistoryItem[]>([])
const runsNext = ref<string | null>(null)
const archived = ref<HistoryItem[]>([])
const archivedNext = ref<string | null>(null)
const error = ref('')
const loading = ref(false)

const statusLabel = (item: HistoryItem) => {
  if (item.verdict === 'PASSED') return t('通过', 'Passed')
  if (item.verdict) return t('未通过', 'Not passed')
  return item.status
}
const archivedIds = computed(() => new Set(archived.value.map(item => item.runId)))

function merge(page: Page<HistoryItem>, target: HistoryItem[]) {
  const seen = new Set(target.map(item => item.runId))
  return [...target, ...page.items.filter(item => !seen.has(item.runId))]
}

async function reloadRuns() {
  const page = await readHistory(props.problemId)
  runs.value = page.items
  runsNext.value = page.nextCursor
}

async function reloadArchive() {
  const page = await readArchive()
  archived.value = page.items
  archivedNext.value = page.nextCursor
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([reloadRuns(), reloadArchive()])
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : (err as Error).message
  } finally {
    loading.value = false
  }
}

async function more(kind: 'runs' | 'archive') {
  const cursor = kind === 'runs' ? runsNext.value : archivedNext.value
  if (!cursor) return
  try {
    const page = kind === 'runs'
      ? await readHistory(props.problemId, cursor)
      : await readArchive(cursor)
    if (kind === 'runs') {
      runs.value = merge(page, runs.value)
      runsNext.value = page.nextCursor
    } else {
      archived.value = merge(page, archived.value)
      archivedNext.value = page.nextCursor
    }
  } catch (err) {
    error.value = (err as Error).message
  }
}

async function toggle(item: HistoryItem) {
  error.value = ''
  try {
    if (item.submissionId) await unarchive(item.submissionId)
    else await archiveRun(item.runId)
    await refresh()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : (err as Error).message
  }
}

watch(() => [props.problemId, props.revision], refresh, { immediate: true })
</script>

<template>
  <section class="panel" aria-labelledby="history-heading">
    <div class="section-heading">
      <h2 id="history-heading">{{ t('运行历史', 'Run history') }}</h2>
      <button type="button" :disabled="loading || busy" @click="refresh">{{ t('刷新', 'Refresh') }}</button>
    </div>
    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <h3>{{ t('本题运行', 'Runs for this problem') }}</h3>
    <p v-if="!runs.length" class="help">{{ t('暂无运行记录。', 'No runs yet.') }}</p>
    <ul class="runs">
      <li v-for="item in runs" :key="item.runId">
        <span class="meta">v{{ item.version }} · {{ item.caseId }} · {{ item.mode }} · {{ statusLabel(item) }}</span>
        <span class="row-actions">
          <button type="button" :disabled="busy" @click="emit('open', item.runId)">{{ t('打开', 'Open') }}</button>
          <button v-if="item.submissionId || item.status === 'COMPLETED'" type="button" :disabled="busy" @click="toggle(item)">
            {{ item.submissionId ? t('取消归档', 'Unarchive') : t('归档', 'Archive') }}
          </button>
        </span>
      </li>
    </ul>
    <button v-if="runsNext" type="button" :disabled="loading" @click="more('runs')">{{ t('加载更多', 'Load more') }}</button>
    <h3>{{ t('已归档', 'Archived') }}</h3>
    <p v-if="!archived.length" class="help">{{ t('暂无归档。', 'Nothing archived.') }}</p>
    <ul class="runs">
      <li v-for="item in archived" :key="item.runId" :data-archived="archivedIds.has(item.runId)">
        <span class="meta">{{ item.problemId }} · v{{ item.version }} · {{ item.caseId }} · {{ statusLabel(item) }}</span>
        <span class="row-actions">
          <button type="button" :disabled="busy" @click="emit('open', item.runId)">{{ t('打开', 'Open') }}</button>
          <button type="button" :disabled="busy" @click="toggle(item)">{{ t('取消归档', 'Unarchive') }}</button>
        </span>
      </li>
    </ul>
    <button v-if="archivedNext" type="button" :disabled="loading" @click="more('archive')">{{ t('加载更多', 'Load more') }}</button>
  </section>
</template>

<style scoped>
.runs { list-style: none; padding: 0; margin: 0 0 var(--space-3); }
.runs li { display: flex; justify-content: space-between; align-items: center; gap: var(--space-2); border-top: 1px solid var(--color-muted); padding: var(--space-2) 0; }
.row-actions { display: flex; gap: var(--space-2); flex-shrink: 0; }
.feedback.error { color: var(--color-error-text); background: var(--color-error-surface); border-radius: var(--radius-control); padding: var(--space-2) var(--space-3); }
</style>
