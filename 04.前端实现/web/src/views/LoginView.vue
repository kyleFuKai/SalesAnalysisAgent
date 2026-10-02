<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../api/auth'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const repIdInput = ref('')
const password = ref('')
const submitting = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  const repId = Number(repIdInput.value)
  if (!Number.isSafeInteger(repId) || repId <= 0) {
    error.value = '请输入有效的账号 ID'
    return
  }
  if (!password.value.trim()) {
    error.value = '请输入密码'
    return
  }

  submitting.value = true
  try {
    auth.setSession(await login(repId, password.value))
    password.value = ''
    const redirect = route.query.redirect
    const home = auth.role === 'SYS_ADMIN' ? '/admin' : '/chat'
    await router.replace(typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
      ? redirect : home)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-intro">
      <span class="eyebrow">SALES INSIGHT AGENT</span>
      <h1>让销售数据<br />更容易开口问。</h1>
      <p>用自然语言查询订单、业绩和趋势。系统会根据你的身份限定可查看的数据范围。</p>
      <div class="intro-note">智能问数 · 权限隔离 · 结果可追溯</div>
    </div>

    <section class="login-card" aria-labelledby="login-title">
      <div class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></div>
      <h2 id="login-title">欢迎回来</h2>
      <p class="muted">使用账号 ID 登录工作台</p>
      <form @submit.prevent="submit">
        <label for="rep-id">账号 ID</label>
        <input id="rep-id" v-model="repIdInput" inputmode="numeric" autocomplete="username"
          placeholder="请输入账号 ID" :disabled="submitting" required />
        <label for="password">密码</label>
        <input id="password" v-model="password" type="password" autocomplete="current-password"
          placeholder="请输入密码" :disabled="submitting" required />
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button type="submit" :disabled="submitting">{{ submitting ? '正在登录…' : '登录工作台' }}</button>
      </form>
      <p class="footnote">登录后仅能访问当前账号授权范围内的数据。</p>
    </section>
  </main>
</template>
