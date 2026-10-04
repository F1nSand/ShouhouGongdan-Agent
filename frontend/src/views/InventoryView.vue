<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { inventoryApi, type InventorySnapshot, type TransferRecommendation } from '../api/inventory'
import NavIcon from '../components/NavIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatCents } from '../domain/display'

const router = useRouter()
const skuCode = ref('SKU-001')
const targetWarehouseId = ref('')
const quantity = ref(1)
const inventory = ref<InventorySnapshot | null>(null)
const recommendation = ref<TransferRecommendation | null>(null)
const loading = ref(false)
const error = ref('')
const sourceName = (id: string) => inventory.value?.warehouses.find((warehouse) => warehouse.warehouseId === id)?.warehouseName ?? id
const targetWarehouse = computed(() => inventory.value?.warehouses.find((warehouse) => warehouse.warehouseId === targetWarehouseId.value))

async function loadInventory() {
  if (!skuCode.value.trim()) return
  loading.value = true
  error.value = ''
  recommendation.value = null
  try {
    inventory.value = await inventoryApi.get(skuCode.value.trim())
    if (!targetWarehouseId.value || !inventory.value.warehouses.some((item) => item.warehouseId === targetWarehouseId.value)) {
      targetWarehouseId.value = inventory.value.warehouses.at(-1)?.warehouseId ?? ''
    }
  } catch (cause) {
    inventory.value = null
    error.value = cause instanceof Error ? cause.message : '库存信息暂不可用。'
  } finally { loading.value = false }
}

