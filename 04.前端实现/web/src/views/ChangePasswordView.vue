<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'

const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }
  if (newPassword.value.length < 12) {
    error.value = '新密码至少需要 12 位'
    return
  }
  busy.value = true
  try {
    await changePassword(oldPassword.value, newPassword.value)
    chat.clear()
    auth.clearSession()
    await router.replace('/login')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '修改失败，请稍后重试'
  } finally {
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    busy.value = false
  }
}
</script>

<template>
  <main class="account-page">
    <section class="account-card">
      <h1>修改密码</h1>
      <p>修改成功后，当前及其他设备的登录会话都会失效，请用新密码重新登录。</p>
      <form @submit.prevent="submit">
        <label for="old-password">原密码</label>
        <input id="old-password" v-model="oldPassword" type="password" autocomplete="current-password" required />
        <label for="new-password">新密码</label>
        <input id="new-password" v-model="newPassword" type="password" autocomplete="new-password" required />
        <label for="confirm-password">确认新密码</label>
        <input id="confirm-password" v-model="confirmPassword" type="password" autocomplete="new-password" required />
        <p v-if="error" role="alert" class="form-error">{{ error }}</p>
        <button type="submit" :disabled="busy">{{ busy ? '提交中…' : '确认修改' }}</button>
      </form>
      <button type="button" class="plain-link" @click="router.back()">返回</button>
    </section>
  </main>
</template>

<style scoped>
.account-page { min-height: 100vh; display: grid; place-items: center; padding: 2rem; background: #f5f7fb; }
.account-card { width: min(100%, 460px); background: white; padding: 2rem; border-radius: 20px; box-shadow: 0 12px 36px #15344c12; }
.account-card form { display: grid; gap: .7rem; margin-top: 1.5rem; }
.account-card input { width: 100%; padding: .7rem; border: 1px solid #cbd5e1; border-radius: 8px; }
.account-card button { padding: .7rem 1rem; border: 0; border-radius: 8px; cursor: pointer; }
.account-card form button { background: #185c7e; color: white; }
.plain-link { margin-top: 1rem; background: transparent; color: #185c7e; }
.form-error { color: #b42318; }
</style>
