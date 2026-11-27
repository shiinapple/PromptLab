export type Message = { role: string; content: string }
type Part = { type: 'fixedText'; text: string } | { type: 'caseVariable' | 'slot'; name: string }
export type MessagePlan = {
  slots: Record<string, { requiredVariables: string[] }>
  messages: { role: string; parts: Part[] }[]
}

export function assembleMessages(plan: MessagePlan, slots: Record<string, string>, variables: Record<string, string>): Message[] {
  const names = Object.keys(plan.slots)
  if (Object.keys(slots).length !== names.length || names.some(name => !Object.hasOwn(slots, name))) {
    throw new Error('SLOT_SET')
  }
  function variable(name: string): string {
    if (!Object.hasOwn(variables, name) || typeof variables[name] !== 'string') throw new Error('UNKNOWN_VARIABLE')
    return variables[name]
  }
  function render(name: string): string {
    const template = slots[name]
    if (typeof template !== 'string' || /^[ \t\r\n]*$/.test(template)) throw new Error('EMPTY_SLOT')
    const seen = new Set<string>()
    const rendered = template.replace(/\\\\|\\\{\{|\{\{([A-Za-z][A-Za-z0-9_]*)}}/g, (token, key?: string) => {
      if (key === undefined) return token.slice(1)
      seen.add(key)
      return variable(key)
    })
    if (plan.slots[name].requiredVariables.some(key => !seen.has(key))) throw new Error('MISSING_VARIABLE')
    return rendered
  }
  return plan.messages.map(({ role, parts }) => ({
    role,
    content: parts.map(part => {
      switch (part.type) {
        case 'fixedText': return part.text
        case 'caseVariable': return variable(part.name)
        case 'slot': return render(part.name)
      }
    }).join(''),
  }))
}
