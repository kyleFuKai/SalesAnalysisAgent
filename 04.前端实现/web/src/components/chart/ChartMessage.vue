<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import type { ChartSpec } from '../../api/chartParser'

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])

const props = defineProps<{ spec: ChartSpec }>()
const chartElement = ref<HTMLDivElement | null>(null)
const renderFailed = ref(false)
let chart: ReturnType<typeof echarts.init> | null = null
let resizeObserver: ResizeObserver | null = null
const numberFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })

const rows = computed(() => {
  const spec = props.spec
  return spec.kind === 'pie'
    ? spec.data.map((item) => ({ label: item.name, value: item.value }))
    : spec.labels.map((label, index) => ({ label, value: spec.values[index] ?? 0 }))
})

function safeOption(spec: ChartSpec): EChartsOption {
  if (spec.kind === 'pie') {
    return {
      tooltip: { trigger: 'item', renderMode: 'richText' },
      legend: { orient: 'vertical', left: 0, top: 'middle', type: 'scroll' },
      series: [{ type: 'pie', radius: '58%', center: ['62%', '50%'], data: spec.data }],
    }
  }
  return {
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    grid: { left: 20, right: 24, top: 24, bottom: 18, containLabel: true },
    xAxis: { type: 'category', data: spec.labels, axisLabel: { rotate: spec.labels.length > 8 ? 30 : 0 } },
    yAxis: { type: 'value', name: '销售额（元）' },
    series: [{ type: spec.kind, data: spec.values,
      ...(spec.kind === 'line' ? { smooth: true, areaStyle: { opacity: 0.08 } } : {}) }],
  }
}

function renderChart() {
  if (!chartElement.value) return
  try {
    if (!chart) chart = echarts.init(chartElement.value, undefined, { renderer: 'svg' })
    chart.setOption(safeOption(props.spec), { notMerge: true })
    renderFailed.value = false
  } catch {
    renderFailed.value = true
    chart?.dispose()
    chart = null
  }
}

function resizeChart() { chart?.resize() }

onMounted(() => {
  renderChart()
  if (chartElement.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(resizeChart)
    resizeObserver.observe(chartElement.value)
  } else {
    window.addEventListener('resize', resizeChart)
  }
})
watch(() => props.spec, renderChart)
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="chart-card" :aria-label="spec.title">
    <header class="chart-card-header">
      <h3>{{ spec.title }}</h3>
      <span>单位：{{ spec.unit }}</span>
    </header>
    <div v-show="!renderFailed" ref="chartElement" class="chart-canvas" role="img"
      :aria-label="`${spec.title}，${rows.length} 个数据点`"></div>
    <p v-if="renderFailed" class="chart-fallback" role="alert">图表渲染失败，可展开下方数据查看具体数值。</p>
    <details class="chart-data">
      <summary>查看图表数据</summary>
      <div class="chart-table-scroll">
        <table>
          <thead><tr><th scope="col">{{ spec.kind === 'pie' ? '名称' : '时间或分类' }}</th><th scope="col">销售额（{{ spec.unit }}）</th></tr></thead>
          <tbody><tr v-for="(row, index) in rows" :key="`${row.label}-${index}`">
            <td>{{ row.label }}</td><td>{{ numberFormat.format(row.value) }}</td>
          </tr></tbody>
        </table>
      </div>
    </details>
  </section>
</template>
