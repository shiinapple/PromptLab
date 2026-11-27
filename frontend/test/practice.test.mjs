import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { assembleMessages } from '../src/practice/messages.ts'
import { checkOutput, importResponse, makeRequest, MAX_FILE_BYTES, MAX_OUTPUT_BYTES } from '../src/practice/d0.ts'

const golden = JSON.parse(await readFile(new URL('../../content/fixtures/messages.json', import.meta.url)))
for (const row of golden.cases) {
  test(`message golden ${row.id}`, () => {
    const assemble = () => assembleMessages(golden.plan, row.slots, golden.variables)
    if (row.error) assert.throws(assemble, { message: row.error })
    else assert.deepEqual(assemble(), row.expected)
  })
}

const file = data => new Blob([JSON.stringify(data)])
const reply = (content, reason = 'stop', extra = {}) => ({
  choices: [{ finish_reason: reason, message: { role: 'assistant', content, ...extra } }],
})
const original = ' \r\n{"cases":[]}\t \n'

test('exports frozen request without transport credentials', () => {
  const messages = [{ role: 'user', content: '冻结的消息' }]
  assert.deepEqual(makeRequest(messages), { model: 'YOUR_MODEL', messages, stream: false })
})
test('file and whole paste preserve exactly the same original text', async () => {
  assert.equal(await importResponse(file(reply(original))), original)
  assert.equal(checkOutput(original), original)
})
test('checks file size before reading', async () => {
  const fake = { size: MAX_FILE_BYTES + 1, arrayBuffer: () => assert.fail('read an oversized file') }
  await assert.rejects(importResponse(fake), /1 MiB/)
})
test('checks actual bytes and strict UTF-8', async () => {
  const fake = { size: 0, arrayBuffer: () => new ArrayBuffer(MAX_FILE_BYTES + 1) }
  await assert.rejects(importResponse(fake), /1 MiB/)
  await assert.rejects(importResponse(new Blob([new Uint8Array([0xc3, 0x28])])), /UTF-8/)
})
test('checks extracted UTF-8 byte size and Unicode validity', async () => {
  const long = '界'.repeat(Math.floor(MAX_OUTPUT_BYTES / 3) + 1)
  await assert.rejects(importResponse(file(reply(long))), /256 KiB/)
  assert.throws(() => checkOutput(long), /256 KiB/)
  assert.throws(() => checkOutput('\ud800'), /Unicode/)
  assert.equal(checkOutput('😀'), '😀')
})
for (const [label, payload] of [
  ['error envelope', { error: { message: 'upstream' } }],
  ['loose answer', { cases: [] }],
  ['no choices', { choices: [] }],
  ['truncation', reply(original, 'length')],
  ['filter', reply(original, 'content_filter')],
  ['blank', reply('   ')],
  ['null', reply(null)],
  ['tool calls', reply(original, 'stop', { tool_calls: [{}] })],
  ['function call', reply(original, 'stop', { function_call: {} })],
  ['refusal', reply(original, 'stop', { refusal: 'refused' })],
  ['wrong role', reply(original, 'stop', { role: 'user' })],
]) {
  test(`rejects ${label}`, async () => assert.rejects(importResponse(file(payload))))
}
