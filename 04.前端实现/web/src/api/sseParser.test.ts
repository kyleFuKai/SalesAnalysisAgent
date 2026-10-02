import { describe, expect, it } from 'vitest'
import { SseParser, type SseEvent } from './sseParser'

describe('SseParser', () => {
  it('解析一包中的多个事件及多行 data', () => {
    const events: SseEvent[] = []
    const parser = new SseParser((event) => events.push(event))
    parser.feed(': keep-alive\nevent: token\ndata: 第一行\ndata: 第二行\n\nevent: done\ndata: [DONE]\n\n')
    expect(events).toEqual([
      { event: 'token', data: '第一行\n第二行' },
      { event: 'done', data: '[DONE]' },
    ])
  })

  it('支持跨分片的 CRLF、字段和值', () => {
    const events: SseEvent[] = []
    const parser = new SseParser((event) => events.push(event))
    parser.feed('event: to')
    parser.feed('ken\r')
    parser.feed('\ndata: 华东\r\n\r')
    expect(events).toEqual([])
    parser.feed('\n')
    expect(events).toEqual([{ event: 'token', data: '华东' }])
  })

  it('未到空行时不提前派发事件', () => {
    const events: SseEvent[] = []
    const parser = new SseParser((event) => events.push(event))
    parser.feed('event: token\ndata: 等待')
    expect(events).toEqual([])
    parser.feed('\n\n')
    expect(events).toEqual([{ event: 'token', data: '等待' }])
  })
})
