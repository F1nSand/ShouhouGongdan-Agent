<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import NavIcon from './NavIcon.vue'
import { useAuthStore } from '../stores/auth'
import { useConversationStore } from '../stores/conversation'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const conversation = useConversationStore()
const roleNames: Record<string, string> = {
  CUSTOMER_SERVICE: '客服专员', SERVICE_SUPERVISOR: '服务主管',
  WAREHOUSE_OPERATOR: '仓库运营', ADMIN: '系统管理员',
}
const links = computed(() => [
  { label: '智能工作台', to: '/workspace', icon: 'workspace', show: true },
  { label: '售后工单', to: '/tickets', icon: 'tickets', show: true },
  { label: '库存调度', to: '/inventory', icon: 'inventory', show: true },
  { label: '人工审批', to: '/approvals', icon: 'approvals', show: auth.canSeeApprovals() },
  { label: '政策知识库', to: '/knowledge', icon: 'knowledge', show: auth.role === 'ADMIN' },
  { label: '操作审计', to: '/audit', icon: 'audit', show: auth.role === 'ADMIN' },
].filter((item) => item.show))
const pageTitle = computed(() => String(route.meta.title ?? '售后工作台'))

function logout() {
  conversation.reset()
  auth.logout()
  void router.push('/login')
}
</script>

<template>
  <div class="app-frame">
    <aside class="sidebar" aria-label="主导航">
      <RouterLink class="brand" to="/workspace" aria-label="售后工作台首页">
        <span class="brand__mark"><span></span><span></span><span></span></span>
        <span class="brand__text"><strong>织云</strong><small>售后运营系统</small></span>
      </RouterLink>
      <div class="sidebar__section-label">工作区</div>
      <nav class="main-nav">
        <RouterLink v-for="link in links" :key="link.to" :to="link.to" class="main-nav__link" :class="{ 'is-active': route.path === link.to || (link.to === '/tickets' && route.path.startsWith('/tickets/')) }">
          <NavIcon :name="link.icon" />
          <span>{{ link.label }}</span>
          <span v-if="route.path === link.to" class="main-nav__active-mark"></span>
        </RouterLink>
      </nav>
      <div class="sidebar__bottom">
        <div class="sidebar__service"><span class="service-led"></span><span>业务服务正常</span><span class="sidebar__service-dot">·</span></div>
        <div class="profile-row">
          <div class="profile-avatar">{{ roleNames[auth.role ?? '']?.slice(0, 1) ?? '用' }}</div>
          <div class="profile-copy"><strong>{{ roleNames[auth.role ?? ''] ?? '内部用户' }}</strong><small>{{ auth.role ?? '' }}</small></div>
          <button class="icon-button icon-button--quiet" aria-label="退出登录" title="退出登录" @click="logout"><NavIcon name="logout" :size="18" /></button>
        </div>
      </div>
    </aside>

    <div class="app-main">
      <header class="topbar">
        <div class="topbar__breadcrumb"><span>售后运营</span><span class="breadcrumb-divider">/</span><strong>{{ pageTitle }}</strong></div>
        <div class="topbar__right"><span class="topbar__tenant"><span class="tenant-icon">商</span>演示商家</span><span class="topbar__date">{{ new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }) }}</span></div>
      </header>
      <main class="page-content"><RouterView /></main>
    </div>

    <nav class="mobile-nav" aria-label="移动端导航">
      <RouterLink v-for="link in links.slice(0, 5)" :key="link.to" :to="link.to" :aria-current="route.path === link.to ? 'page' : undefined">
        <NavIcon :name="link.icon" :size="19" /><span>{{ link.label.replace('智能', '') }}</span>
      </RouterLink>
    </nav>
  </div>
</template>
