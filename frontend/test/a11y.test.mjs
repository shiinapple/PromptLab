import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { compileTemplate, parse } from 'vue/compiler-sfc'

// Render the SFC template with Vue's installed compiler and a small view model.
// Browser focus and accessible names are checked independently by the CDP suite.
async function renderer(name) {
  const filename = new URL(`../src/practice/${name}.vue`, import.meta.url)
  const { descriptor, errors: parseErrors } = parse(await readFile(filename, 'utf8'))
  assert.deepEqual(parseErrors, [])
  const { code, errors } = compileTemplate({
    source: descriptor.template.content, filename: filename.pathname, id: name, ssr: true, ssrCssVars: [],
  })
  assert.deepEqual(errors, [])
  const module = code.replaceAll(/from "(vue(?:\/server-renderer)?)"/g,
    (_, id) => `from "${import.meta.resolve(id)}"`)
  const { ssrRender } = await import(`data:text/javascript;base64,${Buffer.from(module).toString('base64')}`)
  return props => {
    let html = ''
    ssrRender({ t: (_zh, en) => en, ...props }, chunk => { html += chunk }, null, {})
    return html
  }
}

test('Prompt label and hint reference the editor; a validation alert is linked only when present', async () => {
  const render = await renderer('PromptPanel')
  const invalid = render({ preview: { error: 'Keep the required variable.' }, mode: 'D0', prompt: '' })
  const valid = render({ preview: { messages: [] }, mode: 'D0', prompt: '' })
  assert.match(invalid, /<label for="prompt">Prompt<\/label>/)
  assert.match(invalid, /<textarea id="prompt"[^>]*aria-describedby="prompt-hint prompt-error" aria-invalid="true"/)
  assert.match(invalid, /<p id="prompt-error"[^>]*role="alert">Keep the required variable\.<\/p>/)
  assert.match(valid, /<textarea id="prompt"[^>]*aria-describedby="prompt-hint" aria-invalid="false"/)
  assert.doesNotMatch(valid, /id="prompt-error"/)
})

test('D0 renders associated file and output labels and names the confirmation checkbox', async () => {
  const render = await renderer('ManualRunSection')
  const html = render({ raw: '{}', confirmed: false })
  assert.match(html, /<label[^>]*for="response-file">Import response JSON<\/label><input id="response-file" type="file"/)
  assert.match(html, /<label[^>]*for="raw">Original model output<\/label><textarea id="raw"/)
  assert.match(html, /<label class="confirm"><input id="confirm" type="checkbox">\s*I confirm[^<]+<\/label>/)
})

test('problem disclosure references the panel and renders its expanded state', async () => {
  const render = await renderer('ProblemPanel')
  const publicCase = { caseId: 'case-1', variables: {} }
  const view = { problem: { problemId: '002', version: 2, cases: [publicCase] }, publicCase, narrow: true }
  for (const expanded of [false, true]) {
    const html = render({ ...view, expanded })
    assert.match(html, new RegExp(`<button[^>]*aria-controls="problem-body" aria-expanded="${expanded}"`))
    assert.match(html, /id="problem-body"/)
    assert.equal(/id="problem-body"[^>]*display:none/.test(html), !expanded)
  }
})

test('elapsed direct request updates the polite status text between renders', async () => {
  const render = await renderer('DirectRunSection')
  const view = { run: { status: 'PENDING' }, stage: 'request', busy: true, hints: [] }
  for (const seconds of [0, 2]) {
    assert.match(render({ ...view, seconds }), new RegExp(`role="status" aria-live="polite">${seconds} s<`))
  }
})
