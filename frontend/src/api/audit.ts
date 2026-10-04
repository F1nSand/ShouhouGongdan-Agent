import { http } from './http'

export interface AuditEvent {
  id: string
  actorId?: string
  actorRole?: string
  traceId: string
  action: string
  resourceType: string
  resourceId?: string
  result: string
  parameterSummary: Record<string, unknown>
  reason?: string
  createdAt: string
}

export interface AuditPage {
  items: AuditEvent[]
  page: number
  size: number
  total: number
}

export const auditApi = {
  list: (filters: { traceId?: string; resourceId?: string; action?: string; from?: string; to?: string; page?: number; size?: number }) => {
    const query = new URLSearchParams()
    Object.entries(filters).forEach(([key, value]) => value && query.set(key, String(value)))
    return http.get<AuditPage>('/audit-events?' + query.toString())
  },
}
