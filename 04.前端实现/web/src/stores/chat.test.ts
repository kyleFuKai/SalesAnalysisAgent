import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from './chat'

beforeEach(() => setActivePinia(createPinia()))

describe('本地会话管理', () => {
  it('新建、命名、切换会话时消息互不混淆', () => {
    const chat = useChatStore()
    const first = chat.newSession()
    chat.addMessage({ id: 'm1', role: 'user', content: '华东区本月销售额是多少？', status: 'success', createdAt: '' })
    const second = chat.newSession()
    chat.addMessage({ id: 'm2', role: 'user', content: '本月 Top 5 产品', status: 'success', createdAt: '' })
    expect(chat.messages.map((item) => item.id)).toEqual(['m2'])
    chat.switchSession(first)
    expect(chat.messages.map((item) => item.id)).toEqual(['m1'])
    expect(chat.sessions.find((item) => item.id === first)?.title).toBe('华东区本月销售额是多少？')
    chat.removeSession(first)
    expect(chat.activeSessionId).toBe(second)
  })

  it('退出登录时清除全部本地消息', () => {
    const chat = useChatStore()
    chat.newSession()
    chat.addMessage({ id: 'm', role: 'assistant', content: '秘密数据', status: 'success', createdAt: '' })
    chat.clear()
    expect(chat.sessions).toEqual([])
    expect(chat.messages).toEqual([])
    expect(chat.activeSessionId).toBeNull()
  })
})
