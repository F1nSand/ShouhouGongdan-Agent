import { createRouter, createWebHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import AppShell from '../components/AppShell.vue'
import { useAuthStore } from '../stores/auth'

export function createAppRouter(pinia: Pinia) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true, title: '登录' } },
      {
        path: '/', component: AppShell, redirect: '/workspace', children: [
          { path: 'workspace', name: 'workspace', component: () => import('../views/WorkspaceView.vue'), meta: { title: '智能工作台' } },
          { path: 'tickets', name: 'tickets', component: () => import('../views/TicketListView.vue'), meta: { title: '售后工单' } },
          { path: 'tickets/:id', name: 'ticket-detail', component: () => import('../views/TicketDetailView.vue'), meta: { title: '工单详情' } },
          { path: 'inventory', name: 'inventory', component: () => import('../views/InventoryView.vue'), meta: { title: '库存调度' } },
          { path: 'approvals', name: 'approvals', component: () => import('../views/ApprovalView.vue'), meta: { title: '人工审批', roles: ['SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN'] } },
          { path: 'knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue'), meta: { title: '政策知识库', roles: ['ADMIN'] } },
          { path: 'audit', name: 'audit', component: () => import('../views/AuditView.vue'), meta: { title: '操作审计', roles: ['ADMIN'] } },
        ],
      },
      { path: '/:pathMatch(.*)*', redirect: '/workspace' },
    ],
  })

  router.beforeEach(async (to) => {
    const auth = useAuthStore(pinia)
    if (to.meta.public) {
      if (!auth.isAuthenticated) await auth.restore()
      return auth.isAuthenticated ? '/workspace' : true
    }
    if (!auth.isAuthenticated && !(await auth.restore())) return { name: 'login', query: { next: to.fullPath } }
    const roles = to.meta.roles as string[] | undefined
    if (roles && !roles.includes(auth.role ?? '')) return { name: 'workspace' }
    return true
  })

  return router
}
