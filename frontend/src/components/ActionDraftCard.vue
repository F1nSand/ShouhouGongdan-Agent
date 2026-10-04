<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Draft } from '../api/tickets'
import { formatCents } from '../domain/display'
import StatusBadge from './StatusBadge.vue'

const props = defineProps<{ draft: Draft; confirmDraft: (draft: Draft) => Promise<unknown> }>()
const confirming = ref(false)
const confirmation = ref('')
const payload = computed(() => props.draft.payload ?? {})
const title = computed(() => {
  if (props.draft.type === 'CREATE_TICKET') return String(payload.value.title ?? '售后工单')
  if (props.draft.type === 'REQUEST_REFUND') return '退款申请'
  if (props.draft.type === 'REQUEST_STOCK_TRANSFER') return '库存调拨申请'
  return '操作申请'
})
const orderNo = computed(() => String(payload.value.orderNo ?? payload.value.orderNumber ?? ''))
const amount = computed(() => {
  const value = payload.value.amountCents
  return typeof value === 'number' ? formatCents(value) : ''
})

async function confirm() {
  confirming.value = true
  confirmation.value = ''
  try {
    await props.confirmDraft(props.draft)
  } catch (error) {
    confirmation.value = error instanceof Error ? error.message : '操作未完成，请重试。'
  } finally {
    confirming.value = false
  }
}
</script>

<template>
  <article class="draft-card">
    <div class="draft-card__top"><span class="draft-card__icon">↗</span><StatusBadge :status="draft.status" /></div>
    <div class="draft-card__body">
      <span class="eyebrow">{{ draft.type === 'CREATE_TICKET' ? '工单草稿' : '敏感操作草稿' }}</span>
      <h3>{{ title }}</h3>
      <dl class="draft-card__facts">
        <template v-if="orderNo"><dt>订单</dt><dd>{{ orderNo }}</dd></template>
        <template v-if="payload.category"><dt>类别</dt><dd>{{ payload.category }}</dd></template>
        <template v-if="payload.description"><dt>问题摘要</dt><dd>{{ payload.description }}</dd></template>
        <template v-if="amount"><dt>申请金额</dt><dd>{{ amount }}</dd></template>
        <template v-if="payload.reason"><dt>申请理由</dt><dd>{{ payload.reason }}</dd></template>
        <template v-if="payload.quantity"><dt>调拨数量</dt><dd>{{ payload.quantity }} 件</dd></template>
        <template v-if="payload.skuCode"><dt>商品编码</dt><dd>{{ payload.skuCode }}</dd></template>
        <template v-if="payload.targetWarehouseName"><dt>目标仓</dt><dd>{{ payload.targetWarehouseName }}</dd></template>
      </dl>
      <div v-if="draft.type !== 'CREATE_TICKET'" class="draft-card__notice">提交后仍需对应角色审批</div>
      <p v-if="confirmation" class="form-error" role="alert">{{ confirmation }}</p>
      <button v-if="draft.status === 'READY'" class="button button--primary button--wide" :disabled="confirming" @click="confirm">
        {{ confirming ? '正在确认…' : draft.type === 'CREATE_TICKET' ? '确认创建工单' : '提交审批申请' }}
      </button>
      <div v-else-if="draft.status === 'CONFIRMED'" class="draft-card__result">已提交，结果已记录</div>
    </div>
  </article>
</template>
