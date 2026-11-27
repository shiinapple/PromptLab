import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { apiErrorMessage, apiErrorText } from '../src/i18n/errors.ts'
import { setLocale, t } from '../src/i18n/locale.ts'
import { describeFailure, checkLabel, statusTitle } from '../src/practice/labels.ts'

const api = await readFile(new URL('../../docs/design/API.md', import.meta.url), 'utf8')
// 只取 4xx/5xx 错误表中的业务码；run 状态名（如 EXPIRED）出现在说明里，不属于错误码。
const RUN_STATES = new Set(['EXPIRED', 'PENDING', 'COMPLETED', 'FAILED', 'CANCELED', 'PATCH'])
const documentedCodes = [...new Set(api.split('\n')
  .filter(line => /^\| (4\d\d|5\d\d) \|/.test(line))
  .flatMap(line => line.match(/\b[A-Z][A-Z0-9_]{2,}\b/g) ?? [])
  .filter(code => !RUN_STATES.has(code)))]

test('every business code in the API.md error table has zh-CN and en text', () => {
  assert.ok(documentedCodes.length >= 20, `parsed only ${documentedCodes.length} codes`)
  for (const code of documentedCodes) {
    const text = apiErrorText[code]
    assert.ok(text, `missing ${code}`)
    assert.ok(text['zh-CN'].trim(), `empty zh-CN for ${code}`)
    assert.ok(text.en.trim(), `empty en for ${code}`)
  }
})

test('non-JSON Nginx responses fall back by status in both languages', () => {
  for (const locale of ['zh-CN', 'en']) {
    setLocale(locale, { persist: false })
    for (const status of [413, 502, 503]) {
      const message = apiErrorMessage(undefined, status)
      assert.ok(message.length > 0)
      assert.ok(!message.includes(String(status)) || status === 502 || status === 503 || status === 413, message)
    }
    assert.match(apiErrorMessage(undefined, 418), locale === 'en' ? /HTTP 418/ : /HTTP 418/)
  }
  setLocale('zh-CN', { persist: false })
})

test('error text switches with the current locale', () => {
  setLocale('en', { persist: false })
  assert.equal(apiErrorMessage('INVALID_LOCALE', 400), apiErrorText.INVALID_LOCALE.en)
  setLocale('zh-CN', { persist: false })
  assert.equal(apiErrorMessage('INVALID_LOCALE', 400), apiErrorText.INVALID_LOCALE['zh-CN'])
})

test('rule and status labels are bilingual and reactive', () => {
  const check = { id: 'json', path: '', status: 'FAIL', code: 'BLANK', expected: null, actual: null }
  setLocale('zh-CN', { persist: false })
  assert.equal(checkLabel(check), 'JSON 语法')
  assert.equal(statusTitle('WRONG_ANSWER'), '答案错误')
  assert.equal(describeFailure({ ...check, path: 'a' }), 'a 不能为空白。')
  setLocale('en', { persist: false })
  assert.equal(checkLabel(check), 'JSON syntax')
  assert.equal(statusTitle('WRONG_ANSWER'), 'Wrong answer')
  assert.equal(describeFailure({ ...check, path: 'a' }), 'a must not be blank.')
  setLocale('zh-CN', { persist: false })
})

test('t() returns the text for the active locale only', () => {
  setLocale('zh-CN', { persist: false })
  assert.equal(t('中', 'EN'), '中')
  setLocale('en', { persist: false })
  assert.equal(t('中', 'EN'), 'EN')
  setLocale('zh-CN', { persist: false })
})
