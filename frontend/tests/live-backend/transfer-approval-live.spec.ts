import { expect, test, type Page } from '@playwright/test'
import { liveData, login } from './support'

type WarehouseStock = {
  warehouseId: string
  onHand: number
  reserved: number
  safetyStock: number
}

type InventorySnapshot = { skuId: string; warehouses: WarehouseStock[] }
type TransferCandidate = { sourceWarehouseId: string }
type SensitiveAction = {
  id: string
  type: string
  status: string
  version: number
  requestedBy: string
  payload: {
    skuId: string
    sourceWarehouseId: string
    targetWarehouseId: string
    quantity: number
  }
  executionResult?: { status?: string; simulation?: boolean; transferOrderId?: string }
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

async function readInventory(page: Page): Promise<InventorySnapshot> {
  const response = await apiJson<{ data: InventorySnapshot }>(
    page,
    `/api/v1/inventory?skuCode=${encodeURIComponent(liveData.skuCode)}`,
  )
  expect(response.status).toBe(200)
  return response.body.data
}

async function readAction(page: Page, actionId: string): Promise<SensitiveAction> {
  const response = await apiJson<{ data: SensitiveAction }>(page, `/api/v1/sensitive-actions/${actionId}`)
  expect(response.status).toBe(200)
  return response.body.data
}

test('库存页创建调拨申请，经仓库审批后只在本地形成单据和预留', async ({ page }) => {
  const warehousePassword = requiredEnv('LIVE_TEST_WAREHOUSE_PASSWORD')
  await login(page)
  const inventoryBefore = await readInventory(page)

  await page.getByRole('link', { name: /库存调度/ }).click()
  await page.getByLabel('商品编码').fill(liveData.skuCode)
  await page.getByRole('button', { name: '查询库存' }).click()
  await expect(page.locator('.inventory-product-heading')).toContainText(liveData.skuCode)
  await page.getByLabel('申请数量').fill('2')
  const recommendationResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/inventory/recommendations',
  )
  await page.getByRole('button', { name: '生成建议' }).click()
  const recommendation = await recommendationResponse
  expect(recommendation.ok()).toBeTruthy()
  const recommendationBody = await recommendation.json() as {
    data: { targetWarehouseId: string; candidates: TransferCandidate[] }
  }
  const selectedCandidate = recommendationBody.data.candidates[0]
  expect(selectedCandidate?.sourceWarehouseId).toBeTruthy()

  await page.locator('.candidate-row').first().getByRole('button', { name: '生成申请草稿' }).click()
  const draftCard = page.locator('.message__draft .draft-card')
  await expect(draftCard).toBeVisible({ timeout: 30_000 })
  await expect(draftCard).toContainText('库存调拨申请')
  await expect(draftCard).toContainText('2 件')
  await expect(draftCard.getByRole('button', { name: '提交审批申请' })).toBeVisible()

  const inventoryWhileDraft = await readInventory(page)
  expect(inventoryWhileDraft.warehouses.map(({ warehouseId, onHand, reserved }) => [warehouseId, onHand, reserved]))
    .toEqual(inventoryBefore.warehouses.map(({ warehouseId, onHand, reserved }) => [warehouseId, onHand, reserved]))

  const confirmationResponse = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'POST'
      && /\/api\/v1\/action-drafts\/[^/]+\/confirm$/.test(url.pathname)
  })
  await draftCard.getByRole('button', { name: '提交审批申请' }).click()
  const confirmation = await confirmationResponse
  expect(confirmation.ok()).toBeTruthy()
  const confirmationBody = await confirmation.json() as { data: { actionId: string; status: string } }
  const actionId = confirmationBody.data.actionId
  expect(confirmationBody.data.status).toBe('PENDING_APPROVAL')

  const actionBeforeApproval = await readAction(page, actionId)
  expect(actionBeforeApproval.type).toBe('STOCK_TRANSFER')
  expect(actionBeforeApproval.payload).toMatchObject({
    skuId: inventoryBefore.skuId,
    sourceWarehouseId: selectedCandidate.sourceWarehouseId,
    targetWarehouseId: recommendationBody.data.targetWarehouseId,
    quantity: 2,
  })
  const selfDecision = await apiJson(page, `/api/v1/sensitive-actions/${actionId}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ decision: 'APPROVE', reason: '申请人不可以自批', expectedVersion: actionBeforeApproval.version }),
  })
  expect(selfDecision.status).toBe(403)

  const warehousePage = await page.context().newPage()
  try {
    await loginAs(warehousePage, 'warehouse', warehousePassword)
    const warehouseBefore = await readInventory(warehousePage)
    await warehousePage.getByRole('link', { name: '人工审批' }).click()
    const card = warehousePage.locator('.approval-card').filter({ hasText: actionId.slice(0, 8) })
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '批准申请' }).click()
    await expect(warehousePage.getByRole('heading', { name: '确认批准申请？' })).toBeVisible()
    await warehousePage.locator('.dialog textarea').fill('核对来源库存和安全库存后批准本地调拨。')
    await warehousePage.getByRole('button', { name: '确认批准' }).click()

    await expect.poll(async () => (await readAction(warehousePage, actionId)).executionResult, {
      timeout: 30_000,
      intervals: [500, 1_000, 2_000],
    }).toMatchObject({ status: 'SUCCEEDED', simulation: false })

    const actionAfterApproval = await readAction(warehousePage, actionId)
    expect(actionAfterApproval.executionResult?.transferOrderId).toMatch(/^[0-9a-f-]{36}$/i)
    const inventoryAfter = await readInventory(warehousePage)
    const sourceBefore = warehouseBefore.warehouses.find(
      ({ warehouseId }) => warehouseId === selectedCandidate.sourceWarehouseId,
    )
    const targetBefore = warehouseBefore.warehouses.find(
      ({ warehouseId }) => warehouseId === recommendationBody.data.targetWarehouseId,
    )
    const sourceAfter = inventoryAfter.warehouses.find(
      ({ warehouseId }) => warehouseId === selectedCandidate.sourceWarehouseId,
    )
    const targetAfter = inventoryAfter.warehouses.find(
      ({ warehouseId }) => warehouseId === recommendationBody.data.targetWarehouseId,
    )
    expect(sourceBefore).toBeTruthy()
    expect(targetBefore).toBeTruthy()
    expect(sourceAfter?.onHand).toBe(sourceBefore?.onHand)
    expect(sourceAfter?.reserved).toBe((sourceBefore?.reserved ?? 0) + 2)
    expect(targetAfter?.onHand).toBe(targetBefore?.onHand)
    expect(targetAfter?.reserved).toBe(targetBefore?.reserved)
  } finally {
    await warehousePage.close()
  }
})
