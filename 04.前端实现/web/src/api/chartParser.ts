const PREFIX = 'CHART_JSON:'
const MAX_ITEMS = 200

export type ChartSpec =
  | { kind: 'line' | 'bar'; title: string; labels: string[]; values: number[]; unit: string }
  | { kind: 'pie'; title: string; data: Array<{ name: string; value: number }>; unit: string }

export type ReplyPart = { type: 'text'; text: string } | { type: 'chart'; chart: ChartSpec }
export interface ParsedReply {
  parts: ReplyPart[]
  invalidCharts: number
}

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : null
}

function safeLabel(value: unknown, maxLength = 100): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength && !/[<>\u0000-\u001f]/.test(value)
}

function safeNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

/** 只取当前后端工具实际产出的字段，不把模型转交的原始 option 直接传给 ECharts。 */
export function toChartSpec(value: unknown): ChartSpec | null {
  const option = object(value)
  const title = object(option?.title)?.text
  const series = option?.series
  if (!safeLabel(title, 120) || !Array.isArray(series) || series.length !== 1) return null
  const item = object(series[0])
  if (!item) return null

  if (item.type === 'pie') {
    if (!Array.isArray(item.data) || item.data.length < 1 || item.data.length > MAX_ITEMS) return null
    const data = item.data.map((entry: unknown) => {
      const point = object(entry)
      return point && safeLabel(point.name) && safeNumber(point.value)
        ? { name: point.name, value: point.value } : null
    })
    if (data.some((entry) => entry === null)) return null
    return { kind: 'pie', title, data: data as Array<{ name: string; value: number }>, unit: '元' }
  }

  if (item.type !== 'line' && item.type !== 'bar') return null
  const axis = object(option?.xAxis)
  const labels = axis?.data
  const values = item.data
  if (axis?.type !== 'category' || !Array.isArray(labels) || !Array.isArray(values)
      || labels.length < 1 || labels.length > MAX_ITEMS || labels.length !== values.length) return null
  if (!labels.every((label: unknown) => safeLabel(label))
      || !values.every((number: unknown) => safeNumber(number))) return null

  if (object(option?.yAxis)?.name !== '销售额（元）') return null
  return { kind: item.type, title, labels: labels as string[], values: values as number[], unit: '元' }
}

/** 在 JSON 字符串引号外匹配成对的大括号；支持标题中含有 { } 或转义引号。 */
function jsonEnd(text: string, start: number): number | null {
  let depth = 0
  let quoted = false
  let escaped = false
  for (let index = start; index < text.length; index++) {
    const char = text[index]
    if (quoted) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === '"') quoted = false
      continue
    }
    if (char === '"') quoted = true
    else if (char === '{') depth++
    else if (char === '}' && --depth === 0) return index + 1
  }
  return null
}

/**
 * 从完整回答中抽取一张或多张 CHART_JSON 图表，保留原有文字顺序。
 * 解析失败时保留原始文本，并计数供界面提示，不静默丢数据。
 */
export function parseChartReply(reply: string): ParsedReply {
  const parts: ReplyPart[] = []
  let invalidCharts = 0
  let cursor = 0
  const pushText = (text: string) => {
    if (text.trim()) parts.push({ type: 'text', text: text.trim() })
  }

  while (cursor < reply.length) {
    const marker = reply.indexOf(PREFIX, cursor)
    if (marker < 0) {
      pushText(reply.slice(cursor))
      break
    }

    let start = marker + PREFIX.length
    while (start < reply.length && /\s/.test(reply[start])) start++
    if (reply[start] !== '{') {
      invalidCharts++
      pushText(reply.slice(cursor, start))
      cursor = start
      continue
    }
    const end = jsonEnd(reply, start)
    if (end === null) {
      invalidCharts++
      pushText(reply.slice(cursor))
      break
    }

    let chart: ChartSpec | null = null
    try { chart = toChartSpec(JSON.parse(reply.slice(start, end))) } catch { /* malformed JSON */ }
    if (chart) {
      pushText(reply.slice(cursor, marker))
      parts.push({ type: 'chart', chart })
    } else {
      invalidCharts++
      pushText(reply.slice(cursor, end))
    }
    cursor = end
  }
  return { parts, invalidCharts }
}
