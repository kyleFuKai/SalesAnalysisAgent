<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{ busy: boolean }>()
const emit = defineEmits<{ send: [message: string] }>()
const draft = ref('')
const limit = 2000
const remaining = computed(() => limit - draft.value.length)

function submit() {
  const message = draft.value.trim()
  if (!message || props.busy || draft.value.length > limit) return
  emit('send', message)
  draft.value = ''
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  submit()
}

defineExpose({
  fill(value: string) { draft.value = value },
})
</script>

<template>
  <form class="chat-composer" @submit.prevent="submit">
    <label class="sr-only" for="chat-question">向销售分析 Agent 提问</label>
    <textarea id="chat-question" v-model="draft" rows="3" :maxlength="limit"
      :disabled="busy" placeholder="问问销售数据，例如：我本月销售额是多少？"
      @keydown="onKeydown" />
    <div class="composer-footer">
      <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>
      <span class="composer-count" :class="{ 'is-low': remaining < 100 }">{{ remaining }} 字可输入</span>
      <button type="submit" :disabled="busy || !draft.trim()">{{ busy ? '分析中…' : '发送' }}</button>
    </div>
  </form>
</template>
