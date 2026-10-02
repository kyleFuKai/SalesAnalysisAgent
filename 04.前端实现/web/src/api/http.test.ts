import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { ApiError, fetchApi } from './http'

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.unstubAllGlobals())

describe('统一请求错误处理', () => {
  it('自动携带 token 和 JSON 请求头', async () => {
    useAuthStore().setSession({ token: 'test-token', username: '张伟', role: 'SALES_REP' })
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    await fetchApi('/agent/chat', { method: 'POST', body: '{}' })
    const headers = fetch.mock.calls[0][1].headers as Headers
    expect(headers.get('Authorization')).toBe('test-token')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('识别登录 JSON 错误与业务纯文本错误', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(new Response('{"error":"账号或密码错误"}', { status: 400 }))
      .mockResolvedValueOnce(new Response('当前身份无权查询', { status: 403 }))
    vi.stubGlobal('fetch', fetch)
    await expect(fetchApi('/auth/login')).rejects.toMatchObject({ status: 400, message: '账号或密码错误' })
    await expect(fetchApi('/agent/chat')).rejects.toMatchObject({ status: 403, message: '当前身份无权查询' })
  })

  it('401 清除内存登录态并跳转登录页', async () => {
    const auth = useAuthStore()
    auth.setSession({ token: 'test-token', username: '张伟', role: 'SALES_REP' })
    const chat = useChatStore()
    chat.newSession()
    const assign = vi.fn()
    vi.stubGlobal('window', { location: { pathname: '/chat', assign } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('未登录', { status: 401 })))
    await expect(fetchApi('/agent/chat')).rejects.toBeInstanceOf(ApiError)
    expect(auth.token).toBeNull()
    expect(chat.sessions).toHaveLength(0)
    expect(assign).toHaveBeenCalledWith('/login')
  })

  it('网络故障返回统一提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('connection refused')))
    await expect(fetchApi('/agent/chat')).rejects.toMatchObject({ status: 0, message: '网络连接失败，请检查服务是否启动' })
  })
})
