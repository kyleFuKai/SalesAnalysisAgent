<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { askSalesAgent, clearSalesSession } from '../api/agent'
import { streamSalesAgent, StreamInterruptedError } from '../api/agentStream'
import { logout } from '../api/auth'
import { ApiError } from '../api/http'
import ChatComposer from '../components/chat/ChatComposer.vue'
import ChatMessageCard from '../components/chat/ChatMessageCard.vue'
import PromptSuggestions from '../components/chat/PromptSuggestions.vue'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'

const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()
const composer = ref<InstanceType<typeof ChatComposer> | null>(null)
const messageEnd = ref<HTMLElement | null>(null)
const signingOut = ref(false)
const clearingSession = ref(false)
const sending = ref(false)
const error = ref('')
const failedQuestion = ref('')
const mode = ref<'stream' | 'sync'>('stream')
let activeAbort: AbortController | null = null
let scrollScheduled = false

onBeforeUnmount(() => activeAbort?.abort())

const roleLabel = {
  SALES_REP: '销售员',
  SALES_MANAGER: '销售主管',
  SALES_DIRECTOR: '销售总监',
  SYS_ADMIN: '系统管理员',
} as const

const scopeLabel = computed(() => {
  switch (auth.role) {
    case 'SALES_REP': return '仅查看本人数据'
    case 'SALES_MANAGER': return '仅查看所属大区数据'
    case 'SALES_DIRECTOR': return '可查看全公司数据'
    default: return '权限待确认'
  }
})

function newConversation() {
  if (sending.value || clearingSession.value) return
  chat.newSession()
  error.value = ''
  failedQuestion.value = ''
}

function selectSession(id: string) {
  if (sending.value || clearingSession.value) return
  chat.switchSession(id)
  error.value = ''
  failedQuestion.value = ''
  scheduleScroll()
}

async function removeConversation(id: string) {
  if (sending.value || clearingSession.value) return
  if (!window.confirm('删除这段对话？当前浏览器中的消息和后端会话记忆都将清除，无法恢复。')) return
  clearingSession.value = true
  error.value = ''
  try {
    await clearSalesSession(id)
    chat.removeSession(id)
    failedQuestion.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除会话失败，请稍后重试。'
  } finally {
    clearingSession.value = false
  }
}

async function scrollToLatest() {
  await nextTick()
  messageEnd.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })
}

function scheduleScroll() {
  if (scrollScheduled) return
  scrollScheduled = true
  requestAnimationFrame(() => {
    scrollScheduled = false
    void scrollToLatest()
  })
}

function stopReceiving() {
  activeAbort?.abort()
}

async function send(message: string) {
  if (sending.value || clearingSession.value) return
  const question = message.trim()
  if (!question || question.length > 2000) return

  error.value = ''
  failedQuestion.value = ''
  sending.value = true
  const userMessageId = crypto.randomUUID()
  const sessionId = chat.ensureSessionId()
  let assistantMessageId: string | null = null
  chat.addMessage({
    id: userMessageId,
    role: 'user',
    content: question,
    status: 'pending',
    createdAt: new Date().toISOString(),
  })
  scheduleScroll()

  try {
    if (mode.value === 'stream') {
      assistantMessageId = crypto.randomUUID()
      chat.addMessage({
        id: assistantMessageId,
        role: 'assistant',
        content: '',
        status: 'pending',
        createdAt: new Date().toISOString(),
      })
      const controller = new AbortController()
      activeAbort = controller
      const replyId = assistantMessageId
      await streamSalesAgent(sessionId, question, {
        signal: controller.signal,
        onToken: (token) => {
          chat.appendToMessage(replyId, token)
          scheduleScroll()
        },
      })
      const completedReply = chat.messages.find((item) => item.id === replyId)?.content
      if (!completedReply?.trim()) throw new StreamInterruptedError('服务未返回回答内容')
      chat.markMessage(userMessageId, 'success')
      chat.markMessage(replyId, 'success')
    } else {
      const response = await askSalesAgent(sessionId, question)
      if (response.sessionId !== sessionId) throw new Error('会话标识不匹配，请新建会话后重试')
      chat.markMessage(userMessageId, 'success')
      chat.addMessage({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: response.reply,
        status: 'success',
        createdAt: new Date().toISOString(),
        durationMs: response.durationMs,
      })
    }
  } catch (cause) {
    chat.markMessage(userMessageId, 'failed')
    if (assistantMessageId) {
      chat.markMessage(assistantMessageId, 'failed')
      const reply = chat.messages.find((item) => item.id === assistantMessageId)
      if (reply && !reply.content) chat.setMessageContent(assistantMessageId, '回答未完成。')
    }
    failedQuestion.value = question
    if (activeAbort?.signal.aborted) {
      error.value = '已停止接收回答。后端可能仍在处理；继续提问前建议新建对话。'
    } else if (cause instanceof ApiError) {
      if (cause.status === 429) error.value = '提问太频繁了，请稍后重试。'
      else if (cause.status === 403) error.value = cause.message || '当前身份无权查询该数据。'
      else if (cause.status === 401) error.value = '登录已过期，请重新登录。'
      else if (cause.status >= 500) error.value = '分析服务暂时不可用，请稍后重试。'
      else error.value = cause.message
    } else {
      error.value = cause instanceof Error ? cause.message : '请求失败，请稍后重试。'
    }
  } finally {
    activeAbort = null
    sending.value = false
    scheduleScroll()
  }
}

