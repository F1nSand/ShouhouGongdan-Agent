<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { knowledgeApi, type KnowledgeVersion } from '../api/knowledge'
import { useAuthStore } from '../stores/auth'
import NavIcon from '../components/NavIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'

const STORAGE_KEY = 'aftercare.knowledge-recent'
const auth = useAuthStore()
const versions = ref<KnowledgeVersion[]>([])
const current = ref<KnowledgeVersion | null>(null)
const title = ref('售后退换货政策')
const categoryCode = ref('RETURN_REFUND')
const effectiveFrom = ref(new Date().toISOString().slice(0, 16))
const file = ref<File | null>(null)
const query = ref('退款申请条件')
const chunks = ref<Array<{ chunkId: string; documentTitle: string; versionNo: number; pageNo?: number; content: string }>>([])
const busy = ref(false)
const error = ref('')
const notice = ref('')
const searchBusy = ref(false)
const retryBusy = ref(false)
const canPublish = computed(() => current.value?.status === 'READY')
const canRetry = computed(() => auth.role === 'ADMIN' && current.value?.status === 'FAILED')

onMounted(() => {
  try { versions.value = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? '[]') as KnowledgeVersion[] }
  catch { versions.value = [] }
})

function remember(version: KnowledgeVersion) {
  versions.value = [version, ...versions.value.filter((item) => item.versionId !== version.versionId)].slice(0, 12)
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(versions.value))
  current.value = version
}

async function upload() {
  if (!file.value || busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  const form = new FormData()
  form.set('title', title.value.trim())
  form.set('categoryCode', categoryCode.value.trim())
  form.set('effectiveFrom', new Date(effectiveFrom.value).toISOString())
  form.set('file', file.value)
  try {
    let version = await knowledgeApi.upload(form)
    remember(version)
    notice.value = '文档已上传，正在提取文本并生成检索片段。'
    for (let attempt = 0; attempt < 30 && ['UPLOADED', 'PROCESSING'].includes(version.status); attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 1500))
      version = await knowledgeApi.version(version.versionId)
      remember(version)
    }
    notice.value = version.status === 'READY' ? '文档处理完成，可以预览检索结果并发布。'
      : version.status === 'FAILED' ? '文档处理失败，请查看失败原因。' : '文档仍在处理中，可稍后刷新状态。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '上传失败。' }
  finally { busy.value = false }
}

async function inspect(version: KnowledgeVersion) {
  current.value = version
  error.value = ''
  try { remember(await knowledgeApi.version(version.versionId)) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '读取知识版本失败。' }
}

async function refreshCurrent() {
  if (!current.value) return
  await inspect(current.value)
}

async function retry() {
  if (!current.value || !canRetry.value || retryBusy.value) return
  retryBusy.value = true
  error.value = ''
  notice.value = ''
  try {
    let version = await knowledgeApi.retry(current.value.versionId)
    remember(version)
    notice.value = '已重新加入处理队列，正在刷新入库状态。'
    for (let attempt = 0; attempt < 30 && ['UPLOADED', 'PROCESSING'].includes(version.status); attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 1500))
      version = await knowledgeApi.version(version.versionId)
      remember(version)
    }
    notice.value = version.status === 'READY' ? '重试完成，文档已可发布。'
      : version.status === 'FAILED' ? '重试后仍未处理成功，请查看失败原因。' : '文档仍在处理中，可稍后刷新状态。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重试入库失败。' }
  finally { retryBusy.value = false }
}

async function publish() {
  if (!current.value || !canPublish.value) return
  busy.value = true
  error.value = ''
  try {
    await knowledgeApi.publish(current.value.versionId)
    current.value = { ...current.value, status: 'PUBLISHED' }
    remember(current.value)
    notice.value = '知识版本已发布，新的政策检索将使用此版本。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '发布失败。' }
  finally { busy.value = false }
}

async function searchPolicy() {
  if (!query.value.trim()) return
  searchBusy.value = true
  error.value = ''
  try { chunks.value = (await knowledgeApi.search(query.value.trim())).chunks }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '政策检索失败。' }
  finally { searchBusy.value = false }
}
</script>

