import type { RunEvent } from '../domain/display'
import { ApiError, http } from './http'

export interface Conversation {
  id: string
  conversationId?: string
  title: string
  status: string
  createdAt: string
  updatedAt?: string
  lastMessage?: string
}

export interface ConversationPage {
  items: Conversation[]
  page: number
  size: number
  total: number
}

export interface ChatMessage {
  id: string
  runId?: string
  role: 'USER' | 'ASSISTANT' | string
  content: string
  structuredAnswer?: {
    answer?: string
    citationIds?: string[]
    draftIds?: string[]
    citations?: Citation[]
    suggestedActions?: Array<{ draftId: string; type: string; summary: string }>
    actionResult?: { ticketId?: string; ticketNo?: string; actionId?: string; status?: string }
  }
  createdAt: string
}

export interface Citation {
  citationId?: string
  documentTitle: string
  version?: number
  versionNo?: number
  page?: number
  pageNo?: number
  quote?: string
  content?: string
}

export interface AgentRun {
  runId: string
  conversationId: string
  status: string
  deadlineAt: string
  errorCode?: string
  structuredAnswer?: Record<string, unknown>
  eventsUrl: string
}

const terminalEvents = new Set(['answer.final', 'run.failed', 'run.cancelled'])

export const agentApi = {
  conversations: () => http.get<ConversationPage>('/conversations?page=1&size=50'),
  createConversation: (title?: string) => http.post<Conversation>('/conversations', { title }),
  messages: (id: string) => http.get<ChatMessage[]>(`/conversations/${id}/messages?limit=100`),
  startRun: (id: string, message: string, requestId = crypto.randomUUID()) =>
    http.post<AgentRun>(`/conversations/${id}/runs`, { requestId, message }),
  cancelRun: (id: string) => http.post<AgentRun>(`/agent-runs/${id}/cancel`),
  streamEvents: (runId: string, afterSequence: number, onEvent: (event: RunEvent) => void, signal: AbortSignal) =>
    streamEvents(runId, afterSequence, onEvent, signal),
}

async function streamEvents(
  runId: string,
  initialSequence: number,
  onEvent: (event: RunEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  let cursor = initialSequence
  let retry = 0

  while (!signal.aborted) {
    const token = sessionStorage.getItem('aftercare.session')
    const accessToken = token ? (JSON.parse(token) as { accessToken?: string }).accessToken : undefined
    const headers = new Headers({ Accept: 'text/event-stream' })
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    headers.set('Last-Event-ID', String(cursor))

    try {
      const response = await fetch(`/api/v1/agent-runs/${runId}/events`, { headers, signal })
      if (response.status === 401) {
        window.dispatchEvent(new CustomEvent('auth:expired'))
        throw new ApiError('登录已过期，请重新登录', 401, 'UNAUTHENTICATED')
      }
      if (!response.ok || !response.body) throw new ApiError('实时进度连接失败', response.status, 'SSE_ERROR')

      retry = 0
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let terminal = false
      while (!signal.aborted && !terminal) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const frames = buffer.split(/\r?\n\r?\n/)
        buffer = frames.pop() ?? ''
        for (const frame of frames) {
          const event = parseFrame(frame)
          if (!event || event.sequence <= cursor) continue
          cursor = event.sequence
          onEvent(event)
          if (terminalEvents.has(event.eventType)) terminal = true
        }
      }
      await reader.cancel().catch(() => undefined)
      if (terminal || signal.aborted) return
    } catch (error) {
      if (signal.aborted) return
      if (error instanceof ApiError && error.status === 401) throw error
    }

    retry += 1
    await delay(Math.min(800 * 2 ** Math.min(retry - 1, 4), 10_000), signal)
  }
}

function parseFrame(frame: string): RunEvent | undefined {
  const data = frame.split(/\r?\n/).filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart()).join('\n')
  if (!data) return undefined
  try {
    return JSON.parse(data) as RunEvent
  } catch {
    return undefined
  }
}

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve()
    const timer = window.setTimeout(done, milliseconds)
    signal.addEventListener('abort', done, { once: true })
    function done() {
      window.clearTimeout(timer)
      signal.removeEventListener('abort', done)
      resolve()
    }
  })
}