function fillFailedQuestion() {
  composer.value?.fill(failedQuestion.value)
  error.value = ''
  failedQuestion.value = ''
}

async function signOut() {
  if (signingOut.value || sending.value || clearingSession.value) return
  signingOut.value = true
  try {
    await logout()
  } catch {
    // 网络不可用时仍清除本地令牌，不能把失效的登录态留在页面。
  } finally {
    chat.clear()
    auth.clearSession()
    signingOut.value = false
    await router.replace('/login')
  }
}
</script>

<template>
  <main class="workspace-shell">
    <header class="workspace-header">
      <div class="header-brand"><span class="header-symbol" aria-hidden="true">▥</span>销售数据分析 Agent</div>
      <div class="header-actions">
        <span class="user-chip">{{ auth.username }} · {{ auth.role ? roleLabel[auth.role] : '' }}</span>
        <button class="text-button" :disabled="sending" @click="router.push('/change-password')">修改密码</button>
        <button class="text-button" :disabled="signingOut || sending || clearingSession" @click="signOut">退出登录</button>
      </div>
    </header>

    <div class="workspace-grid">
      <aside class="workspace-sidebar" aria-label="工作台导航">
        <div class="sidebar-heading">工作台</div>
        <div class="nav-current"><span aria-hidden="true">◉</span> 智能问答</div>
        <button class="new-conversation" :disabled="sending || clearingSession" @click="newConversation">＋ 新建对话</button>
        <div class="sidebar-section-title">当前浏览器会话</div>
        <p v-if="chat.sessions.length === 0" class="session-item">尚未开始对话</p>
        <div v-for="session in chat.sessions" :key="session.id" class="session-row"
          :class="{ active: session.id === chat.activeSessionId }">
          <button type="button" class="session-select" :disabled="sending || clearingSession"
            :aria-current="session.id === chat.activeSessionId ? 'page' : undefined"
            @click="selectSession(session.id)">{{ session.title }}</button>
          <button type="button" class="session-remove" :disabled="sending || clearingSession"
            :aria-label="`删除会话：${session.title}`" @click="removeConversation(session.id)">×</button>
        </div>
        <p class="sidebar-note">会话仅保存在当前页面内存，刷新或退出登录后不会恢复；不支持跨设备同步。</p>
      </aside>

      <section class="chat-main" aria-label="销售数据问答">
        <div class="chat-topline">
          <div>
            <span class="eyebrow">智能问答</span>
            <h1>用一句话，问清销售数据</h1>
          </div>
          <div class="answer-mode" role="group" aria-label="回答方式">
            <button type="button" :class="{ active: mode === 'stream' }" :aria-pressed="mode === 'stream'"
              :disabled="sending" @click="mode = 'stream'">流式</button>
            <button type="button" :class="{ active: mode === 'sync' }" :aria-pressed="mode === 'sync'"
              :disabled="sending" @click="mode = 'sync'">同步</button>
          </div>
        </div>

        <div class="chat-content" aria-live="polite">
          <div v-if="chat.messages.length === 0" class="chat-empty">
            <span class="empty-symbol" aria-hidden="true">✦</span>
            <h2>今天想了解什么销售数据？</h2>
            <p>试试下面的问题，或在输入框里直接提问。回答会遵循当前账号的数据权限。</p>
            <PromptSuggestions :role="auth.role" :disabled="sending" @select="send" />
          </div>
          <template v-else>
            <ChatMessageCard v-for="message in chat.messages" :key="message.id" :message="message" />
            <div ref="messageEnd"></div>
          </template>
        </div>

        <div class="chat-bottom">
          <div v-if="error" class="chat-error" role="alert">
            <span>{{ error }}</span>
            <button v-if="failedQuestion && !sending" type="button" @click="fillFailedQuestion">重新填入问题</button>
          </div>
          <div v-if="sending && mode === 'stream'" class="stream-status" role="status">
            <span>正在流式生成回答…</span>
            <button type="button" @click="stopReceiving">停止接收</button>
          </div>
          <ChatComposer ref="composer" :busy="sending" @send="send" />
          <p class="chat-disclaimer">只有收到完成信号才算回答成功。停止接收不会保证取消后端任务；图表可展开查看原始数据。</p>
        </div>
      </section>

      <aside class="workspace-context" aria-label="当前身份与建议">
        <div class="context-card">
          <span class="context-label">当前身份</span>
          <strong>{{ auth.username }}</strong>
          <p>{{ auth.role ? roleLabel[auth.role] : '角色未知' }}</p>
          <div class="scope-pill">{{ scopeLabel }}</div>
        </div>
        <div class="context-card">
          <span class="context-label">你可以这样问</span>
          <PromptSuggestions :role="auth.role" :disabled="sending" @select="send" />
        </div>
        <div class="context-tip">当前页面不展示预置业绩数字。每个回答都通过后端实时请求获取，缓存命中时仍由后端决定结果。</div>
      </aside>
    </div>
  </main>
</template>
