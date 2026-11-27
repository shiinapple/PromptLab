type Store = Pick<Storage, 'getItem' | 'setItem'>

/** 引导关闭状态按账号隔离，未登录（本地练习）使用 local。 */
export const guideKey = (userId?: string) => `promptlab.guide.${userId ?? 'local'}`

function browserStore(): Store | undefined {
  try {
    return localStorage
  } catch {
    return undefined
  }
}

export function guideDismissed(userId?: string, store: Store | undefined = browserStore()): boolean {
  try {
    return store?.getItem(guideKey(userId)) === 'dismissed'
  } catch {
    return false
  }
}

export function dismissGuide(userId?: string, store: Store | undefined = browserStore()) {
  try {
    store?.setItem(guideKey(userId), 'dismissed')
  } catch {
    // 存储不可用时，本次页面内仍然关闭。
  }
}
