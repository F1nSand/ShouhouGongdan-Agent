import { http, idempotencyHeaders } from './http'

export interface SensitiveAction {
  id: string
  ticketId?: string
  type: string
  payload: Record<string, unknown>
  evidenceSnapshot?: unknown
  status: string
  expiresAt: string
  version: number
  requestedBy: string
  executionResult?: Record<string, unknown>
  errorCode?: string
}

export const approvalsApi = {
  list: (page = 1, size = 50) => http.get<SensitiveAction[]>(`/sensitive-actions?page=${page}&size=${size}`),
  get: (id: string) => http.get<SensitiveAction>(`/sensitive-actions/${id}`),
  decide: (action: SensitiveAction, decision: 'APPROVE' | 'REJECT', reason: string) =>
    http.post(`/sensitive-actions/${action.id}/decision`, {
      decision, reason, expectedVersion: action.version,
    }, idempotencyHeaders()),
}
