import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { NARROW_QUERY } from '../src/app/useNarrow.ts'

const read = path => readFile(new URL(path, import.meta.url), 'utf8')

// 断点只有一个数值：CSS 变量不能用于 @media，因此在 tokens.css 登记，并在媒体查询中使用同一字面量。
test('the narrow breakpoint is 768px (48rem) in the query, the tokens registry and App styles', async () => {
  assert.equal(NARROW_QUERY, '(max-width: 48rem)')
  assert.match(await read('../src/styles/tokens.css'), /断点[^\n]*768px[^\n]*48rem/)
  assert.match(await read('../src/app/App.vue'), /@media \(max-width: 48rem\)/)
  assert.match(await read('../src/guide/OnboardingBanner.vue'), /@media \(max-width: 48rem\)/)
})
