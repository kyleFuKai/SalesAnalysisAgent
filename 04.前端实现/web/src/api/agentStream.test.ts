import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchApi } from './http'
import { streamSalesAgent, StreamInterruptedError } from './agentStream'

vi.mock('./http', () => ({ fetchApi: vi.fn() }))

function sseResponse(chunks: Uint8Array[]): Response {
  return new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } })
}

describe('streamSalesAgent', () => {
  beforeEach(() => vi.clearAllMocks())

  it('跨 UTF-8 字节分片拼出 token，并以 done 完成', async () => {
    const bytes = new TextEncoder().encode('event: token\ndata: 华东\n\nevent: done\ndata: [DONE]\n\n')
    const cut = bytes.indexOf(0xe5) + 1 // 故意从“华”的 UTF-8 中间拆开。
    vi.mocked(fetchApi).mockResolvedValue(sseResponse([bytes.slice(0, cut), bytes.slice(cut)]))
    const tokens: string[] = []
    await streamSalesAgent('session-1', '本月业绩', { onToken: (token) => tokens.push(token) })
    expect(tokens).toEqual(['华东'])
    expect(fetchApi).toHaveBeenCalledWith('/agent/chat/stream', expect.objectContaining({ method: 'POST' }))
  })

  it('流提前结束且没有 done 时拒绝当作成功', async () => {
    const bytes = new TextEncoder().encode('event: token\ndata: 只收到一半\n\n')
    vi.mocked(fetchApi).mockResolvedValue(sseResponse([bytes]))
    const tokens: string[] = []
    await expect(streamSalesAgent('session-2', '趋势', { onToken: (token) => tokens.push(token) }))
      .rejects.toBeInstanceOf(StreamInterruptedError)
    expect(tokens).toEqual(['只收到一半'])
  })

  it('error 事件的提示会抛出，不会当成回答', async () => {
    const bytes = new TextEncoder().encode('event: error\ndata: 服务暂时不可用\n\n')
    vi.mocked(fetchApi).mockResolvedValue(sseResponse([bytes]))
    await expect(streamSalesAgent('session-3', '订单', { onToken: () => {} }))
      .rejects.toThrow('服务暂时不可用')
  })

  it('缓存命中的整段 token 也正常返回', async () => {
    const bytes = new TextEncoder().encode('event: token\ndata: 本月销售额 100 元\n\nevent: done\ndata: [DONE]\n\n')
    vi.mocked(fetchApi).mockResolvedValue(sseResponse([bytes]))
    const tokens: string[] = []
    await streamSalesAgent('session-4', '销售额', { onToken: (token) => tokens.push(token) })
    expect(tokens).toEqual(['本月销售额 100 元'])
  })
})
