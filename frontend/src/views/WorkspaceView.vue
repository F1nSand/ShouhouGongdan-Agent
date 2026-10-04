<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { watch } from 'vue'
import { useConversationStore } from '../stores/conversation'
import { useAuthStore } from '../stores/auth'
import { ordersApi } from '../api/orders'
import { ticketsApi } from '../api/tickets'
import type { Citation } from '../api/agent'
import ActionDraftCard from '../components/ActionDraftCard.vue'
import CitationPanel from '../components/CitationPanel.vue'
import NavIcon from '../components/NavIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'
import ToolProgress from '../components/ToolProgress.vue'
import { formatCents } from '../domain/display'

const router = useRouter()
const route = useRoute()
const store = useConversationStore()
const auth = useAuthStore()
const input = ref('')
const loading = ref(true)
const manualOpen = ref(false)
const detailOpen = ref(false)
const manualOrderNo = ref('')
const manualTitle = ref('物流异常待跟进')
const manualDescription = ref('')
const pageError = ref('')
const manualBusy = ref(false)
const handledPrompt = ref('')
const latestAssistant = computed(() => [...store.messages].reverse().find((message) => message.role === 'ASSISTANT'))
const citations = computed(() => (latestAssistant.value?.structuredAnswer?.citations ?? []) as Citation[])
const canCreateManualTicket = computed(() => ['CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'ADMIN'].includes(auth.role ?? ''))

onMounted(async () => {
  try {
    await store.loadConversations()
    if (!store.conversations.length) await store.newConversation()
    await applyPromptQuery()
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '工作台暂时无法连接服务。'
  } finally {
    loading.value = false
  }
})

async function applyPromptQuery() {
  const prompt = route.query.prompt
  if (typeof prompt !== 'string' || !prompt.trim() || handledPrompt.value === prompt) return
  handledPrompt.value = prompt
  input.value = prompt
  await router.replace({ path: '/workspace', query: {} })
  await send()
}

async function send() {
  const message = input.value.trim()
  if (!message || store.busy) return
  input.value = ''
  await store.sendMessage(message)
}

watch(() => route.query.prompt, () => { void applyPromptQuery() })

async function createConversation() {
  pageError.value = ''
  try { await store.newConversation() }
  catch (error) { pageError.value = error instanceof Error ? error.message : '创建会话失败。' }
}

async function createManualTicket() {
  if (!manualDescription.value.trim() || manualBusy.value) return
  manualBusy.value = true
  pageError.value = ''
  try {
    const order = manualOrderNo.value.trim() ? await ordersApi.getByNumber(manualOrderNo.value.trim()) : undefined
    const draft = await ticketsApi.createManualDraft({
      orderId: order?.orderId,
      category: '物流异常',
      title: manualTitle.value.trim(),
      description: manualDescription.value.trim(),
      priority: 'NORMAL',
    })
    await ticketsApi.confirmDraft(draft.id, `manual-ticket:${draft.id}`)
    manualOpen.value = false
    manualDescription.value = ''
    await store.refreshMessages()
    pageError.value = '工单已创建，可以在工单列表中查看。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '手工创建工单失败，请稍后重试。'
  } finally {
    manualBusy.value = false
  }
}

function timeLabel(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}
</script>

<template>
  <section class="workspace-page">
    <header class="page-heading workspace-heading">
      <div><span class="eyebrow">AFTERCARE DESK / {{ auth.role?.replaceAll('_', ' ') }}</span><h1>你好，今天要处理什么？</h1><p>用自然语言描述售后问题，订单与政策依据会同步整理。</p></div>
      <button class="button button--secondary" @click="detailOpen = !detailOpen"><NavIcon name="box" :size="18" />业务信息</button>
    </header>
    <div v-if="pageError" class="inline-alert" :class="{ 'inline-alert--success': pageError.includes('已创建') }" role="status">{{ pageError }}<button class="alert-close" aria-label="关闭提示" @click="pageError = ''">×</button></div>

    <div class="workspace-grid">
      <aside class="conversation-rail">
        <div class="conversation-rail__header"><div><span class="eyebrow">最近会话</span><strong>{{ store.conversations.length.toString().padStart(2, '0') }}</strong></div><button class="icon-button icon-button--accent" aria-label="新建会话" title="新建会话" @click="createConversation"><NavIcon name="add" /></button></div>
        <button v-for="conversation in store.conversations" :key="conversation.id" class="conversation-item" :class="{ 'is-current': conversation.id === store.activeConversationId }" @click="store.openConversation(conversation.id)">
          <span class="conversation-item__icon"><NavIcon name="message" :size="17" /></span>
          <span class="conversation-item__copy"><strong>{{ conversation.title || '售后咨询' }}</strong><small>{{ conversation.lastMessage || '开始一段新的咨询' }}</small></span>
          <span class="conversation-item__time">{{ timeLabel(conversation.updatedAt ?? conversation.createdAt) }}</span>
        </button>
        <div v-if="!store.conversations.length && !loading" class="conversation-empty">还没有会话，点击右上角开始。</div>
        <div class="conversation-rail__note"><span class="note-line"></span><p>会话内容仅对创建者开放</p></div>
      </aside>

      <section class="chat-panel" aria-label="售后助手对话">
        <div class="chat-panel__top"><div class="assistant-presence"><span class="assistant-avatar">织</span><span><strong>售后协作助手</strong><small><span class="service-led"></span> 业务查询已就绪</small></span></div><span class="session-tag">SESSION / {{ store.activeConversationId.slice(0, 7) || 'NEW' }}</span></div>
        <div class="message-list" aria-live="polite" aria-relevant="additions text">
          <div v-if="loading" class="chat-loading"><span class="spinner" />正在载入会话…</div>
          <div v-else-if="!store.messages.length && !store.busy" class="welcome-state">
            <div class="welcome-state__index">售后咨询 · 01</div>
            <h2>把问题说给我听。</h2>
            <p>我可以协助核对订单与物流、检索适用政策，并准备工单或审批申请。</p>
            <div class="prompt-chips"><button @click="input = '订单 AF20260926001 还没收到，帮我查一下物流和售后政策'">查订单物流 <NavIcon name="arrow" :size="14" /></button><button @click="input = 'SKU-001 在华东仓的可调配库存是多少？'">看库存建议 <NavIcon name="arrow" :size="14" /></button></div>
          </div>
          <article v-for="message in store.messages" :key="message.id" class="message" :class="`message--${message.role.toLowerCase()}`">
            <div v-if="message.role === 'ASSISTANT'" class="message__avatar">织</div>
            <div class="message__body">
              <div class="message__meta"><strong>{{ message.role === 'USER' ? '你' : '售后协作助手' }}</strong><time>{{ timeLabel(message.createdAt) }}</time></div>
              <div class="message__content">{{ message.content }}</div>
              <CitationPanel :citations="(message.structuredAnswer?.citations ?? []) as Citation[]" />
              <div v-if="message.role === 'ASSISTANT'" v-for="draft in store.drafts.filter((item) => item.runId === message.runId)" :key="draft.id" class="message__draft">
                <ActionDraftCard :draft="draft" :confirm-draft="async (item) => { const result = await store.confirmDraft(item.id); await store.refreshMessages(); return result }" />
              </div>
              <div v-if="message.structuredAnswer?.actionResult" class="action-result">
                <span class="action-result__mark"><NavIcon name="check" :size="15" /></span>
                <span><strong>{{ message.structuredAnswer.actionResult.ticketNo ? `工单 ${message.structuredAnswer.actionResult.ticketNo} 已创建` : '申请已提交' }}</strong><small>{{ message.structuredAnswer.actionResult.status }} · 后续状态可在工单与审批页面追踪</small></span>
                <button v-if="message.structuredAnswer.actionResult.ticketId" class="text-link" @click="router.push(`/tickets/${message.structuredAnswer?.actionResult?.ticketId}`)">查看工单</button>
              </div>
            </div>
          </article>
          <div v-if="store.busy" class="message message--assistant message--streaming">
            <div class="message__avatar">织</div><div class="message__body"><div class="message__meta"><strong>正在处理</strong><span class="live-indicator">实时进度</span></div>
              <ToolProgress :events="store.orderedEvents" :busy="store.busy" />
              <div v-if="store.streamText" class="message__content message__content--stream">{{ store.streamText }}</div>
              <div v-else-if="!store.events.length" class="thinking-line"><span class="spinner" />正在理解问题并核对业务信息</div>
            </div>
          </div>
          <div v-if="store.error" class="chat-error" role="alert"><div><strong>本次查询未完成</strong><p>{{ store.error }}</p></div><button v-if="canCreateManualTicket" class="button button--secondary button--small" @click="manualOpen = true">手工创建工单</button><button class="icon-button" aria-label="关闭错误" @click="store.error = ''"><NavIcon name="close" :size="16" /></button></div>
        </div>
        <form class="composer" @submit.prevent="send">
          <label class="sr-only" for="message-input">描述售后问题</label>
          <textarea id="message-input" v-model="input" rows="2" maxlength="4000" placeholder="输入订单号、商品编码或售后问题…" :disabled="store.busy || !store.activeConversationId" @keydown.enter.exact.prevent="send" />
          <div class="composer__footer"><span>回答会展示实际查询与政策依据</span><div><span class="composer__count">{{ input.length }}/4000</span><button v-if="store.busy" type="button" class="button button--secondary button--small" @click="store.cancelRun">停止</button><button class="send-button" type="submit" aria-label="发送消息" :disabled="!input.trim() || store.busy"><NavIcon name="arrow" :size="18" /></button></div></div>
        </form>
      </section>

      <aside class="context-panel" :class="{ 'context-panel--open': detailOpen }" aria-label="业务信息与政策依据">
        <div class="context-panel__mobile"><strong>业务信息</strong><button class="icon-button" aria-label="关闭业务信息" @click="detailOpen = false"><NavIcon name="close" /></button></div>
        <section class="context-section">
          <div class="context-section__heading"><span class="eyebrow">订单摘要</span><NavIcon name="box" :size="17" /></div>
          <template v-if="store.activeOrder">
            <div class="order-summary__number">{{ store.activeOrder.orderNo }}</div>
            <div class="order-summary__person"><span class="person-monogram">{{ store.activeOrder.customerName.slice(0, 1) }}</span><span><strong>{{ store.activeOrder.customerName }}</strong><small>{{ store.activeOrder.maskedPhone }}</small></span></div>
            <div class="context-facts"><div><span>订单状态</span><StatusBadge :status="store.activeOrder.status" /></div><div><span>实付金额</span><strong>{{ formatCents(store.activeOrder.paidAmountCents) }}</strong></div><div><span>累计退款</span><strong>{{ formatCents(store.activeOrder.refundedAmountCents) }}<small v-if="store.activeOrder.refundedAmountCents > 0"> / {{ store.activeOrder.refundedAmountCents >= store.activeOrder.paidAmountCents ? '已全额退款' : '部分退款' }}</small></strong></div><div><span>下单时间</span><strong>{{ new Date(store.activeOrder.purchasedAt).toLocaleDateString('zh-CN') }}</strong></div></div>
            <div v-for="item in store.activeOrder.items" :key="item.skuId" class="order-product"><span class="product-thumb"><NavIcon name="box" :size="17" /></span><span><strong>{{ item.name }}</strong><small>{{ item.skuCode }} × {{ item.quantity }}</small></span></div>
            <div v-if="store.activeLogistics" class="logistics-strip"><span class="eyebrow">物流动态</span><span v-if="store.activeLogistics.exceptionCodes.length" class="exception-text">{{ store.activeLogistics.exceptionCodes.join(' · ') }}</span><span v-else>暂未发现物流异常</span><small v-if="store.activeLogistics.events.length">{{ store.activeLogistics.events.at(-1)?.description }}</small></div>
          </template>
          <div v-else class="context-empty"><span class="context-empty__symbol">01</span><p>在对话中提供订单号后，这里会显示订单与物流信息。</p></div>
        </section>
        <section class="context-section context-section--citations"><div class="context-section__heading"><span class="eyebrow">政策依据</span><span class="citation-count">{{ citations.length.toString().padStart(2, '0') }}</span></div><CitationPanel :citations="citations" /><p v-if="!citations.length" class="context-empty__text">检索到已发布政策后，来源版本和引用内容会显示在这里。</p></section>
        <div class="context-panel__foot"><span>证据优先</span><span>所有敏感操作都需人工确认</span></div>
      </aside>
    </div>

    <div v-if="manualOpen" class="modal-scrim" @click.self="manualOpen = false"><form class="dialog" @submit.prevent="createManualTicket"><div class="dialog__header"><div><span class="eyebrow">备用流程</span><h2>手工整理工单</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="manualOpen = false"><NavIcon name="close" /></button></div><p>Agent 查询未完成时，仍可由客服手工确认创建工单。</p><label class="field"><span>订单号（可选）</span><input v-model="manualOrderNo" placeholder="AF20260926001" /></label><label class="field"><span>工单标题</span><input v-model="manualTitle" required maxlength="120" /></label><label class="field"><span>问题描述</span><textarea v-model="manualDescription" required maxlength="5000" rows="4" /></label><div class="dialog__actions"><button type="button" class="button button--secondary" @click="manualOpen = false">取消</button><button class="button button--primary" :disabled="manualBusy">{{ manualBusy ? '正在提交…' : '确认创建工单' }}</button></div></form></div>
  </section>
</template>
