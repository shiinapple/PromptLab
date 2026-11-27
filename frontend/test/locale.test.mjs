import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import { getLocale, initLocale, isLocale, pickLocale, setLocale, t, urlPinsLocale } from '../src/i18n/locale.ts'

const storage = new Map()
beforeEach(() => {
  storage.clear()
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key),
  }
  globalThis.document = { documentElement: { lang: '' } }
  setLocale('zh-CN', { persist: false })
})
afterEach(() => {
  delete globalThis.localStorage
  delete globalThis.document
})

test('isLocale accepts only the two supported locales', () => {
  assert.equal(isLocale('zh-CN'), true)
  assert.equal(isLocale('en'), true)
  for (const value of ['EN', 'zh', 'fr', '', null, undefined, 1]) assert.equal(isLocale(value), false, String(value))
})

test('pickLocale priority: url > server > stored > default', () => {
  assert.equal(pickLocale({ url: 'en', server: 'zh-CN', stored: 'zh-CN' }), 'en')
  assert.equal(pickLocale({ url: 'bad', server: 'en', stored: 'zh-CN' }), 'en')
  assert.equal(pickLocale({ server: 'en', stored: 'zh-CN' }), 'en')
  assert.equal(pickLocale({ stored: 'en' }), 'en')
  assert.equal(pickLocale({ stored: 'xx' }), 'zh-CN')
  assert.equal(pickLocale({}), 'zh-CN')
})

test('t() follows the reactive locale and setLocale updates html lang', () => {
  assert.equal(t('中', 'EN'), '中')
  setLocale('en', { persist: false })
  assert.equal(getLocale(), 'en')
  assert.equal(t('中', 'EN'), 'EN')
  assert.equal(globalThis.document.documentElement.lang, 'en')
})

test('setLocale persists by default but not when persist is false', () => {
  setLocale('en', { persist: false })
  assert.equal(storage.get('promptlab.locale'), undefined)
  setLocale('en')
  assert.equal(storage.get('promptlab.locale'), 'en')
})

test('initLocale uses url without writing it back to storage', () => {
  storage.set('promptlab.locale', 'zh-CN')
  initLocale('?locale=en')
  assert.equal(getLocale(), 'en')
  assert.equal(storage.get('promptlab.locale'), 'zh-CN')
})

test('initLocale falls back to stored value and ignores invalid url values', () => {
  storage.set('promptlab.locale', 'en')
  initLocale('?locale=fr')
  assert.equal(getLocale(), 'en')
})

test('urlPinsLocale is true only for a valid locale parameter', () => {
  assert.equal(urlPinsLocale('?locale=en'), true)
  assert.equal(urlPinsLocale('?locale='), false)
  assert.equal(urlPinsLocale('?problem=002'), false)
})
