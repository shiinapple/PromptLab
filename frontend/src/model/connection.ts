const loopback = new Set(['localhost', '127.0.0.1', '[::1]'])

export function normalizeBase(input: string, platformOrigin: string): string {
  const base = input.trim()
  if (!/^https?:\/\/[^/\\]/i.test(base) || /[?#\\\s]/u.test(base)
    || base.split('/')[2].includes('@')) throw new Error('INVALID_URL')
  const url = new URL(base)
  const platform = new URL(platformOrigin)
  const local = loopback.has(url.hostname)
  if ((url.protocol !== 'https:' && (!local || url.hostname === '[::1]')) || url.origin === platform.origin
    || (local && loopback.has(platform.hostname) && url.port === platform.port)) {
    throw new Error('INVALID_URL')
  }
  return url.origin + url.pathname.replace(/\/+$/, '')
}

export type ConnectionProfile = { name: string; base: string; model: string; stream: boolean }

const namespace = 'prompt-practice:model:'
const sessionKey = namespace + 'key'

type BrowserStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export function browserStorage(name: 'localStorage' | 'sessionStorage'): BrowserStorage | undefined {
  try {
    return window[name]
  } catch {
    return undefined
  }
}

function read(storage: BrowserStorage | undefined, key: string): Record<string, unknown> | undefined {
  try {
    const data = JSON.parse(storage?.getItem(key) ?? 'null')
    return data && typeof data === 'object' ? data : undefined
  } catch {
    return undefined
  }
}

function write(storage: BrowserStorage | undefined, key: string, value: object | undefined): boolean {
  if (!storage) return false
  try {
    if (value) storage.setItem(key, JSON.stringify(value))
    else storage.removeItem(key)
    return true
  } catch {
    return false
  }
}

export function validKey(key: string) {
  return /^[\x21-\x7e]{0,4096}$/.test(key)
}

export function validModel(model: string) {
  return Boolean(model.trim()) && new TextEncoder().encode(model).length <= 256 && !/[\x00-\x1f\x7f]/.test(model)
}

export class ConnectionStore {
  private owner: string | null = null
  private key = ''
  profile?: ConnectionProfile
  remember = false

  private readonly local: BrowserStorage | undefined
  private readonly session: BrowserStorage | undefined
  private readonly platformOrigin: string

  constructor(local: BrowserStorage | undefined, session: BrowserStorage | undefined, platformOrigin: string) {
    this.local = local
    this.session = session
    this.platformOrigin = platformOrigin
  }

  selectAccount(owner: string | null) {
    if (owner !== null && this.owner === owner) return
    if (this.owner !== null && this.owner !== owner) this.clearKey()
    this.owner = owner
    this.profile = undefined
    this.key = ''
    this.remember = false
    const saved = owner ? read(this.local, namespace + owner) : undefined
    if (saved && typeof saved.name === 'string' && typeof saved.base === 'string'
      && typeof saved.model === 'string' && typeof saved.stream === 'boolean') {
      try {
        const base = normalizeBase(saved.base, this.platformOrigin)
        this.profile = { name: saved.name, base, model: saved.model, stream: saved.stream }
      } catch {
        // Invalid or obsolete stored addresses require explicit reconfiguration.
      }
    }
    const session = read(this.session, sessionKey)
    if (owner && this.profile && session?.owner === owner && session.base === this.profile.base
      && typeof session.key === 'string' && session.key && validKey(session.key)) {
      this.key = session.key
      this.remember = true
    } else {
      this.clearKey()
    }
  }

  save(profile: ConnectionProfile, newKey: string, remember: boolean): boolean {
    const { name, model, stream } = profile
    const base = normalizeBase(profile.base, this.platformOrigin)
    const previous = this.getKey(this.profile?.base)
    if (!validKey(newKey) || (model && !validModel(model))
      || [newKey, previous].some(secret => secret && [name, base, model].some(value => value.includes(secret)))) {
      throw new Error('INVALID_PROFILE')
    }
    if (this.profile && base !== this.profile.base) {
      this.clearKey()
      newKey = ''
      remember = false
    }
    if (newKey) this.key = newKey
    this.profile = { name, base, model, stream }
    const profileSaved = this.owner !== null && write(this.local, namespace + this.owner, this.profile)
    this.remember = Boolean(remember && this.key && profileSaved
      && write(this.session, sessionKey, { owner: this.owner, base, key: this.key }))
    if (!this.remember) write(this.session, sessionKey, undefined)
    return profileSaved && (!remember || !this.key || this.remember)
  }

  getKey(base?: string): string {
    return this.profile && base === this.profile.base ? this.key : ''
  }

  clearKey() {
    this.key = ''
    this.remember = false
    write(this.session, sessionKey, undefined)
  }

  remove() {
    this.clearKey()
    if (this.owner !== null) write(this.local, namespace + this.owner, undefined)
    this.profile = undefined
  }
}
