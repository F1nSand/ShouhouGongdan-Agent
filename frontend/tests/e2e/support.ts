import type { Page, Route } from '@playwright/test'

const ids = {
  order: '11111111-1111-4111-8111-111111111111',
  sku: '22222222-2222-4222-8222-222222222222',
  east: '33333333-3333-4333-8333-333333333333',
  central: '44444444-4444-4444-8444-444444444444',
  south: '55555555-5555-4555-8555-555555555555',
  ticket: '66666666-6666-4666-8666-666666666666',
}

export async function mockApi(page: Page) {
  const state: {
    username: string
    runCount: number
    currentRunId: string
    currentEvents: string
    messages: Array<Record<string, unknown>>
    drafts: Map<string, Record<string, unknown>>
    tickets: Array<Record<string, unknown>>
    actions: Array<Record<string, unknown>>
  } = {
    username: 'csr',
    runCount: 0,
    currentRunId: '',
    currentEvents: '',
    messages: [],
    drafts: new Map(),
    tickets: [],
    actions: [],
  }

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    const method = request.method()
    const body = request.postDataJSON() as Record<string, any> | undefined
    const ok = (data: unknown, status = 200) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'OK', data, traceId: 'e2e-trace' }),
    })

    if (path === '/auth/login' && method === 'POST') {
      state.username = String(body?.username ?? 'csr')
      const role = state.username === 'supervisor' ? 'SERVICE_SUPERVISOR'
        : state.username === 'warehouse' ? 'WAREHOUSE_OPERATOR'
          : state.username === 'admin' ? 'ADMIN' : 'CUSTOMER_SERVICE'
      return ok({ accessToken: 'e2e-token', tokenType: 'Bearer', expiresAt: new Date(Date.now() + 3600000).toISOString(), tenantId: 'tenant-e2e', actorId: state.username + '-actor', role })
    }
    if (path === '/auth/me') {
      const role = state.username === 'supervisor' ? 'SERVICE_SUPERVISOR'
        : state.username === 'warehouse' ? 'WAREHOUSE_OPERATOR'
          : state.username === 'admin' ? 'ADMIN' : 'CUSTOMER_SERVICE'
      return ok({ tenantId: 'tenant-e2e', actorId: state.username + '-actor', role })
    }
    if (path === '/conversations' && method === 'GET') return ok({ items: [], page: 1, size: 50, total: 0 })
    if (path === '/conversations' && method === 'POST') {
      return ok({ conversationId: 'conversation-e2e', title: body?.title ?? '售后咨询', status: 'ACTIVE', createdAt: new Date().toISOString() }, 201)
    }
    if (path === '/conversations/conversation-e2e/messages') return ok(state.messages)
    if (path === '/conversations/conversation-e2e/runs' && method === 'POST') {
      state.runCount += 1
      const message = String(body?.message ?? '')
      const transfer = message.includes('调拨')
      const refund = !transfer && message.includes('退款')
      const draftId = transfer ? 'draft-stock' : refund ? 'draft-refund' : 'draft-ticket'
      const runId = 'run-' + state.runCount
      state.currentRunId = runId
      const uuidValues = [...message.matchAll(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi)].map((item) => item[0])
      const type = transfer ? 'REQUEST_STOCK_TRANSFER' : refund ? 'REQUEST_REFUND' : 'CREATE_TICKET'
      const payload = transfer
        ? { skuId: uuidValues[0] ?? ids.sku, targetWarehouseId: uuidValues[1] ?? ids.south, sourceWarehouseId: uuidValues[2] ?? ids.east, quantity: 3 }
        : refund
          ? { orderId: ids.order, amountCents: 12000, reason: '订单物流延误', ticketId: state.tickets[0]?.id }
          : { orderId: ids.order, category: '物流异常', title: '物流未按时送达', description: message, priority: 'NORMAL' }
      state.drafts.set(draftId, { id: draftId, runId, origin: 'AGENT', type, payload, evidenceSnapshot: { chunks: [] }, status: 'READY', expiresAt: new Date(Date.now() + 1800000).toISOString() })
      const answer = '已查询订单 AF20260926001，政策依据已核对。我已准备一份待确认草稿。'
      const citations = [{ citationId: 'citation-1', documentTitle: '售后服务政策', versionNo: 2, pageNo: 3, quote: '符合物流延误条件时，可提交售后申请。' }]
      state.messages = [
        ...state.messages,
        { id: 'user-' + runId, runId, role: 'USER', content: message, createdAt: new Date().toISOString() },
        { id: 'assistant-' + runId, runId, role: 'ASSISTANT', content: answer, structuredAnswer: { answer, citations, draftIds: [draftId], suggestedActions: [{ draftId, type, summary: '待确认' }] }, createdAt: new Date().toISOString() },
      ]
      const events = [
        { runId, sequence: 1, eventType: 'run.started', payload: {}, createdAt: new Date().toISOString() },
        { runId, sequence: 2, eventType: 'tool.started', payload: { invocationId: 'call-1', toolName: 'query_order' }, createdAt: new Date().toISOString() },
        { runId, sequence: 3, eventType: 'tool.completed', payload: { invocationId: 'call-1', toolName: 'query_order', success: true }, createdAt: new Date().toISOString() },
        { runId, sequence: 4, eventType: 'citation', payload: citations[0], createdAt: new Date().toISOString() },
        { runId, sequence: 5, eventType: 'draft.created', payload: { draftId, type, status: 'READY' }, createdAt: new Date().toISOString() },
        { runId, sequence: 6, eventType: 'answer.delta', payload: { text: answer }, createdAt: new Date().toISOString() },
        { runId, sequence: 7, eventType: 'answer.final', payload: { answer, citationIds: ['citation-1'], draftIds: [draftId], needsHuman: true }, createdAt: new Date().toISOString() },
      ]
      state.currentEvents = events.map((event) => 'id: ' + event.sequence + '\nevent: ' + event.eventType + '\ndata: ' + JSON.stringify(event) + '\n\n').join('')
      return ok({ runId, conversationId: 'conversation-e2e', status: 'RUNNING', deadlineAt: new Date(Date.now() + 60000).toISOString(), eventsUrl: '/api/v1/agent-runs/' + runId + '/events' }, 202)
    }
    if (/^\/agent-runs\/[^/]+\/events$/.test(path)) {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: state.currentEvents })
    }
    if (path.startsWith('/action-drafts/') && method === 'GET') {
      const draftId = path.split('/').at(-1) ?? ''
      const draft = state.drafts.get(draftId)
      return draft ? ok(draft) : route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: 'Not found' }) })
    }
    if (path.startsWith('/action-drafts/') && path.endsWith('/confirm') && method === 'POST') {
      const draftId = path.split('/')[2]
      const draft = state.drafts.get(draftId)
      if (!draft) return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: 'Not found' }) })
      draft.status = 'CONFIRMED'
      let result: Record<string, unknown>
      if (draft.type === 'CREATE_TICKET') {
        const ticket = { id: ids.ticket, ticketNo: 'AFTER-2026-0001', orderId: ids.order, category: '物流异常', title: '物流未按时送达', description: '物流未按时送达', priority: 'NORMAL', status: 'OPEN', version: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), events: [] }
        state.tickets = [ticket]
        result = { draftId, ticketId: ids.ticket, ticketNo: 'AFTER-2026-0001', status: 'OPEN' }
      } else {
        const isRefund = draft.type === 'REQUEST_REFUND'
        const action = {
          id: isRefund ? 'action-refund' : 'action-stock',
          ticketId: (draft.payload as Record<string, unknown>).ticketId,
          type: isRefund ? 'REFUND_REQUEST' : 'STOCK_TRANSFER',
          payload: draft.payload,
          evidenceSnapshot: { chunks: [] },
          status: 'PENDING_APPROVAL',
          expiresAt: new Date(Date.now() + 86400000).toISOString(),
          version: 0,
          requestedBy: state.username + '-actor',
        }
        state.actions = [...state.actions.filter((item) => item.id !== action.id), action]
        result = { draftId, actionId: action.id, status: action.status }
      }
      state.messages = [...state.messages, { id: 'action-result-' + draftId, runId: draft.runId, role: 'ASSISTANT', content: draft.type === 'CREATE_TICKET' ? '工单 AFTER-2026-0001 已创建。' : '申请已提交，等待审批。', structuredAnswer: { answer: '操作结果已记录。', actionResult: result, needsHuman: true }, createdAt: new Date().toISOString() }]
      return ok(result)
    }
    if (path === '/tickets' && method === 'GET') return ok({ items: state.tickets, page: 1, size: 100, total: state.tickets.length })
    if (path === '/sensitive-actions' && method === 'GET') {
      for (const item of state.actions) {
        if (item.status === 'APPROVED') {
          item.status = 'SUCCEEDED'
          item.executionResult = item.type === 'REFUND_REQUEST'
            ? { status: item.status, simulation: false, executionMode: 'LOCAL_DEMO_LEDGER',
              details: { executionMode: 'LOCAL_DEMO_LEDGER', externalPayment: false } }
            : { status: item.status, simulation: false }
        }
      }
      return ok(state.actions)
    }
    if (/^\/sensitive-actions\/[^/]+\/decision$/.test(path) && method === 'POST') {
      if (state.username === 'csr') return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ code: 'FORBIDDEN', message: '当前账号不能审批此类申请' }) })
      const actionId = path.split('/')[2]
      const item = state.actions.find((action) => action.id === actionId)
      if (item) item.status = 'APPROVED'
      return ok({ actionId, status: 'APPROVED', version: Number(item?.version ?? 0) + 1 })
    }
    if (path === '/inventory' && method === 'GET') {
      return ok({ skuId: ids.sku, skuCode: url.searchParams.get('skuCode') ?? 'SKU-001', skuName: '轻量旅行水杯', calculatedAt: new Date().toISOString(), warehouses: [
        { warehouseId: ids.east, warehouseCode: 'WH-EAST', warehouseName: '华东仓', onHand: 28, reserved: 4, available: 24, safetyStock: 8, allocatable: 16, inventoryVersion: 7 },
        { warehouseId: ids.central, warehouseCode: 'WH-CENTRAL', warehouseName: '华中仓', onHand: 15, reserved: 5, available: 10, safetyStock: 5, allocatable: 5, inventoryVersion: 4 },
        { warehouseId: ids.south, warehouseCode: 'WH-SOUTH', warehouseName: '华南仓', onHand: 3, reserved: 2, available: 1, safetyStock: 4, allocatable: 0, inventoryVersion: 2 },
      ] })
    }
    if (path === '/inventory/recommendations' && method === 'POST') {
      return ok({ skuId: ids.sku, targetWarehouseId: ids.south, quantity: body?.quantity ?? 3, calculatedAt: new Date().toISOString(), candidates: [
        { sourceWarehouseId: ids.east, available: 24, allocatable: 16, inventoryVersion: 7, estimatedDays: 2, costCentsPerUnit: 320 },
      ] })
    }
    if (path.startsWith('/orders') && method === 'GET' && !path.endsWith('/logistics')) {
      const data = { orderId: ids.order, orderNo: url.searchParams.get('orderNo') ?? 'AF20260926001', status: 'SHIPPED', purchasedAt: new Date().toISOString(), paidAmountCents: 36900, refundedAmountCents: 0, promisedDeliveryAt: new Date().toISOString(), customerName: '林女士', maskedPhone: '138****2048', items: [{ skuId: ids.sku, skuCode: 'SKU-001', name: '轻量旅行水杯', quantity: 1, unitPriceCents: 36900 }] }
      return ok(data)
    }
    if (path.endsWith('/logistics')) return ok({ orderId: ids.order, calculatedAt: new Date().toISOString(), dataInsufficient: false, exceptionCodes: ['DELIVERY_DELAY'], events: [{ eventCode: 'IN_TRANSIT', eventTime: new Date().toISOString(), carrierCode: 'SF', trackingNo: 'SF123', description: '包裹运输中' }] })
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'NOT_MOCKED', message: '未模拟的接口：' + method + ' ' + path }) })
  })
  return state
}

export async function loginAs(page: Page, username: string) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill('DemoPass123!')
  await page.getByRole('button', { name: '进入工作台' }).click()
  await page.waitForURL('**/workspace')
}
