import { describe, expect, it } from 'vitest'
import { parseChartReply, toChartSpec } from './chartParser'

const line = {
  title: { text: '近半年趋势' }, xAxis: { type: 'category', data: ['1月', '2月'] },
  yAxis: { type: 'value', name: '销售额（元）' },
  series: [{ type: 'line', data: [120, 180], itemStyle: { color: '#3688ff' } }],
}

describe('图表协议解析', () => {
  it('提取正文中的折线图，并丢弃额外配置', () => {
    const reply = `分析如下：\nCHART_JSON:${JSON.stringify({ ...line, graphic: [{ type: 'text', style: { text: '<script>' } }] })}\n已结束。`
    expect(parseChartReply(reply)).toEqual({
      invalidCharts: 0,
      parts: [
        { type: 'text', text: '分析如下：' },
        { type: 'chart', chart: { kind: 'line', title: '近半年趋势', labels: ['1月', '2月'], values: [120, 180], unit: '元' } },
        { type: 'text', text: '已结束。' },
      ],
    })
  })

  it('支持柱状图与饼图', () => {
    expect(toChartSpec({ ...line, series: [{ type: 'bar', data: [1, 2] }] })?.kind).toBe('bar')
    expect(toChartSpec({ title: { text: '占比' }, series: [{ type: 'pie', data: [{ name: '华东', value: 30 }] }] }))
      .toEqual({ kind: 'pie', title: '占比', data: [{ name: '华东', value: 30 }], unit: '元' })
  })

  it('拒绝危险标签、负数与不完整 JSON，保留原回答', () => {
    expect(toChartSpec({ ...line, xAxis: { type: 'category', data: ['<img>'] } })).toBeNull()
    expect(toChartSpec({ ...line, series: [{ type: 'line', data: [-1] }] })).toBeNull()
    const malformed = 'CHART_JSON:{"title":'
    expect(parseChartReply(malformed)).toEqual({ invalidCharts: 1, parts: [{ type: 'text', text: malformed }] })
  })

  it('按完整回答统一解析，同步和流式聚合后的结果一致', () => {
    const reply = `CHART_JSON:${JSON.stringify(line)}`
    expect(parseChartReply(reply)).toEqual(parseChartReply(['CHART_JSON:', JSON.stringify(line)].join('')))
  })
})
