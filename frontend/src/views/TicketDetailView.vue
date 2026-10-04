<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ticketsApi, type Ticket } from '../api/tickets'
import { useAuthStore } from '../stores/auth'
import { useConversationStore } from '../stores/conversation'
import { approvalsApi, type SensitiveAction } from '../api/approvals'
import NavIcon from '../components/NavIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'
import CitationPanel from '../components/CitationPanel.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const conversation = useConversationStore()
const ticket = ref<Ticket | null>(null)
const actions = ref<SensitiveAction[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const updateNote = ref('')
const canProcess = computed(() => ['CUSTOMER_SERVICE', 'SERVICE_SUPERVISOR', 'ADMIN'].includes(auth.role ?? ''))

async function load() {
  loading.value = true
  error.value = ''
  try {
    ticket.value = await ticketsApi.get(String(route.params.id))
    actions.value = (await approvalsApi.list()).filter((action) => action.ticketId === ticket.value?.id)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '工单暂不可用。' }
  finally { loading.value = false }
}

async function assignToMe() {
  if (!ticket.value || !auth.identity) return
  busy.value = true
  try { ticket.value = await ticketsApi.assign(ticket.value, auth.identity.actorId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '领取失败，请刷新后重试。' }
  finally { busy.value = false }
}

async function transition(toStatus: string) {
  if (!ticket.value) return
  busy.value = true
  try {
    ticket.value = await ticketsApi.transition(ticket.value, toStatus, updateNote.value)
    updateNote.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '状态更新失败，请刷新后重试。' }
  finally { busy.value = false }
}

async function openAssistant() {
  await router.push({ name: 'workspace' })
  conversation.$patch({ activeOrder: null })
}

onMounted(load)
watch(() => route.params.id, load)
</script>

<template>
  <section class="detail-page">
    <div class="detail-back"><button class="text-link" @click="router.push('/tickets')"><NavIcon name="chevron" class="rotate-180" :size="15" />返回工单列表</button><button class="button button--secondary button--small" @click="load"><NavIcon name="search" :size="16" />刷新</button></div>
    <div v-if="error" class="inline-alert" role="alert">{{ error }}</div>
    <div v-if="loading" class="table-loading"><span class="spinner" />正在载入工单详情…</div>
    <template v-else-if="ticket">
      <header class="detail-heading"><div><div class="detail-heading__meta"><span class="ticket-code">{{ ticket.ticketNo }}</span><StatusBadge :status="ticket.status" /></div><h1>{{ ticket.title }}</h1><p>{{ ticket.category }} <span>·</span> 创建于 {{ new Date(ticket.createdAt).toLocaleString('zh-CN') }}</p></div><div class="detail-heading__actions"><button v-if="ticket.status === 'OPEN' && canProcess" class="button button--primary" :disabled="busy" @click="assignToMe">领取到我</button><button v-else-if="ticket.status === 'ASSIGNED' && ticket.assignedTo === auth.identity?.actorId" class="button button--primary" :disabled="busy" @click="transition('IN_PROGRESS')">开始处理</button><button v-else-if="ticket.status === 'IN_PROGRESS'" class="button button--primary" :disabled="busy" @click="transition('RESOLVED')">标记已解决</button><button v-else-if="ticket.status === 'RESOLVED'" class="button button--secondary" :disabled="busy" @click="transition('CLOSED')">关闭工单</button><button class="button button--secondary" @click="openAssistant">继续对话 <NavIcon name="arrow" :size="16" /></button></div></header>
      <div class="detail-grid"><div class="detail-main">
        <article class="content-card"><div class="content-card__heading"><div><span class="eyebrow">ISSUE DESCRIPTION</span><h2>问题描述</h2></div><span class="priority-label" :class="`priority-label--${ticket.priority.toLowerCase()}`">{{ ticket.priority }} 优先级</span></div><p class="issue-description">{{ ticket.description }}</p><div v-if="ticket.resolution" class="resolution-note"><span class="eyebrow">处理结论</span><p>{{ ticket.resolution }}</p></div></article>
        <article class="content-card"><div class="content-card__heading"><div><span class="eyebrow">RELATED REQUESTS</span><h2>关联审批</h2></div><span class="card-count">{{ actions.length.toString().padStart(2, '0') }}</span></div><div v-if="actions.length" class="related-actions"><div v-for="action in actions" :key="action.id" class="related-action"><span class="related-action__type">{{ action.type === 'REFUND_REQUEST' ? '退款申请' : '库存调拨' }}</span><StatusBadge :status="action.status" /><span class="related-action__date">截止 {{ new Date(action.expiresAt).toLocaleDateString('zh-CN') }}</span><RouterLink to="/approvals" class="text-link">打开审批 <NavIcon name="arrow" :size="14" /></RouterLink></div></div><div v-else class="subtle-empty">这张工单目前没有关联的敏感操作申请。</div></article>
      </div><aside class="detail-side">
        <article class="content-card"><span class="eyebrow">CASE DETAILS</span><h2>工单信息</h2><dl class="detail-facts"><div><dt>订单 ID</dt><dd>{{ ticket.orderId ?? '未关联订单' }}</dd></div><div><dt>负责人</dt><dd>{{ ticket.assignedTo === auth.identity?.actorId ? '当前账号' : ticket.assignedTo ?? '尚未分派' }}</dd></div><div><dt>更新时间</dt><dd>{{ new Date(ticket.updatedAt).toLocaleString('zh-CN') }}</dd></div></dl></article>
        <article class="content-card"><span class="eyebrow">ACTIVITY</span><h2>处理时间线</h2><ol class="timeline"><li v-for="event in ticket.events" :key="event.id"><span class="timeline__dot"></span><div><strong>{{ event.eventType }}</strong><small>{{ new Date(event.createdAt).toLocaleString('zh-CN') }}</small><p v-if="event.comment">{{ event.comment }}</p></div></li></ol><div v-if="!ticket.events.length" class="subtle-empty">当前还没有额外处理记录。</div></article>
        <article v-if="ticket.evidenceSnapshot" class="content-card"><span class="eyebrow">EVIDENCE SNAPSHOT</span><h2>创建依据</h2><pre class="evidence-pre">{{ JSON.stringify(ticket.evidenceSnapshot, null, 2) }}</pre></article>
      </aside></div>
      <div v-if="ticket.status === 'IN_PROGRESS'" class="resolution-editor"><label class="field"><span>处理备注（可选）</span><input v-model="updateNote" placeholder="补充处理结论" /></label><span>完成后会记录在工单时间线上</span></div>
    </template>
  </section>
</template>
