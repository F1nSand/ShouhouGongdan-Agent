import { expect, test } from '@playwright/test'
import { expectUniqueRequestId, liveData, login } from './support'

test('客服确认工单草稿后可从列表、详情和时间线追踪', async ({ page }) => {
  const confirmationRequests: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && /\/api\/v1\/action-drafts\/[^/]+\/confirm$/.test(url.pathname)) {
      confirmationRequests.push(url.pathname)
    }
  })

  await login(page)
  await page.getByRole('button', { name: '新建会话' }).click()
  await expect(page.getByRole('heading', { name: '把问题说给我听。' })).toBeVisible()
  await page.getByLabel('描述售后问题').fill(
    `订单 ${liveData.orderNo} 的物流显示异常，用户称已签收但没有收到，请创建一张待跟进工单。`,
  )
  await expectUniqueRequestId(page, () => page.getByRole('button', { name: '发送消息' }).click())
  await expect(page.locator('.message__draft .draft-card')).toBeVisible({ timeout: 180_000 })
  await expect(page.locator('.message__draft .draft-card button')).toHaveText('确认创建工单')
  expect(confirmationRequests).toEqual([])

  const confirmResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'POST' && /\/api\/v1\/action-drafts\/[^/]+\/confirm$/.test(url.pathname)
  })
  await page.locator('.message__draft .draft-card button').click()
  const confirmResponse = await confirmResponsePromise
  expect(confirmResponse.ok()).toBeTruthy()
  const confirmation = await confirmResponse.json() as { data?: { ticketId?: string; ticketNo?: string } }
  const ticketId = confirmation.data?.ticketId
  const ticketNo = confirmation.data?.ticketNo
  expect(ticketId).toEqual(expect.stringMatching(/^[0-9a-f-]{36}$/i))
  expect(ticketNo).toEqual(expect.stringMatching(/^TK-\d{8}-[A-F0-9]{10}$/))
  expect(confirmationRequests).toHaveLength(1)

  await page.getByRole('link', { name: /售后工单/ }).click()
  await expect(page).toHaveURL(/\/tickets$/)
  await expect(page.getByRole('button', { name: `查看工单 ${ticketNo}` })).toBeVisible()
  await page.getByRole('button', { name: `查看工单 ${ticketNo}` }).click()
  await expect(page).toHaveURL(new RegExp(`/tickets/${ticketId}$`))
  await expect(page.locator('.detail-heading .ticket-code')).toHaveText(ticketNo!)
  await expect(page.locator('.timeline').getByText('CREATED', { exact: true })).toBeVisible()
})
