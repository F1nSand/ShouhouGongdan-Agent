import { expect, test } from '@playwright/test'
import { liveData, login } from './support'

test('SSE 首次断线后可从事件游标恢复最终答复', async ({ page }) => {
  const eventCursors: string[] = []
  await page.route('**/api/v1/agent-runs/*/events', async (route) => {
    eventCursors.push(route.request().headers()['last-event-id'] ?? '')
    if (eventCursors.length === 1) {
      await route.abort('failed')
      return
    }
    await route.continue()
  })

  await login(page)
  await page.getByRole('button', { name: '新建会话' }).click()
  await expect(page.getByRole('heading', { name: '把问题说给我听。' })).toBeVisible()
  await page.getByLabel('描述售后问题').fill(
    `请查询订单 ${liveData.orderNo} 的订单状态和物流轨迹。`,
  )
  await page.getByRole('button', { name: '发送消息' }).click()

  await expect(page.locator('.message--assistant .message__content').last())
    .toContainText('已查询订单', { timeout: 30_000 })
  expect(eventCursors.length).toBeGreaterThanOrEqual(2)
  expect(eventCursors[0]).toBe('0')
  expect(eventCursors[1]).toBe('0')
})
