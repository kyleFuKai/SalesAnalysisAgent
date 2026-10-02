<script setup lang="ts">
import type { UserRole } from '../../api/auth'
import { promptsForRole } from './rolePrompts'

defineProps<{ role: UserRole | null; disabled?: boolean }>()
const emit = defineEmits<{ select: [prompt: string] }>()

</script>

<template>
  <div class="suggestion-list">
    <button v-for="prompt in promptsForRole(role)" :key="prompt" type="button"
      :disabled="disabled" @click="emit('select', prompt)">
      <span>{{ prompt }}</span><span aria-hidden="true">↗</span>
    </button>
  </div>
</template>
