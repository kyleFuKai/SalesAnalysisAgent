<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createAccount, listAccounts, listRegions, resetAccountPassword, setAccountActive } from '../api/admin'
import type { Account, NewAccount, SalesRegion } from '../api/admin'
import { logout } from '../api/auth'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const accounts = ref<Account[]>([])
const regions = ref<SalesRegion[]>([])
const regionsLoading = ref(true)
const regionsFailed = ref(false)
const form = ref<NewAccount>({ name: '', role: 'SALES_REP', regionId: 0, email: null, password: '' })
const resetId = ref<number | null>(null)
const resetPassword = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')

async function refresh() {
  try { accounts.value = await listAccounts() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '加载账号失败' }
}
async function loadRegions() {
  regionsLoading.value = true
  regionsFailed.value = false
  try { regions.value = await listRegions() }
  catch {
    regions.value = []
    regionsFailed.value = true
    error.value = '加载大区失败，请确认后端已更新并重启'
  }
  finally { regionsLoading.value = false }
}
onMounted(() => { void refresh(); void loadRegions() })

function regionName(id: number): string {
  return regions.value.find(region => region.id === id)?.name ?? `大区 ID ${id}`
}

async function create() {
  if (busy.value) return
  error.value = ''
  notice.value = ''
  if (!regions.value.some(region => region.id === form.value.regionId)) {
    error.value = '请先选择有效大区'
    return
  }
  busy.value = true
  try {
    const account = await createAccount(form.value)
    accounts.value.push(account)
    form.value = { name: '', role: 'SALES_REP', regionId: 0, email: null, password: '' }
    notice.value = `已创建账号 ${account.id}；请通过安全渠道告知本人初始密码。`
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '创建失败' }
  finally { form.value.password = ''; busy.value = false }
}

async function toggle(account: Account) {
  if (busy.value || !window.confirm(`确定${account.active ? '停用' : '启用'} ${account.name} 的账号？`)) return
  error.value = ''
  busy.value = true
  try {
    const updated = await setAccountActive(account.id, !account.active)
    accounts.value = accounts.value.map(item => item.id === account.id ? updated : item)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败' }
  finally { busy.value = false }
}

async function reset() {
  if (busy.value || resetId.value === null) return
  error.value = ''
  notice.value = ''
  busy.value = true
  try {
    await resetAccountPassword(resetId.value, resetPassword.value)
    notice.value = '密码已重置；原有会话已失效。请通过安全渠道告知本人新密码。'
    resetId.value = null
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重置失败' }
  finally { resetPassword.value = ''; busy.value = false }
}

async function signOut() {
  try { await logout() } catch { /* 网络错误也清除本地状态 */ }
  auth.clearSession()
  await router.replace('/login')
}
</script>

<template>
  <main class="admin-page">
    <header><h1>账号管理</h1><div><span>{{ auth.username }}</span><button @click="router.push('/change-password')">修改密码</button><button @click="signOut">退出</button></div></header>
    <p>系统管理员只能管理账号，不能查询销售数据；新账号的初始密码须通过安全渠道告知本人。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <section class="card">
      <h2>创建销售账号</h2>
      <form @submit.prevent="create">
        <label>姓名<input v-model.trim="form.name" required maxlength="50" /></label>
        <label>角色<select v-model="form.role"><option value="SALES_REP">销售员</option><option value="SALES_MANAGER">主管</option><option value="SALES_DIRECTOR">总监</option></select></label>
        <label>所属大区<select v-model.number="form.regionId" :disabled="regionsLoading || regions.length === 0" required>
          <option :value="0" disabled>请选择大区</option>
          <option v-for="region in regions" :key="region.id" :value="region.id">{{ region.name }}</option>
        </select></label>
        <label>邮箱（可选）<input v-model.trim="form.email" type="email" maxlength="100" /></label>
        <label>初始密码<input v-model="form.password" type="password" autocomplete="new-password" minlength="12" required /></label>
        <button :disabled="busy || regionsLoading || regions.length === 0 || form.regionId === 0" type="submit">创建账号</button>
      </form>
      <p v-if="regionsFailed">大区列表请求失败。<button type="button" @click="loadRegions">重新加载</button></p>
      <p v-else-if="!regionsLoading && regions.length === 0">数据库暂无大区，无法创建账号。</p>
    </section>
    <section class="card">
      <h2>销售账号</h2>
      <p v-if="accounts.length === 0">暂无账号</p>
      <div v-for="account in accounts" :key="account.id" class="account-row">
        <div><strong>{{ account.name }}</strong> · ID {{ account.id }} · {{ account.role }} · {{ regionName(account.regionId) }} · {{ account.active ? '启用' : '停用' }}</div>
        <div><button :disabled="busy" @click="toggle(account)">{{ account.active ? '停用' : '启用' }}</button><button :disabled="busy" @click="resetId = account.id">重置密码</button></div>
      </div>
      <form v-if="resetId !== null" class="reset-form" @submit.prevent="reset">
        <label>为账号 {{ resetId }} 设置新密码<input v-model="resetPassword" type="password" autocomplete="new-password" minlength="12" required /></label>
        <button :disabled="busy" type="submit">确认重置</button><button type="button" @click="resetId = null; resetPassword = ''">取消</button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.admin-page { max-width: 1100px; margin: auto; padding: 2rem; }
header, header div, .account-row { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.card { background: white; border: 1px solid #dbe3e9; border-radius: 16px; padding: 1.5rem; margin-top: 1.5rem; }
form { display: flex; flex-wrap: wrap; align-items: end; gap: 1rem; }
label { display: grid; gap: .35rem; }
input, select { padding: .65rem; border: 1px solid #b9cbd7; border-radius: 7px; }
button { padding: .65rem .9rem; border: 1px solid #a8bcc8; border-radius: 7px; background: white; cursor: pointer; }
button[type=submit] { color: white; background: #185c7e; }
.account-row { padding: 1rem 0; border-bottom: 1px solid #e7edf1; }
.account-row div:last-child { display: flex; gap: .5rem; }
.reset-form { margin-top: 1rem; }
.error { color: #b42318; }.notice { color: #16704a; }
@media (max-width: 650px) { .account-row, header { align-items: start; flex-direction: column; } }
</style>
