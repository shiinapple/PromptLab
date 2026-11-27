import assert from 'node:assert/strict'
import { test } from 'node:test'
import { dismissGuide, guideDismissed, guideKey } from '../src/guide/guide.ts'
import { failureHints } from '../src/model/hints.ts'
import { ModelError } from '../src/model/direct.ts'

function memoryStore() {
  const data = new Map()
  return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, String(value)) }
}

test('guide dismissal is stored per account, and local mode has its own key', () => {
  const store = memoryStore()
  assert.equal(guideKey(), 'promptlab.guide.local')
  assert.equal(guideKey('u1'), 'promptlab.guide.u1')
  assert.equal(guideDismissed('u1', store), false)
  dismissGuide('u1', store)
  assert.equal(guideDismissed('u1', store), true)
  assert.equal(guideDismissed('u2', store), false)
  assert.equal(guideDismissed(undefined, store), false)
})

test('guide state survives when storage is unavailable', () => {
  assert.equal(guideDismissed('u1', undefined), false)
  assert.doesNotThrow(() => dismissGuide('u1', undefined))
})

test('failure hints match the failure category without claiming a cause', () => {
  assert.equal(failureHints(new ModelError('NETWORK')).length, 4)
  assert.ok(failureHints(new ModelError('HTTP', 401)).length >= 1)
  assert.ok(failureHints(new ModelError('HTTP', 404))[0].length > 0)
  assert.deepEqual(failureHints(new ModelError('PROTOCOL_ERROR')), [])
  assert.deepEqual(failureHints(new ModelError('CANCELED')), [])
})
