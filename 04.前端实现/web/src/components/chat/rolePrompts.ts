import type { UserRole } from '../../api/auth'

const prompts: Record<UserRole, readonly string[]> = {
  SALES_REP: [
    '我本月销售额是多少？',
    '近30天我成交了哪些订单？',
    '给我画一张近半年的个人销售趋势折线图',
  ],
  SALES_MANAGER: [
    '本月本大区销售额是多少？',
    '本月本大区 Top 5 销售员是谁？',
    '本大区最近有没有销售异常？',
  ],
  SALES_DIRECTOR: [
    '本月全公司销售额是多少？',
    '各大区本月销售额排名',
    '最近数据有没有什么异常需要关注？',
  ],
  SYS_ADMIN: [],
}

export function promptsForRole(role: UserRole | null): readonly string[] {
  return role ? prompts[role] : []
}
