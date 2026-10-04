import { expect, test } from '@playwright/test'
import { expectUniqueRequestId, liveData, login } from './support'

test('真实模型经工作台完成订单与物流只读查询', async ({ page }) => {
  const writes: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && /\/api\/v1\/(?:tickets|action-drafts\/[^/]+\/confirm|inventory\/recommendations)/.test(url.pathname)) {
      writes.push(`${request.method()} ${url.pathname}`)
    }
  })

  await login(page)
  await page.getByRole('button', { name: '新建会话' }).click()
  await expect(page.getByRole('heading', { name: '把问题说给我听。' })).toBeVisible()
  await page.getByLabel('描述售后问题').fill(
    `请查询订单 ${liveData.orderNo} 的当前订单状态和物流轨迹，并说明最后一条物流记录。`,
  )
  await expectUniqueRequestId(page, () => page.getByRole('button', { name: '发送消息' }).click())

  await expect(page.locator('.order-summary__number')).toHaveText(liveData.orderNo)
  await expect(page.locator('.logistics-strip')).toBeVisible()
  await expect(page.locator('.message--assistant .message__content').last())
    .toContainText('已查询订单', { timeout: 180_000 })
  expect(writes).toEqual([])
})
