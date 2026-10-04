import { expect, test, type Page } from '@playwright/test'
import { liveData, login } from './support'

type OrderSnapshot = {
  orderId: string
  orderNo: string
  paidAmountCents: number
  refundedAmountCents: number
}

type SensitiveAction = {
  id: string
  type: string
  payload: { orderId?: string; orderNo?: string; amountCents?: number; reason?: string }
  status: string
  version: number
  requestedBy: string
  executionResult?: { simulation?: boolean; status?: string; executionMode?: string; details?: Record<string, unknown> }
}

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`缺少必需环境变量 ${name}`)
  return value
}

async function loginAs(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('商家编码').fill(liveData.merchantCode)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await page.waitForURL('**/workspace')
}

async function apiJson<T>(page: Page, path: string, init?: RequestInit): Promise<{ status: number; body: T }> {
  return page.evaluate(async ({ path, init }) => {
    const storedSession = sessionStorage.getItem('aftercare.session')
    const token = storedSession ? (JSON.parse(storedSession) as { accessToken?: string }).accessToken : undefined
    const headers = new Headers(init?.headers)
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(path, { ...init, headers })
    return { status: response.status, body: await response.json() as T }
  }, { path, init })
}

async function readOrder(page: Page): Promise<OrderSnapshot> {
  const response = await apiJson<{ data: OrderSnapshot }>(
    page,
    `/api/v1/orders?orderNo=${encodeURIComponent(liveData.orderNo)}`,
  )
  expect(response.status).toBe(200)
  return response.body.data
}

async function readAction(page: Page, actionId: string): Promise<SensitiveAction> {
  const response = await apiJson<{ data: SensitiveAction }>(page, `/api/v1/sensitive-actions/${actionId}`)
  expect(response.status).toBe(200)
  return response.body.data
}

test('客服提交退款申请后由另一位主管审批并记录本地演示账本结果', async ({ page }) => {
  const refundAmountCents = 100
  const supervisorPassword = requiredEnv('LIVE_TEST_SUPERVISOR_PASSWORD')
  const orderBefore = await (async () => {
    await login(page)
    return readOrder(page)
  })()
  expect(orderBefore.paidAmountCents - orderBefore.refundedAmountCents).toBeGreaterThanOrEqual(refundAmountCents)

  await page.getByRole('button', { name: '新建会话' }).click()
  await expect(page.getByRole('heading', { name: '把问题说给我听。' })).toBeVisible()
  await page.getByLabel('描述售后问题').fill(
    `订单 ${liveData.orderNo} 申请退款 1 元，原因是客户取消订单，请提交退款审批。`,
  )
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page.locator('.message__draft .draft-card')).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('.message__draft .draft-card')).toContainText('退款申请')
  await expect(page.locator('.message__draft .draft-card')).toContainText('¥1.00')

  const confirmationResponse = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'POST'
      && /\/api\/v1\/action-drafts\/[^/]+\/confirm$/.test(url.pathname)
  })
  await page.locator('.message__draft .draft-card button').click()
  const confirmation = await confirmationResponse
  expect(confirmation.ok()).toBeTruthy()
  const confirmed = await confirmation.json() as { data: { actionId?: string; status: string } }
  const actionId = confirmed.data.actionId
  expect(actionId).toEqual(expect.stringMatching(/^[0-9a-f-]{36}$/i))
  expect(confirmed.data.status).toBe('PENDING_APPROVAL')

  const submittedAction = await readAction(page, actionId!)
  expect(submittedAction.type).toBe('REFUND_REQUEST')
  expect(submittedAction.payload.orderId).toBe(orderBefore.orderId)
  expect(submittedAction.payload.amountCents).toBe(refundAmountCents)
  expect(submittedAction.status).toBe('PENDING_APPROVAL')

  await page.goto('/approvals')
  await expect(page).toHaveURL(/\/workspace$/)
  await expect(page.getByRole('link', { name: '人工审批' })).toHaveCount(0)
  const selfDecision = await apiJson<{ code?: string; message?: string }>(
    page,
    `/api/v1/sensitive-actions/${actionId}/decision`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ decision: 'APPROVE', reason: '客服自审批应被拒绝', expectedVersion: submittedAction.version }),
    },
  )
  expect(selfDecision.status).toBe(403)

  const supervisorPage = await page.context().newPage()
  try {
    await loginAs(supervisorPage, 'supervisor', supervisorPassword)
    await supervisorPage.getByRole('link', { name: '人工审批' }).click()
    await expect(supervisorPage).toHaveURL(/\/approvals$/)
    const card = supervisorPage.locator('.approval-card').filter({ hasText: actionId!.slice(0, 8) })
    await expect(card).toBeVisible({ timeout: 30_000 })
    await expect(card).toContainText('退款审批')
    await expect(card).toContainText('¥1.00')
    await expect(card).toContainText('申请人')

    await card.getByRole('button', { name: '批准申请' }).click()
    await expect(supervisorPage.getByRole('heading', { name: '确认批准申请？' })).toBeVisible()
    await supervisorPage.locator('.dialog textarea').fill('核验订单后批准退款申请。')
    const decisionResponse = supervisorPage.waitForResponse((response) => {
      const url = new URL(response.url())
      return response.request().method() === 'POST'
        && url.pathname === `/api/v1/sensitive-actions/${actionId}/decision`
    })
    await supervisorPage.getByRole('button', { name: '确认批准' }).click()
    const decision = await decisionResponse
    expect(decision.ok()).toBeTruthy()
    const decisionBody = await decision.json() as { data: { actionId: string; status: string } }
    expect(decisionBody.data.actionId).toBe(actionId)
    expect(decisionBody.data.status).toBe('APPROVED')

    await expect.poll(async () => (await readAction(supervisorPage, actionId!)).executionResult, {
      timeout: 30_000,
      intervals: [500, 1_000, 2_000],
    }).toMatchObject({
      simulation: false,
      status: 'SUCCEEDED',
      executionMode: 'LOCAL_DEMO_LEDGER',
      details: { externalPayment: false },
    })
    await expect(card).toContainText('演示订单退款已入账', { timeout: 30_000 })

    const actionAfter = await readAction(supervisorPage, actionId!)
    expect(actionAfter.status).toBe('SUCCEEDED')
    expect(actionAfter.executionResult?.simulation).toBe(false)
    expect(actionAfter.executionResult?.executionMode).toBe('LOCAL_DEMO_LEDGER')
    const orderAfter = await readOrder(supervisorPage)
    expect(orderAfter.orderId).toBe(orderBefore.orderId)
    expect(orderAfter.refundedAmountCents).toBe(orderBefore.refundedAmountCents + refundAmountCents)
  } finally {
    await supervisorPage.close()
  }
})
