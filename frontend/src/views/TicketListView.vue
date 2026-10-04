<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ticketsApi, type Ticket } from '../api/tickets'
import NavIcon from '../components/NavIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'

const router = useRouter()
const tickets = ref<Ticket[]>([])
const status = ref('')
const search = ref('')
const loading = ref(true)
const error = ref('')
const filtered = computed(() => tickets.value.filter((ticket) => {
  const keyword = search.value.trim().toLowerCase()
  return (!keyword || `${ticket.ticketNo} ${ticket.title} ${ticket.category}`.toLowerCase().includes(keyword))
    && (!status.value || ticket.status === status.value)
}))

async function load() {
  loading.value = true
  error.value = ''
  try { tickets.value = (await ticketsApi.list({ page: 1, size: 100 })).items }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '工单列表暂不可用。' }
  finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <section class="records-page">
    <header class="page-heading records-heading"><div><span class="eyebrow">CUSTOMER CARE / CASES</span><h1>售后工单</h1><p>跟踪待处理事项、服务进度与处理记录。</p></div><button class="button button--secondary" @click="load"><NavIcon name="search" :size="17" />刷新列表</button></header>
    <div class="records-toolbar"><label class="search-field"><NavIcon name="search" :size="17" /><input v-model="search" placeholder="搜索工单号、标题或类别" aria-label="搜索工单" /></label><label class="select-field"><span>处理状态</span><select v-model="status"><option value="">全部状态</option><option value="OPEN">待处理</option><option value="ASSIGNED">已分派</option><option value="IN_PROGRESS">处理中</option><option value="WAITING_APPROVAL">待审批</option><option value="RESOLVED">已解决</option><option value="CLOSED">已关闭</option></select></label><span class="records-total">共 {{ filtered.length }} 条</span></div>
    <div v-if="error" class="inline-alert" role="alert">{{ error }}</div>
    <div class="table-card"><div class="table-scroll"><table class="data-table"><thead><tr><th>工单编号</th><th>问题摘要</th><th>类别</th><th>优先级</th><th>创建时间</th><th>状态</th><th><span class="sr-only">操作</span></th></tr></thead><tbody>
      <tr v-for="ticket in filtered" :key="ticket.id" class="table-row-clickable" tabindex="0" @click="router.push(`/tickets/${ticket.id}`)" @keydown.enter="router.push(`/tickets/${ticket.id}`)"><td><span class="ticket-code">{{ ticket.ticketNo }}</span></td><td><strong class="table-primary">{{ ticket.title }}</strong><small class="table-secondary">{{ ticket.description }}</small></td><td>{{ ticket.category }}</td><td><span class="priority-label" :class="`priority-label--${ticket.priority.toLowerCase()}`">{{ ticket.priority }}</span></td><td>{{ new Date(ticket.createdAt).toLocaleDateString('zh-CN') }}</td><td><StatusBadge :status="ticket.status" /></td><td><button class="row-arrow" :aria-label="`查看工单 ${ticket.ticketNo}`" @click.stop="router.push(`/tickets/${ticket.id}`)"><NavIcon name="chevron" :size="17" /></button></td></tr>
      <tr v-if="!loading && filtered.length === 0"><td colspan="7"><div class="table-empty"><span class="table-empty__count">—</span><strong>没有符合条件的工单</strong><p>调整搜索条件，或从工作台创建一张新工单。</p><RouterLink class="text-link" to="/workspace">前往工作台 <NavIcon name="arrow" :size="14" /></RouterLink></div></td></tr>
      <tr v-if="loading"><td colspan="7"><div class="table-loading"><span class="spinner" />正在读取工单…</div></td></tr>
    </tbody></table></div></div>
    <footer class="records-foot"><span><span class="service-led"></span>数据来自当前商家业务系统</span><span>状态与处理记录实时同步</span></footer>
  </section>
</template>
