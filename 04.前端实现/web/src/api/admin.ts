import { apiRequest } from './http'
import type { UserRole } from './auth'

export interface Account {
  id: number
  name: string
  role: Exclude<UserRole, 'SYS_ADMIN'>
  regionId: number
  email: string | null
  active: boolean
}

export interface SalesRegion {
  id: number
  name: string
}

export interface NewAccount {
  name: string
  role: Account['role']
  regionId: number
  email: string | null
  password: string
}

export const listAccounts = () => apiRequest<Account[]>('/admin/accounts')
export const listRegions = () => apiRequest<SalesRegion[]>('/admin/accounts/regions')
export const createAccount = (account: NewAccount) => apiRequest<Account>('/admin/accounts', {
  method: 'POST', body: JSON.stringify(account),
})
export const setAccountActive = (id: number, active: boolean) => apiRequest<Account>(
  `/admin/accounts/${id}/${active ? 'enable' : 'disable'}`, { method: 'POST' },
)
export const resetAccountPassword = (id: number, password: string) => apiRequest<{ message: string }>(
  `/admin/accounts/${id}/reset-password`, { method: 'POST', body: JSON.stringify({ password }) },
)
