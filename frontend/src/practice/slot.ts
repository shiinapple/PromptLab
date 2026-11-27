import type { MessagePlan } from './messages.ts'

/** 题目里供用户编辑的槽。题包契约规定每题只有一个。 */
export function editableSlot(plan: MessagePlan) {
  const [name] = Object.keys(plan.slots)
  const message = plan.messages.find(({ parts }) => parts.some(part => part.type === 'slot' && part.name === name))
  return { name, role: message?.role, requiredVariables: plan.slots[name].requiredVariables }
}
