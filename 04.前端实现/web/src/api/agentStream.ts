import { fetchApi } from './http'
import { SseParser } from './sseParser'

export class StreamInterruptedError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'StreamInterruptedError'
  }
}

export interface StreamOptions {
  signal?: AbortSignal
  onToken: (token: string) => void
}

/** 后端要求 POST + JSON + Authorization；浏览器原生 EventSource 不适用。 */
export async function streamSalesAgent(sessionId: string, message: string, options: StreamOptions): Promise<void> {
  const response = await fetchApi('/agent/chat/stream', {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    body: JSON.stringify({ sessionId, message }),
    signal: options.signal,
  })
  if (!response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) {
    throw new StreamInterruptedError('服务未返回事件流，请检查后端接口或代理配置')
  }
  if (!response.body) throw new StreamInterruptedError('浏览器未收到流式响应内容')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let completed = false
  const parser = new SseParser(({ event, data }) => {
    if (completed) return // done 是终止事件，忽略同一网络分片内的后续内容。
    if (event === 'token') options.onToken(data)
    else if (event === 'done') completed = true
    else if (event === 'error') throw new StreamInterruptedError(data || '生成回答时发生错误')
  })

  try {
    while (!completed) {
      const { done, value } = await reader.read()
      if (done) break
      parser.feed(decoder.decode(value, { stream: true }))
    }
    if (!completed) {
      parser.feed(decoder.decode())
      if (!completed) throw new StreamInterruptedError('回答连接中断，未收到完成信号')
    }
  } finally {
    try { await reader.cancel() } catch { /* 连接已关闭时无需再取消。 */ }
    reader.releaseLock()
  }
}
