export interface SseEvent {
  event: string
  data: string
}

/**
 * 按 SSE 的“空行结束事件”规则增量解析文本。
 * 不假设一次网络读取刚好是一整行或一整条事件。
 */
export class SseParser {
  private pending = ''
  private eventName = ''
  private dataLines: string[] = []

  constructor(private readonly onEvent: (event: SseEvent) => void) {}

  feed(chunk: string): void {
    this.pending += chunk
    while (true) {
      const carriageReturn = this.pending.indexOf('\r')
      const lineFeed = this.pending.indexOf('\n')
      let end: number
      if (carriageReturn < 0) end = lineFeed
      else if (lineFeed < 0) end = carriageReturn
      else end = Math.min(carriageReturn, lineFeed)
      if (end < 0) break

      const delimiter = this.pending[end]
      // CRLF 恰好跨网络分片时，先等下一片再决定分隔符长度。
      if (delimiter === '\r' && end === this.pending.length - 1) break
      const delimiterLength = delimiter === '\r' && this.pending[end + 1] === '\n' ? 2 : 1
      const line = this.pending.slice(0, end)
      this.pending = this.pending.slice(end + delimiterLength)
      this.processLine(line)
    }
  }

  private processLine(line: string): void {
    if (line === '') {
      if (this.dataLines.length > 0) {
        this.onEvent({ event: this.eventName || 'message', data: this.dataLines.join('\n') })
      }
      this.eventName = ''
      this.dataLines = []
      return
    }
    if (line.startsWith(':')) return // SSE 心跳注释。

    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') this.eventName = value
    else if (field === 'data') this.dataLines.push(value)
  }
}
