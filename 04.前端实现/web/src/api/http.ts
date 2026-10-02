import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

async function errorMessage(response: Response): Promise<string> {
  const body = await response.text()
  try {
    const data: unknown = JSON.parse(body)
    if (data && typeof data === 'object' && 'error' in data && typeof data.error === 'string') {
      return data.error
    }
  } catch {
    // 全局异常处理器多数错误直接返回纯文本。
  }
  return body || `请求失败（HTTP ${response.status}）`
}

// JSON 与 SSE 共用鉴权和 HTTP 错误处理，避免流式请求漏掉 401/403/429。
export async function fetchApi(path: string, init: RequestInit = {}): Promise<Response> {
  const auth = useAuthStore()
  const headers = new Headers(init.headers)
  if (init.body != null && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (auth.token) headers.set('Authorization', auth.token)

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (cause) {
    if (init.signal?.aborted) throw cause
    throw new ApiError(0, '网络连接失败，请检查服务是否启动')
  }

  if (!response.ok) {
    const message = await errorMessage(response)
    if (response.status === 401) {
      auth.clearSession()
      useChatStore().clear()
      if (window.location.pathname !== '/login') window.location.assign('/login')
    }
    throw new ApiError(response.status, message)
  }

  return response
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetchApi(path, init)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
