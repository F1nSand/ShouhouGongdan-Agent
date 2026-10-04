<script setup lang="ts">
import { computed } from 'vue'
import type { RunEvent } from '../domain/display'

const props = defineProps<{ events: RunEvent[]; busy?: boolean }>()
const tools = computed(() => {
  const byId = new Map<string, { name: string; state: 'running' | 'done' | 'failed' }>()
  for (const event of props.events) {
    if (!event.eventType.startsWith('tool.')) continue
    const key = String(event.payload.invocationId ?? event.sequence)
    const existing = byId.get(key)
    const name = String(event.payload.toolName ?? existing?.name ?? '业务查询')
    if (event.eventType === 'tool.started') byId.set(key, { name, state: 'running' })
    if (event.eventType === 'tool.completed') byId.set(key, { name, state: event.payload.success === false ? 'failed' : 'done' })
  }
  return [...byId.values()]
})

const labels: Record<string, string> = {
  query_order: '查询订单', query_logistics: '核对物流', retrieve_policy: '检索售后政策',
  query_inventory: '查询库存', recommend_transfer: '生成调拨建议', draft_ticket: '整理工单草稿',
  draft_sensitive_action: '整理审批申请',
}
</script>

<template>
  <div v-if="busy || tools.length" class="tool-progress" aria-live="polite">
    <div class="tool-progress__top"><span class="eyebrow">处理过程</span><span v-if="busy" class="live-indicator">实时</span></div>
    <div v-for="(tool, index) in tools" :key="`${tool.name}-${index}`" class="tool-progress__row">
      <span class="tool-progress__mark" :class="`is-${tool.state}`" aria-hidden="true">
        <span v-if="tool.state === 'running'" class="spinner" />
        <span v-else>{{ tool.state === 'done' ? '✓' : '!' }}</span>
      </span>
      <span>{{ labels[tool.name] ?? tool.name }}</span>
      <span class="tool-progress__state">{{ tool.state === 'running' ? '进行中' : tool.state === 'done' ? '已完成' : '未完成' }}</span>
    </div>
    <div v-if="busy && tools.length === 0" class="tool-progress__row tool-progress__row--pending"><span class="spinner" />正在分析问题并准备查询</div>
  </div>
</template>
