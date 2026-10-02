import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type MessageStatus = 'pending' | 'success' | 'failed'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: MessageStatus
  createdAt: string
  durationMs?: number
}

export interface LocalSession {
  id: string
  title: string
  updatedAt: string
  messages: ChatMessage[]
}

export const useChatStore = defineStore('chat', () => {
  // 只在当前页面内存维护会话；不把销售数据写进浏览器持久化存储。
  const sessions = ref<LocalSession[]>([])
  const activeSessionId = ref<string | null>(null)
  const activeSession = computed(() => sessions.value.find((item) => item.id === activeSessionId.value))
  const messages = computed(() => activeSession.value?.messages ?? [])

  function newSession(): string {
    const id = crypto.randomUUID()
    sessions.value.unshift({ id, title: '新对话', updatedAt: new Date().toISOString(), messages: [] })
    activeSessionId.value = id
    return id
  }

  function ensureSessionId(): string {
    return activeSessionId.value ?? newSession()
  }

  function switchSession(id: string) {
    if (sessions.value.some((item) => item.id === id)) activeSessionId.value = id
  }

  function removeSession(id: string) {
    sessions.value = sessions.value.filter((item) => item.id !== id)
    if (activeSessionId.value === id) activeSessionId.value = sessions.value[0]?.id ?? null
  }

  function addMessage(message: ChatMessage) {
    ensureSessionId()
    const session = activeSession.value
    if (!session) return
    session.messages.push(message)
    if (message.role === 'user' && session.title === '新对话') {
      session.title = message.content.slice(0, 24) || '新对话'
    }
    session.updatedAt = new Date().toISOString()
  }

  function findMessage(id: string) {
    return activeSession.value?.messages.find((message) => message.id === id)
  }

  function markMessage(id: string, status: MessageStatus) {
    const item = findMessage(id)
    if (item) item.status = status
  }

  function appendToMessage(id: string, fragment: string) {
    const item = findMessage(id)
    if (item) item.content += fragment
  }

  function setMessageContent(id: string, content: string) {
    const item = findMessage(id)
    if (item) item.content = content
  }

  function clear() {
    activeSessionId.value = null
    sessions.value = []
  }

  return { sessions, activeSessionId, messages, newSession, ensureSessionId, switchSession,
    removeSession, addMessage, markMessage, appendToMessage, setMessageContent, clear }
})