async function recommend() {
  if (!inventory.value || !targetWarehouseId.value || quantity.value < 1) return
  loading.value = true
  error.value = ''
  try { recommendation.value = await inventoryApi.recommend(inventory.value.skuId, targetWarehouseId.value, quantity.value) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法生成调拨建议。' }
  finally { loading.value = false }
}

function prepareApplication(candidate: TransferRecommendation['candidates'][number]) {
  if (!inventory.value || !targetWarehouse.value) return
  const prompt = `请为 ${inventory.value.skuCode} 生成调拨建议并准备提交调拨审批草稿。商品ID ${inventory.value.skuId}，目标仓库ID ${targetWarehouseId.value}，建议来源仓库ID ${candidate.sourceWarehouseId}，数量 ${quantity.value} 件。`
  void router.push({ path: '/workspace', query: { prompt } })
}
</script>

<template>
  <section class="inventory-page">
    <header class="page-heading records-heading"><div><span class="eyebrow">INVENTORY / REPLENISHMENT</span><h1>库存调度</h1><p>查看各仓可调配库存，并根据安全库存约束生成调拨建议。</p></div><span class="updated-at" v-if="inventory">计算于 {{ new Date(inventory.calculatedAt).toLocaleString('zh-CN') }}</span></header>
    <div class="inventory-query"><div class="inventory-query__label"><span class="eyebrow">SKU 查询</span><strong>查询商品库存</strong></div><form class="inventory-query__form" @submit.prevent="loadInventory"><label class="field field--inline"><span>商品编码</span><input v-model="skuCode" aria-label="商品编码" placeholder="SKU-001" required /></label><button class="button button--primary" :disabled="loading"><NavIcon name="search" :size="17" />{{ loading ? '查询中…' : '查询库存' }}</button></form></div>
    <div v-if="error" class="inline-alert" role="alert">{{ error }}</div>
    <template v-if="inventory">
      <div class="inventory-product-heading"><div class="product-large-icon"><NavIcon name="box" :size="23" /></div><div><span class="eyebrow">PRODUCT / {{ inventory.skuCode }}</span><h2>{{ inventory.skuName }}</h2></div><span class="warehouse-count">{{ inventory.warehouses.length }} 个仓库</span></div>
      <div class="stock-grid"><article v-for="warehouse in inventory.warehouses" :key="warehouse.warehouseId" class="stock-card"><div class="stock-card__top"><span class="warehouse-code">{{ warehouse.warehouseCode }}</span><span class="stock-card__warehouse">{{ warehouse.warehouseName }}</span></div><div class="stock-card__amount">{{ warehouse.available }}<small>件可用</small></div><div class="stock-meter"><span :style="{ width: `${Math.max(4, Math.min(100, warehouse.allocatable / Math.max(warehouse.onHand, 1) * 100))}%` }"></span></div><dl class="stock-card__facts"><div><dt>实存</dt><dd>{{ warehouse.onHand }}</dd></div><div><dt>预留</dt><dd>{{ warehouse.reserved }}</dd></div><div><dt>安全库存</dt><dd>{{ warehouse.safetyStock }}</dd></div><div><dt>可调配</dt><dd>{{ warehouse.allocatable }}</dd></div></dl><footer>库存版本 {{ warehouse.inventoryVersion }}</footer></article></div>
      <section class="recommendation-panel"><div class="recommendation-panel__heading"><div><span class="eyebrow">TRANSFER PLANNER</span><h2>生成调拨建议</h2><p>系统按预计时效与成本排序候选；建议不会预留或扣减库存。</p></div><div class="recommendation-stamp"><span>规则校验</span><strong>安全库存保护</strong></div></div><form class="recommendation-form" @submit.prevent="recommend"><label class="field"><span>目标仓库</span><select v-model="targetWarehouseId" required><option v-for="warehouse in inventory.warehouses" :key="warehouse.warehouseId" :value="warehouse.warehouseId">{{ warehouse.warehouseName }} · {{ warehouse.warehouseCode }}</option></select></label><label class="field"><span>申请数量</span><input v-model.number="quantity" type="number" min="1" max="100000" required /></label><button class="button button--primary" :disabled="loading || !targetWarehouseId">{{ loading ? '计算中…' : '生成建议' }}<NavIcon name="arrow" :size="16" /></button></form>
        <div v-if="recommendation" class="recommendation-results"><div class="results-heading"><strong>建议来源仓</strong><span>最多展示 3 个单源方案 · 不跨仓拆分</span></div><div v-if="!recommendation.candidates.length" class="no-candidates"><span class="no-candidates__mark">!</span><div><strong>当前没有满足安全库存的单源方案</strong><p>可减少申请数量、调整目标仓库，或等待库存补充。</p></div></div><article v-for="(candidate, index) in recommendation.candidates" :key="candidate.sourceWarehouseId" class="candidate-row"><span class="candidate-rank">0{{ index + 1 }}</span><div class="candidate-path"><strong>{{ sourceName(candidate.sourceWarehouseId) }}</strong><NavIcon name="arrow" :size="16" /><strong>{{ targetWarehouse?.warehouseName }}</strong><small>可调配余量 {{ candidate.allocatable }} 件 · 建议运输 {{ candidate.estimatedDays }} 天</small></div><div class="candidate-cost"><span>预估单件成本</span><strong>{{ formatCents(candidate.costCentsPerUnit) }}</strong></div><button class="button button--secondary button--small" @click="prepareApplication(candidate)">生成申请草稿 <NavIcon name="arrow" :size="14" /></button></article></div>
      </section>
    </template>
    <section v-else-if="!loading" class="inventory-empty"><div class="inventory-empty__graphic"><span></span><span></span><span></span></div><span class="eyebrow">INVENTORY VISIBILITY</span><h2>从商品编码开始</h2><p>查询后查看实存、预留与安全库存。建议结果仅用于辅助决策。</p><button class="button button--secondary" @click="loadInventory">查询演示商品</button></section>
    <div v-if="loading && !inventory" class="table-loading"><span class="spinner" />正在查询商品库存…</div>
    <footer v-if="inventory" class="records-foot"><span><span class="service-led"></span>查询结果为当前计算时点快照</span><span>库存申请须经仓库角色审批</span></footer>
  </section>
</template>
