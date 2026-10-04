import { http, idempotencyHeaders } from './http'

export interface TicketEvent {
  id: string
  eventType: string
  fromStatus?: string
  toStatus?: string
  comment?: string
  createdAt: string
}

export interface Ticket {
  id: string
  ticketNo: string
  orderId?: string
  category: string
  title: string
  description: string
  priority: string
  status: string
  version: number
  assignedTo?: string
  resolution?: string
  createdAt: string
  updatedAt: string
  events: TicketEvent[]
  evidenceSnapshot?: unknown
}

export interface TicketPage {
  items: Ticket[]
  page: number
  size: number
  total: number
}

export interface Draft {
  id: string
  runId?: string
  type: string
  origin: string
  payload: Record<string, unknown>
  evidenceSnapshot?: unknown
  status: string
  expiresAt: string
}

export interface ConfirmedActionResult {
  type: string
  ticketId?: string
  ticketNo?: string
  sensitiveActionId?: string
  status: string
  simulation?: boolean
}

export const ticketsApi = {
  list: (filters: { status?: string; orderId?: string; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams()
    Object.entries(filters).forEach(([key, value]) => value && query.set(key, String(value)))
    return http.get<TicketPage>(`/tickets${query.size ? `?${query}` : ''}`)
  },
  get: (ticketId: string) => http.get<Ticket>(`/tickets/${ticketId}`),
  assign: (ticket: Ticket, assigneeId: string) =>
    http.post<Ticket>(`/tickets/${ticket.id}/assign`, { assigneeId, expectedVersion: ticket.version }),
  transition: (ticket: Ticket, toStatus: string, comment = '') =>
    http.post<Ticket>(`/tickets/${ticket.id}/transition`, {
      toStatus, comment, expectedVersion: ticket.version,
    }),
  createManualDraft: (typedPayload: {
    orderId?: string; category: string; title: string; description: string; priority: string
  }) => http.post<Draft>('/action-drafts', { type: 'CREATE_TICKET', typedPayload }, idempotencyHeaders()),
  confirmDraft: (draftId: string, key: string) =>
    http.post<ConfirmedActionResult>(`/action-drafts/${draftId}/confirm`, undefined, idempotencyHeaders(key)),
}
