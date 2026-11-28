import type { DeletionStatus } from './account'

/** 反悔期内且服务端尚未到期时可以撤回；到期后服务端会删除数据，前端不再提供撤回。 */
export function canWithdraw(status: DeletionStatus, now: number): boolean {
  if (status.status !== 'PENDING' || !status.effectiveAt) return false
  return now < Date.parse(status.effectiveAt)
}
