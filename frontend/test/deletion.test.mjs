import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canWithdraw } from '../src/practice/deletion.ts'

const requested = '2026-11-28T00:00:00Z'
const effective = '2026-12-01T00:00:00Z'
const pending = { status: 'PENDING', requestedAt: requested, effectiveAt: effective }

test('no pending request cannot be withdrawn', () => {
  assert.equal(canWithdraw({ status: 'NONE', requestedAt: null, effectiveAt: null }, Date.parse(requested)), false)
})

test('pending request can be withdrawn before the effective time', () => {
  assert.equal(canWithdraw(pending, Date.parse('2026-11-30T23:59:59Z')), true)
})

test('pending request cannot be withdrawn at or after the effective time', () => {
  assert.equal(canWithdraw(pending, Date.parse(effective)), false)
  assert.equal(canWithdraw(pending, Date.parse('2026-12-02T00:00:00Z')), false)
})
