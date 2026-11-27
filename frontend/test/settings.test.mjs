import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkPrefs, DEFAULT_PREFS, effectivePrefs } from '../src/practice/settings.ts'

test('editor prefs accept the whitelist bounds', () => {
  assert.equal(checkPrefs(12, 2), null)
  assert.equal(checkPrefs(32, 8), null)
  assert.equal(checkPrefs(DEFAULT_PREFS.fontSize, DEFAULT_PREFS.tabSize), null)
})

test('editor prefs reject out-of-range and non-integer values', () => {
  assert.equal(checkPrefs(11, 4), 'fontSize')
  assert.equal(checkPrefs(33, 4), 'fontSize')
  assert.equal(checkPrefs(14.5, 4), 'fontSize')
  assert.equal(checkPrefs(Number.NaN, 4), 'fontSize')
  assert.equal(checkPrefs(14, 1), 'tabSize')
  assert.equal(checkPrefs(14, 9), 'tabSize')
  assert.equal(checkPrefs(14, 2.5), 'tabSize')
})

test('unsaved preference fields fall back to defaults', () => {
  assert.deepEqual(effectivePrefs({}), { fontSize: 14, tabSize: 4, wordWrap: true })
  assert.deepEqual(effectivePrefs({ fontSize: 18 }), { fontSize: 18, tabSize: 4, wordWrap: true })
  assert.deepEqual(effectivePrefs({ wordWrap: false }), { fontSize: 14, tabSize: 4, wordWrap: false })
})
