import { apiRequest } from './http'

export type UserRole = 'SALES_REP' | 'SALES_MANAGER' | 'SALES_DIRECTOR' | 'SYS_ADMIN'

export interface LoginResponse {
  token: string
  username: string
  role: UserRole
}

export function login(repId: number, password: string) {
  return apiRequest<LoginResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ repId, password }),
  })
}

export function logout() {
  return apiRequest<{ message: string }>('/auth/logout', { method: 'POST' })
}

export function changePassword(oldPassword: string, newPassword: string) {
  return apiRequest<{ message: string }>('/auth/password', {
    method: 'POST',
    body: JSON.stringify({ oldPassword, newPassword }),
  })
}
