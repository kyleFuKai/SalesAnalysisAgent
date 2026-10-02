import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { LoginResponse, UserRole } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  // Token 不写 localStorage / sessionStorage；刷新页面后需重新登录。
  const token = ref<string | null>(null)
  const username = ref<string | null>(null)
  const role = ref<UserRole | null>(null)
  const isAuthenticated = computed(() => Boolean(token.value))

  function setSession(session: LoginResponse) {
    token.value = session.token
    username.value = session.username
    role.value = session.role
  }

  function clearSession() {
    token.value = null
    username.value = null
    role.value = null
  }

  return { token, username, role, isAuthenticated, setSession, clearSession }
})
