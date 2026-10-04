<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { approvalsApi, type SensitiveAction } from '../api/approvals'
import { useAuthStore } from '../stores/auth'
import NavIcon from '../components/NavIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'
import CitationPanel from '../components/CitationPanel.vue'
import { formatCents } from '../domain/display'

const auth = useAuthStore()
const actions = ref<SensitiveAction[]>([])
const loading = ref(true)
const error = ref('')
const statusFilter = ref('PENDING_APPROVAL')
const selected = ref<SensitiveAction | null>(null)
const reason = ref('')
const decision = ref<'APPROVE' | 'REJECT'>('APPROVE')
const submitting = ref(false)
let refreshTimer = 0
const visibleActions = computed(() => actions.value.filter((action) => !statusFilter.value || action.status === statusFilter.value))

async function load(silent = false) {
  if (!silent) loading.value = true
  try { actions.value = await approvalsApi.list(1, 100); error.value = '' }
  catch (cause) { if (!silent) error.value = cause instanceof Error ? cause.message : '审批列表暂不可用。' }
  finally { loading.value = false }
}

function canDecide(action: SensitiveAction) { return auth.canDecide(action.type, action.requestedBy) && action.status === 'PENDING_APPROVAL' }
function typeLabel(type: string) { return type === 'REFUND_REQUEST' ? '退款审批' : type === 'STOCK_TRANSFER' ? '库存调拨审批' : type }
function valueLabel(value: unknown) { return value == null ? '—' : typeof value === 'string' ? value : String(value) }
function displayAmount(value: unknown) { return typeof value === 'number' ? formatCents(value) : valueLabel(value) }
function executionLabel(action: SensitiveAction) {
  if (action.type !== 'REFUND_REQUEST') return '库存已预留'
  return action.executionResult?.executionMode === 'LOCAL_DEMO_LEDGER' ? '演示订单退款已入账' : '模拟退款执行'
}
function openDecision(action: SensitiveAction, choice: 'APPROVE' | 'REJECT') {
  selected.value = action
  decision.value = choice
  reason.value = ''
}

