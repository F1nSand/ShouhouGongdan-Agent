<script setup lang="ts">
import { ref } from 'vue'
import { auditApi, type AuditEvent } from '../api/audit'
import NavIcon from '../components/NavIcon.vue'

const events = ref<AuditEvent[]>([])
const traceId = ref('')
const resourceId = ref('')
const action = ref('')
const from = ref('')
const to = ref('')
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const expandedId = ref('')

async function search(nextPage = 1) {
  loading.value = true
  error.value = ''
  page.value = nextPage
  try {
    const result = await auditApi.list({
      traceId: traceId.value.trim() || undefined,
      resourceId: resourceId.value.trim() || undefined,
      action: action.value.trim() || undefined,
      from: from.value ? new Date(from.value).toISOString() : undefined,
      to: to.value ? new Date(to.value).toISOString() : undefined,
      page: nextPage,
      size: 50,
    })
    events.value = result.items
    total.value = result.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '审计查询失败。'
    events.value = []
  } finally { loading.value = false }
}

function summary(event: AuditEvent) {
  const text = JSON.stringify(event.parameterSummary ?? {})
  return text.length > 100 ? text.slice(0, 100) + '…' : text
}
</script>

<template>
  <section class="records-page audit-page">
    <header class="page-heading records-heading"><div><span class="eyebrow">AUDIT / TRACEABILITY</span><h1>操作审计</h1><p>按追踪编号、业务对象、操作类型或时间范围筛选记录。</p></div><span class="role-chip">仅管理员</span></header>
    <form class="audit-filter content-card" @submit.prevent="search(1)">
      <label class="field"><span>Trace ID</span><input v-model="traceId" placeholder="追踪编号" /></label>
      <label class="field"><span>业务对象 ID</span><input v-model="resourceId" placeholder="UUID" /></label>
      <label class="field"><span>操作类型</span><input v-model="action" placeholder="例如 ACTION_DRAFT_CONFIRMED" /></label>
      <label class="field"><span>开始时间</span><input v-model="from" type="datetime-local" /></label>
      <label class="field"><span>结束时间</span><input v-model="to" type="datetime-local" /></label>
      <button class="button button--primary" :disabled="loading"><NavIcon name="search" :size="16" />{{ loading ? '查询中…' : '筛选记录' }}</button>
    </form>
    <div v-if="error" class="inline-alert" role="alert">{{ error }}</div>
    <div class="audit-table-caption"><span><span class="service-led"></span>商家范围内的只读审计记录</span><strong>{{ total }} 条结果</strong></div>
    <div class="table-card"><div class="table-scroll"><table class="data-table audit-table"><thead><tr><th>发生时间</th><th>操作</th><th>业务对象</th><th>操作者</th><th>结果</th><th>Trace ID</th><th><span class="sr-only">详情</span></th></tr></thead><tbody>
      <template v-for="event in events" :key="event.id">
        <tr><td>{{ new Date(event.createdAt).toLocaleString('zh-CN') }}</td><td><strong class="table-primary">{{ event.action }}</strong><small class="table-secondary">{{ event.actorRole ?? 'SYSTEM' }}</small></td><td><span class="table-primary">{{ event.resourceType }}</span><small class="table-secondary mono-value">{{ event.resourceId ?? '—' }}</small></td><td>{{ event.actorId ?? '系统任务' }}</td><td><span class="audit-result" :class="event.result === 'SUCCESS' ? 'audit-result--success' : 'audit-result--failure'">{{ event.result === 'SUCCESS' ? '成功' : event.result === 'FAILURE' ? '失败' : event.result }}</span></td><td class="mono-value">{{ event.traceId }}</td><td><button class="row-arrow" :aria-label="'查看操作 ' + event.action + ' 的审计详情'" @click="expandedId = expandedId === event.id ? '' : event.id"><NavIcon name="chevron" :size="16" /></button></td></tr>
        <tr v-if="expandedId === event.id" class="audit-detail-row"><td colspan="7"><div class="audit-detail"><div><span class="eyebrow">PARAMETER SUMMARY</span><pre>{{ JSON.stringify(event.parameterSummary, null, 2) }}</pre></div><div v-if="event.reason"><span class="eyebrow">REASON</span><p>{{ event.reason }}</p></div></div></td></tr>
      </template>
      <tr v-if="!events.length && !loading"><td colspan="7"><div class="table-empty"><span class="table-empty__count">—</span><strong>没有匹配的审计记录</strong><p>选择查询条件后筛选，或检查时间范围。</p></div></td></tr>
      <tr v-if="loading"><td colspan="7"><div class="table-loading"><span class="spinner" />正在读取审计日志…</div></td></tr>
    </tbody></table></div></div>
    <footer class="records-foot"><span>审计记录不可在工作台中修改或删除</span><div><button class="button button--secondary button--small" :disabled="page <= 1 || loading" @click="search(page - 1)">上一页</button><span>第 {{ page }} 页</span><button class="button button--secondary button--small" :disabled="page * 50 >= total || loading" @click="search(page + 1)">下一页</button></div></footer>
  </section>
</template>
