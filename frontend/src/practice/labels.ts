import type { Check, Grading, Run } from './api'
import type { DirectStage } from './useDirectRun'
import { t } from '../i18n/locale.ts'

export function previewError(code: string, requiredVariables: string[]): string {
  const names = requiredVariables.map(name => `{{${name}}}`).join(t('、', ', '))
  return code === 'EMPTY_SLOT' ? t('请填写 Prompt。', 'Enter a prompt.')
    : code === 'MISSING_VARIABLE' ? t(`请保留 ${names}，以便带入用例。`, `Keep ${names} so the case can be inserted.`)
      : t('Prompt 含有未定义的变量。', 'The prompt uses an undefined variable.')
}

/** 运行结论与状态的文字标签；图标与文字一起展示，不单靠颜色区分。 */
export function statusTitle(status: Run['status'] | Run['verdict']): string {
  switch (status) {
    case 'PENDING': return t('等待导入', 'Awaiting import')
    case 'COMPLETED': return t('已完成', 'Completed')
    case 'FAILED': return t('执行失败', 'Request failed')
    case 'CANCELED': return t('已取消', 'Canceled')
    case 'EXPIRED': return t('已过期', 'Expired')
    case 'PASSED': return t('通过', 'Passed')
    case 'FORMAT_ERROR': return t('格式错误', 'Format error')
    case 'WRONG_ANSWER': return t('答案错误', 'Wrong answer')
    default: return status ?? ''
  }
}

/** 通过为 ✓，失败与错误为 ✕，其余不加符号。 */
export function statusSymbol(verdict: Run['verdict'] | undefined, failed: boolean): string {
  if (verdict === 'PASSED') return '✓'
  if (failed) return '✕'
  return ''
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
  return statusTitle(run.verdict ?? run.status)
}

export function checkLabel(check: Check): string {
  if (check.id === 'schema' && check.path) return check.path
  switch (check.id) {
    case 'json': return t('JSON 语法', 'JSON syntax')
    case 'schema': return t('输出结构', 'Output structure')
    case 'case-count': return t('用例数量', 'Case count')
    case 'unique-lengths': return t('长度不重复', 'Unique lengths')
    case 'boundary-mapping': return t('边界判断', 'Boundary mapping')
    default: return check.id
  }
}

export function describeFailure(check: Check): string {
  switch (check.code) {
    case 'INVALID_JSON': {
      const location = check.actual as { line?: number; column?: number }
      return location.line && location.line > 0
        ? t(`第 ${location.line} 行、第 ${location.column} 列附近不是合法 JSON。`,
          `Not valid JSON near line ${location.line}, column ${location.column}.`)
        : t('正文须为一个完整 JSON 对象。', 'The output must be one complete JSON object.')
    }
    case 'TYPE': return t(`${check.path || '/'} 需要 ${check.expected}，实际为 ${check.actual}。`,
      `${check.path || '/'} must be ${check.expected}, but it is ${check.actual}.`)
    case 'REQUIRED': return t(`缺少字段 ${check.path}。`, `Missing field ${check.path}.`)
    case 'BLANK': return t(`${check.path} 不能为空白。`, `${check.path} must not be blank.`)
    case 'EXTRA_FIELD': return t(`${check.path || '/'} 含有额外字段。`, `${check.path || '/'} has an extra field.`)
    case 'ARRAY_SIZE': return t(`需要 ${check.expected} 项，实际 ${check.actual} 项。`,
      `Expected ${check.expected} items, found ${check.actual}.`)
    case 'DUPLICATE_KEY': return t('同一个长度只能出现一次。', 'Each length may appear only once.')
    case 'BOUNDARY_MAPPING': {
      const expected = check.expected as { boundaries: Record<string, boolean> }
      const actual = check.actual as { boundaries: Record<string, boolean | null>; unexpectedCount: number }
      const parts = Object.entries(expected.boundaries).map(([length, value]) => {
        const received = actual.boundaries[length]
        return received === null
          ? t(`缺少长度 ${length}`, `Missing length ${length}`)
          : t(`长度 ${length} 应为 ${value}，实际为 ${received}`, `Length ${length} should be ${value}, found ${received}`)
      })
      if (actual.unexpectedCount) parts.push(t(`另有 ${actual.unexpectedCount} 个题目未要求的长度`,
        `${actual.unexpectedCount} more lengths were not requested`))
      return parts.join(t('。', '. ')) + t('。', '.')
    }
    default: return check.path
  }
}