async function submitDecision() {
  if (!selected.value || reason.value.trim().length < 2 || submitting.value) return
  submitting.value = true
  try {
    await approvalsApi.decide(selected.value, decision.value, reason.value.trim())
    selected.value = null
    statusFilter.value = ''
    await load(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '审批提交失败，请刷新后重试。'
    if (error.value.includes('版本') || error.value.includes('已被')) await load(true)
  } finally { submitting.value = false }
}

onMounted(() => { void load(); refreshTimer = window.setInterval(() => void load(true), 5000) })
onUnmounted(() => window.clearInterval(refreshTimer))
</script>

<template>
  <section class="records-page approval-page">
    <header class="page-heading records-heading"><div><span class="eyebrow">REVIEW / HUMAN DECISION</span><h1>人工审批</h1><p>审批人只决定批准或拒绝，不修改申请中的业务参数。</p></div><span class="approval-scope">当前身份 · {{ auth.role === 'SERVICE_SUPERVISOR' ? '退款申请' : auth.role === 'WAREHOUSE_OPERATOR' ? '调拨申请' : '全部申请' }}</span></header>
    <div v-if="error" class="inline-alert" role="alert">{{ error }}<button class="alert-close" @click="error = ''">×</button></div>
    <div class="approval-toolbar"><div class="filter-tabs" role="tablist" aria-label="审批状态"><button v-for="filter in [{ value: 'PENDING_APPROVAL', label: '待我处理' }, { value: '', label: '全部记录' }, { value: 'APPROVED', label: '已批准' }, { value: 'REJECTED', label: '已拒绝' }]" :key="filter.label" :class="{ 'is-active': statusFilter === filter.value }" role="tab" :aria-selected="statusFilter === filter.value" @click="statusFilter = filter.value">{{ filter.label }}</button></div><span class="records-total">{{ visibleActions.length }} 条记录</span></div>
    <div v-if="loading" class="table-loading"><span class="spinner" />正在读取审批记录…</div>
    <div v-else-if="!visibleActions.length" class="approval-empty"><span class="approval-empty__index">NO. 00</span><h2>当前没有待处理申请</h2><p>新的申请会出现在这里，并按角色显示对应审批权限。</p></div>
    <div v-else class="approval-list"><article v-for="action in visibleActions" :key="action.id" class="approval-card"><div class="approval-card__rail"><span class="approval-card__kind">{{ action.type === 'REFUND_REQUEST' ? '¥' : '↗' }}</span><small>{{ new Date(action.expiresAt).toLocaleDateString('zh-CN') }}</small></div><div class="approval-card__main"><div class="approval-card__top"><span class="eyebrow">{{ typeLabel(action.type) }} / {{ action.id.slice(0, 8) }}</span><StatusBadge :status="action.status" /></div><h2>{{ action.type === 'REFUND_REQUEST' ? displayAmount(action.payload.amountCents) : `${valueLabel(action.payload.quantity)} 件库存调拨` }}</h2><div class="approval-card__facts"><div v-if="action.type === 'REFUND_REQUEST'"><span>申请原因</span><strong>{{ valueLabel(action.payload.reason) }}</strong></div><div v-else><span>商品 ID</span><strong>{{ valueLabel(action.payload.skuId) }}</strong></div><div><span>关联工单</span><strong>{{ action.ticketId ?? '未关联' }}</strong></div><div><span>申请人</span><strong>{{ action.requestedBy === auth.identity?.actorId ? '我提交的申请' : action.requestedBy }}</strong></div><div v-if="action.type === 'STOCK_TRANSFER'"><span>来源仓 → 目标仓</span><strong>{{ valueLabel(action.payload.sourceWarehouseId) }} → {{ valueLabel(action.payload.targetWarehouseId) }}</strong></div><div><span>截止时间</span><strong>{{ new Date(action.expiresAt).toLocaleString('zh-CN') }}</strong></div></div><CitationPanel v-if="Array.isArray(action.evidenceSnapshot)" :citations="action.evidenceSnapshot as never[]" compact /><div v-else-if="action.evidenceSnapshot" class="evidence-summary"><span class="eyebrow">申请依据快照</span><pre>{{ JSON.stringify(action.evidenceSnapshot, null, 2) }}</pre></div><div v-if="action.executionResult" class="execution-result"><span class="eyebrow">执行结果</span><strong>{{ executionLabel(action) }}</strong><small>{{ JSON.stringify(action.executionResult) }}</small></div><div v-if="action.errorCode" class="execution-error">{{ action.errorCode }} · 执行未完成</div><div v-if="action.status === 'APPROVED' && !action.executionResult" class="execution-pending"><span class="spinner" />审批已通过，业务任务正在执行…</div><div v-if="action.requestedBy === auth.identity?.actorId && action.status === 'PENDING_APPROVAL'" class="self-approval-note">此申请由当前账号提交，不能由本人审批。</div><div v-if="canDecide(action)" class="approval-card__actions"><button class="button button--secondary" @click="openDecision(action, 'REJECT')">拒绝申请</button><button class="button button--primary" @click="openDecision(action, 'APPROVE')">批准申请 <NavIcon name="arrow" :size="16" /></button></div></div></article></div>
    <footer class="records-foot"><span><span class="service-led"></span>审批操作会记录申请版本与决定原因</span><span>通过审批后，由后台业务任务执行</span></footer>
    <div v-if="selected" class="modal-scrim" @click.self="selected = null"><form class="dialog" @submit.prevent="submitDecision"><div class="dialog__header"><div><span class="eyebrow">{{ decision === 'APPROVE' ? 'APPROVE REQUEST' : 'REJECT REQUEST' }}</span><h2>{{ decision === 'APPROVE' ? '确认批准申请？' : '确认拒绝申请？' }}</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="selected = null"><NavIcon name="close" /></button></div><div class="decision-summary"><span>{{ typeLabel(selected.type) }}</span><strong>{{ selected.type === 'REFUND_REQUEST' ? displayAmount(selected.payload.amountCents) : `${valueLabel(selected.payload.quantity)} 件` }}</strong></div><label class="field"><span>决定原因 <small>必填，最多 500 字</small></span><textarea v-model="reason" rows="4" maxlength="500" required placeholder="说明本次审批决定的业务依据" /></label><div class="dialog__actions"><button type="button" class="button button--secondary" @click="selected = null">返回</button><button class="button" :class="decision === 'APPROVE' ? 'button--primary' : 'button--danger'" :disabled="submitting || reason.trim().length < 2">{{ submitting ? '正在提交…' : decision === 'APPROVE' ? '确认批准' : '确认拒绝' }}</button></div></form></div>
  </section>
</template>