<template>
  <section class="knowledge-page">
    <header class="page-heading records-heading"><div><span class="eyebrow">KNOWLEDGE / POLICY CONTROL</span><h1>政策知识库</h1><p>上传政策文件，检查处理状态和生效区间，再手动发布。</p></div><span class="role-chip">{{ auth.role === 'ADMIN' ? '管理员权限' : '只读' }}</span></header>
    <div v-if="error" class="inline-alert" role="alert">{{ error }}<button class="alert-close" @click="error = ''">×</button></div><div v-if="notice" class="inline-alert inline-alert--success" role="status">{{ notice }}<button class="alert-close" @click="notice = ''">×</button></div>
    <div class="knowledge-grid"><div class="knowledge-main">
      <article class="content-card upload-card"><div class="content-card__heading"><div><span class="eyebrow">NEW DOCUMENT / ADMIN</span><h2>上传政策文件</h2></div><span class="file-support">PDF · TXT · MD</span></div><form @submit.prevent="upload"><div class="form-grid"><label class="field"><span>文档标题</span><input v-model="title" required maxlength="250" placeholder="售后退换货政策" /></label><label class="field"><span>政策类别编码</span><input v-model="categoryCode" required maxlength="64" placeholder="RETURN_REFUND" /></label><label class="field"><span>生效开始时间</span><input v-model="effectiveFrom" type="datetime-local" required /></label><label class="field"><span>政策文件</span><input type="file" accept=".pdf,.txt,.md,application/pdf,text/plain,text/markdown" required @change="file = ($event.target as HTMLInputElement).files?.[0] ?? null" /></label></div><div class="upload-card__foot"><span>单文件最大 10 MB · 扫描 PDF 暂不支持 OCR</span><button class="button button--primary" :disabled="busy || !file"><NavIcon name="upload" :size="17" />{{ busy ? '正在处理…' : '上传并校验' }}</button></div></form></article>
      <article class="content-card version-card"><div class="content-card__heading"><div><span class="eyebrow">VERSION REVIEW</span><h2>版本检查</h2></div><button v-if="current" class="button button--secondary button--small" @click="refreshCurrent"><NavIcon name="search" :size="15" />刷新状态</button></div><div v-if="current" class="version-detail"><div class="version-detail__title"><span class="document-icon"><NavIcon name="knowledge" :size="19" /></span><div><strong>{{ current.title }}</strong><small>{{ current.categoryCode }} · 第 {{ current.versionNo }} 版</small></div><StatusBadge :status="current.status" /></div><div class="version-flow"><div v-for="step in ['UPLOADED', 'PROCESSING', 'READY']" :key="step" class="version-flow__step" :class="{ 'is-current': current.status === step, 'is-done': ['UPLOADED', 'PROCESSING', 'READY', 'PUBLISHED'].indexOf(current.status) > ['UPLOADED', 'PROCESSING', 'READY'].indexOf(step) || current.status === 'PUBLISHED' }"><span></span><strong>{{ step === 'UPLOADED' ? '已上传' : step === 'PROCESSING' ? '文本处理中' : '待发布' }}</strong></div></div><dl class="detail-facts detail-facts--horizontal"><div><dt>生效日期</dt><dd>{{ new Date(current.effectiveFrom).toLocaleString('zh-CN') }}</dd></div><div><dt>生效结束</dt><dd>{{ current.effectiveTo ? new Date(current.effectiveTo).toLocaleString('zh-CN') : '未设置' }}</dd></div><div><dt>版本 ID</dt><dd class="mono-value">{{ current.versionId }}</dd></div></dl><div v-if="current.failureReason" class="execution-error">处理失败原因：{{ current.failureReason }}</div><div v-if="['UPLOADED', 'PROCESSING'].includes(current.status)" class="processing-note"><span class="spinner" />后台正在处理文件内容和检索索引。</div><div v-if="current.status === 'FAILED'" class="file-warning">文件未能完成文本提取。扫描版 PDF 不包含可检索文本时，请上传文本版文件。</div><button v-if="canRetry" class="button button--secondary" :disabled="retryBusy" @click="retry">{{ retryBusy ? '重试中…' : '重新处理' }}<NavIcon name="arrow" :size="15" /></button><button v-if="canPublish" class="button button--primary" :disabled="busy" @click="publish">确认发布政策版本 <NavIcon name="arrow" :size="15" /></button></div><div v-else class="subtle-empty">上传或从右侧最近版本中选择文档，查看生效日期与处理状态。</div></article>
      <article class="content-card retrieval-card"><div class="content-card__heading"><div><span class="eyebrow">RETRIEVAL PREVIEW</span><h2>政策检索预览</h2></div><span class="file-support">只检索已发布版本</span></div><form class="retrieval-form" @submit.prevent="searchPolicy"><input v-model="query" aria-label="政策检索内容" placeholder="输入售后政策问题" /><button class="button button--secondary" :disabled="searchBusy">{{ searchBusy ? '检索中…' : '检索依据' }}<NavIcon name="arrow" :size="15" /></button></form><div v-if="chunks.length" class="retrieval-results"><article v-for="chunk in chunks" :key="chunk.chunkId" class="retrieval-chunk"><div><strong>{{ chunk.documentTitle }}</strong><span>第 {{ chunk.versionNo }} 版{{ chunk.pageNo ? ` · 第 ${chunk.pageNo} 页` : '' }}</span></div><p>{{ chunk.content }}</p></article></div><p v-else class="retrieval-hint">检索结果将展示命中的政策标题、版本和原文片段。</p></article>
    </div><aside class="knowledge-side content-card"><div class="content-card__heading"><div><span class="eyebrow">THIS SESSION</span><h2>最近版本</h2></div><span class="card-count">{{ versions.length.toString().padStart(2, '0') }}</span></div><button v-for="version in versions" :key="version.versionId" class="version-list-item" :class="{ 'is-selected': current?.versionId === version.versionId }" @click="inspect(version)"><span class="document-icon"><NavIcon name="knowledge" :size="17" /></span><span class="version-list-item__copy"><strong>{{ version.title }}</strong><small>v{{ version.versionNo }} · {{ version.categoryCode }}</small></span><StatusBadge :status="version.status" /></button><p v-if="!versions.length" class="subtle-empty">上传的政策版本会在这里保留到本次登录结束。版本列表 API 尚未提供全量归档检索。</p><div class="knowledge-side__note"><span class="eyebrow">发布规则</span><p>新版本需要明确生效时间。发布前请确认文件内容与适用区间，Agent 只引用已发布政策。</p></div></aside></div>
  </section>
</template>
