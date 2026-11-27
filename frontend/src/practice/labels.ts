import type { Check, Grading, Run } from './api'
import type { DirectStage } from './useDirectRun'
import { t } from '../model/copy'

export function previewError(code: string, requiredVariables: string[]): string {
  return code === 'EMPTY_SLOT' ? '请填写 Prompt。'
    : code === 'MISSING_VARIABLE' ? `请保留 ${requiredVariables.map(name => `{{${name}}}`).join('、')}，以便带入用例。`
      : 'Prompt 含有未定义的变量。'
}

const titles = {
  PENDING: t('等待导入', 'Awaiting import'), COMPLETED: t('已完成', 'Completed'), FAILED: t('执行失败', 'Request failed'),
  CANCELED: t('已取消', 'Canceled'), EXPIRED: t('已过期', 'Expired'), PASSED: t('通过', 'Passed'),
  FORMAT_ERROR: t('格式错误', 'Format error'), WRONG_ANSWER: t('答案错误', 'Wrong answer'),
}

export function runTitle(run: Run, stage: DirectStage | undefined, unsaved: boolean, grading?: Grading): string {
  if (run.verdict === 'PASSED' && grading === 'STRUCTURE_ONLY') {
    return t('JSON 格式通过；人设表现未自动评价', 'JSON format passed. Character portrayal was not graded.')
  }
  if (run.mode === 'DIRECT' && run.status === 'PENDING') {
    if (stage === 'request') return t('正在请求模型', 'Requesting model')
    if (stage === 'saving') return t('正在保存结果', 'Saving result')
    if (unsaved) return t('结果待保存', 'Result pending save')
    return t('直连未结束', 'Direct run unfinished')
  }
  return titles[run.verdict ?? run.status]
}

const checkLabels: Record<string, string> = {
  json: 'JSON 语法', schema: '输出结构', 'case-count': '用例数量',
  'unique-lengths': '长度不重复', 'boundary-mapping': '边界判断',
}

export function checkLabel(check: Check): string {
  if (check.id === 'schema' && check.path) return check.path
  return checkLabels[check.id] ?? check.id
}

export function describeFailure(check: Check): string {
  switch (check.code) {
    case 'INVALID_JSON': {
      const location = check.actual as { line?: number; column?: number }
      return location.line && location.line > 0
        ? `第 ${location.line} 行、第 ${location.column} 列附近不是合法 JSON。` : '正文须为一个完整 JSON 对象。'
    }
    case 'TYPE': return `${check.path || '/'} 需要 ${check.expected}，实际为 ${check.actual}。`
    case 'REQUIRED': return `缺少字段 ${check.path}。`
    case 'BLANK': return `${check.path} 不能为空白。`
    case 'EXTRA_FIELD': return `${check.path || '/'} 含有额外字段。`
    case 'ARRAY_SIZE': return `需要 ${check.expected} 项，实际 ${check.actual} 项。`
    case 'DUPLICATE_KEY': return '同一个长度只能出现一次。'
    case 'BOUNDARY_MAPPING': {
      const expected = check.expected as { boundaries: Record<string, boolean> }
      const actual = check.actual as { boundaries: Record<string, boolean | null>; unexpectedCount: number }
      return [...Object.entries(expected.boundaries).map(([length, value]) => {
        const received = actual.boundaries[length]
        return received === null ? `缺少长度 ${length}` : `长度 ${length} 应为 ${value}，实际为 ${received}`
      }), ...(actual.unexpectedCount ? [`另有 ${actual.unexpectedCount} 个题目未要求的长度`] : [])].join('。') + '。'
    }
    default: return check.path
  }
}
