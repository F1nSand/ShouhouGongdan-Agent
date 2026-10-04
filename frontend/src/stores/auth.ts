import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi, type AuthIdentity, type LoginResult } from '../api/auth'

const STORAGE_KEY = 'aftercare.session'

export const useAuthStore = defineStore('auth', () => {
  const identity = ref<AuthIdentity | null>(null)
  const accessToken = ref('')
  const expiresAt = ref('')
  const isAuthenticated = computed(() => Boolean(accessToken.value && identity.value))
  const role = computed(() => identity.value?.role ?? null)

  function save(result: LoginResult) {
    accessToken.value = result.accessToken
    expiresAt.value = result.expiresAt
    identity.value = { tenantId: result.tenantId, actorId: result.actorId, role: result.role }
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(result))
  }

  async function login(merchantCode: string, username: string, password: string) {
    save(await authApi.login(merchantCode, username, password))
  }

  async function restore() {
    const stored = sessionStorage.getItem(STORAGE_KEY)
    if (!stored) return false
    try {
      const parsed = JSON.parse(stored) as LoginResult
      if (!parsed.accessToken || new Date(parsed.expiresAt) <= new Date()) {
        logout()
        return false
      }
      save(parsed)
      const current = await authApi.me()
      identity.value = current
      return true
    } catch {
      logout()
      return false
    }
  }

  function logout() {
    accessToken.value = ''
    expiresAt.value = ''
    identity.value = null
    sessionStorage.removeItem(STORAGE_KEY)
  }

  function canSeeApprovals() {
    return ['SERVICE_SUPERVISOR', 'WAREHOUSE_OPERATOR', 'ADMIN'].includes(role.value ?? '')
  }

  function canDecide(type: string, requestedBy?: string) {
    const approver = ['STOCK_TRANSFER', 'REQUEST_STOCK_TRANSFER'].includes(type)
      ? ['WAREHOUSE_OPERATOR', 'ADMIN'].includes(role.value ?? '')
      : ['SERVICE_SUPERVISOR', 'ADMIN'].includes(role.value ?? '')
    return approver && requestedBy !== identity.value?.actorId
  }

  return { identity, accessToken, expiresAt, isAuthenticated, role, login, restore, logout, canSeeApprovals, canDecide }
})
