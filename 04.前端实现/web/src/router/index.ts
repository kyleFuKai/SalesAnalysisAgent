import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import ChatView from '../views/ChatView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import AdminAccountsView from '../views/AdminAccountsView.vue'
import ChangePasswordView from '../views/ChangePasswordView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/login', component: LoginView },
    { path: '/chat', component: ChatView, meta: { requiresAuth: true } },
    { path: '/admin', component: AdminAccountsView, meta: { requiresAuth: true } },
    { path: '/change-password', component: ChangePasswordView, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', component: NotFoundView },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/admin' && auth.role !== 'SYS_ADMIN') return '/chat'
  if (to.path === '/chat' && auth.role === 'SYS_ADMIN') return '/admin'
  if (to.path === '/login' && auth.isAuthenticated) return auth.role === 'SYS_ADMIN' ? '/admin' : '/chat'
})
