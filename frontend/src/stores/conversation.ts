import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { agentApi, type ChatMessage, type Conversation } from '../api/agent'
import { http } from '../api/http'
import type { Draft } from '../api/tickets'
import { ticketsApi } from '../api/tickets'
import { ordersApi, type Logistics, type Order } from '../api/orders'
import { appendUniqueRunEvent, modelBudgetErrorMessage, type RunEvent } from '../domain/display'

function asConversation(item: Conversation) {
  return { ...item, id: item.id ?? item.conversationId ?? '' }
}

export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref<Conversation[]>([])
  const activeConversationId = ref('')
  const messages = ref<ChatMessage[]>([])
  const events = ref<RunEvent[]>([])
  const drafts = ref<Draft[]>([])
  const streamText = ref('')
  const busy = ref(false)
  const error = ref('')
  const activeRunId = ref('')
  const activeOrder = ref<Order | null>(null)
  const activeLogistics = ref<Logistics | null>(null)
  let streamController: AbortController | undefined

  const activeConversation = computed(() => conversations.value.find((item) => item.id === activeConversationId.value))
  const orderedEvents = computed(() => events.value)

  async function loadConversations() {
    const page = await agentApi.conversations()
    conversations.value = page.items.map(asConversation)
    if (!activeConversationId.value && conversations.value.length) {
      await openConversation(conversations.value[0].id)
    }
  }

  async function newConversation() {
    const created = asConversation(await agentApi.createConversation('售后咨询'))
    conversations.value.unshift(created)
    await openConversation(created.id)
  }

  async function openConversation(id: string) {
    streamController?.abort()
    activeConversationId.value = id
    messages.value = await agentApi.messages(id)
    events.value = []
    drafts.value = []
    streamText.value = ''
    busy.value = false
    error.value = ''
    await restoreDraftCards()
  }

  async function sendMessage(text: string) {
    const id = activeConversationId.value
    if (!id || busy.value || !text.trim()) return
    busy.value = true
    error.value = ''
    streamText.value = ''
    events.value = []
    messages.value.push({
      id: crypto.randomUUID(), role: 'USER', content: text.trim(), createdAt: new Date().toISOString(),
    })
    const orderNo = text.match(/\b[A-Z]{2}\d{8,}\b/)?.[0]
    activeOrder.value = null
    activeLogistics.value = null
    try {
      if (orderNo) {
        try {
          activeOrder.value = await ordersApi.getByNumber(orderNo)
          activeLogistics.value = await ordersApi.logistics(activeOrder.value.orderId).catch(() => null)
        } catch {
          activeOrder.value = null
        }
      }
      const run = await agentApi.startRun(id, text.trim())
      activeRunId.value = run.runId
      streamController = new AbortController()
      await agentApi.streamEvents(run.runId, 0, (event) => void receiveEvent(event), streamController.signal)
      await refreshMessages(id)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '请求失败，请稍后重试。'
      await refreshMessages(id).catch(() => undefined)
    } finally {
      busy.value = false
      activeRunId.value = ''
      streamController = undefined
    }
  }

  async function receiveEvent(event: RunEvent) {
    events.value = appendUniqueRunEvent(events.value, event)
    const payload = event.payload ?? {}
    if (event.eventType === 'run.failed') {
      error.value = modelBudgetErrorMessage(String(payload.errorCode ?? ''))
        ?? '本次处理未完成，请检查服务状态后重试。'
    }
    if (event.eventType === 'answer.delta') streamText.value = String(payload.text ?? '')
    if (event.eventType === 'draft.created' && payload.draftId) {
      try {
        const draft = await http.get<Draft>(`/action-drafts/${String(payload.draftId)}`)
        drafts.value = [...drafts.value.filter((item) => item.id !== draft.id), draft]
      } catch (cause) {
        error.value = cause instanceof Error ? cause.message : '读取操作草稿失败。'
      }
    }
    if (event.eventType === 'action.result') await refreshMessages(activeConversationId.value)
  }

  async function refreshMessages(id = activeConversationId.value) {
    if (id) {
      messages.value = await agentApi.messages(id)
      await restoreDraftCards()
    }
  }

  async function restoreDraftCards() {
    const ids = [...new Set(messages.value.flatMap((message) =>
      message.structuredAnswer?.draftIds ?? message.structuredAnswer?.suggestedActions?.map((item) => item.draftId) ?? []))]
    drafts.value = await Promise.all(ids.map((id) => http.get<Draft>(`/action-drafts/${id}`).catch(() => null)))
      .then((items) => items.filter((item): item is Draft => item !== null))
  }

  async function confirmDraft(draftId: string) {
    const key = `confirm:${draftId}`
    const result = await ticketsApi.confirmDraft(draftId, key)
    drafts.value = drafts.value.map((draft) => draft.id === draftId ? { ...draft, status: 'CONFIRMED' } : draft)
    await refreshMessages()
    return result
  }

  async function cancelRun() {
    if (!activeRunId.value) return
    await agentApi.cancelRun(activeRunId.value)
    streamController?.abort()
    busy.value = false
  }

  function reset() {
    streamController?.abort()
    conversations.value = []
    activeConversationId.value = ''
    messages.value = []
    events.value = []
    drafts.value = []
    streamText.value = ''
    busy.value = false
    error.value = ''
    activeRunId.value = ''
    activeOrder.value = null
    activeLogistics.value = null
  }

  return {
    conversations, activeConversationId, activeConversation, messages, events, orderedEvents,
    drafts, streamText, busy, error, activeRunId, activeOrder, activeLogistics,
    loadConversations, newConversation, openConversation, sendMessage,
    confirmDraft, cancelRun, refreshMessages, reset,
  }
})
