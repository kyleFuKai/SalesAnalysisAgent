import { describe, expect, it } from 'vitest'
import { promptsForRole } from './rolePrompts'

describe('角色推荐问题', () => {
  it('销售员只显示本人视角', () => {
    const prompts = promptsForRole('SALES_REP')
    expect(prompts.length).toBeGreaterThan(0)
    expect(prompts.every((item) => item.includes('我') || item.includes('个人'))).toBe(true)
    expect(prompts.join('')).not.toContain('大区')
  })

  it('主管和总监分别展示本区与全公司视角', () => {
    expect(promptsForRole('SALES_MANAGER').join('')).toContain('本大区')
    expect(promptsForRole('SALES_DIRECTOR').join('')).toContain('全公司')
    expect(promptsForRole(null)).toEqual([])
  })
})
