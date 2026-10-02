<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { parseChartReply, type ParsedReply } from '../../api/chartParser'
import type { ChatMessage } from '../../stores/chat'
const ChartMessage = defineAsyncComponent(() => import('../chart/ChartMessage.vue'))

const props = defineProps<{ message: ChatMessage }>()
const parsed = computed<ParsedReply>(() => props.message.role === 'assistant' && props.message.status === 'success'
  ? parseChartReply(props.message.content)
  : { parts: [{ type: 'text', text: props.message.content }], invalidCharts: 0 })
</script>

<template>
  <article class="message-row" :class="message.role">
    <span class="message-avatar" aria-hidden="true">{{ message.role === 'assistant' ? 'AI' : '我' }}</span>
    <div class="message-main">
      <div class="message-meta">
        <strong>{{ message.role === 'assistant' ? '销售分析 Agent' : '我的提问' }}</strong>
        <span>{{ new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</span>
      </div>
      <!-- 回答文字仍由 Vue 转义，不把模型输出当 HTML；图表只使用校验后的字段。 -->
      <template v-for="(part, index) in parsed.parts" :key="index">
        <div v-if="part.type === 'text'" class="message-bubble">{{ part.text }}</div>
        <ChartMessage v-else :spec="part.chart" />
      </template>
      <div v-if="parsed.invalidCharts" class="chart-fallback" role="status">
        有 {{ parsed.invalidCharts }} 张图表暂时无法展示，原始内容已保留在回答中。
      </div>
      <div v-if="message.status === 'pending'" class="message-state">正在获取数据与生成回答…</div>
      <div v-else-if="message.status === 'failed'" class="message-state error">本轮未完成，请检查提示后重新提问。</div>
      <div v-else-if="message.role === 'assistant' && message.durationMs != null" class="message-state">
        用时 {{ (message.durationMs / 1000).toFixed(1) }} 秒
      </div>
    </div>
  </article>
</template>
