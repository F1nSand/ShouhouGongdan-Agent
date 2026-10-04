import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { expectUniqueRequestId, liveData, login } from './support'

test('真实订单与物流查询展示知识引用及工单草稿，确认前不创建正式工单', async ({ page }) => {
  const logisticsPolicy = readFileSync(
    new URL('../../../docs/demo/policies/logistics-service-v1.md', import.meta.url),
    'utf8',
  ).trim()
  const mutations: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && /\/api\/v1\/(?:tickets|action-drafts\/[^/]+\/confirm)(?:\/|$)/.test(url.pathname)) {
      mutations.push(`${request.method()} ${url.pathname}`)
    }
  })

  await login(page)
  await page.getByRole('button', { name: '新建会话' }).click()
  await expect(page.getByRole('heading', { name: '把问题说给我听。' })).toBeVisible()
  await page.getByLabel('描述售后问题').fill(
    `订单 ${liveData.orderNo} 请按当前物流事实核对。政策原文：${logisticsPolicy} 请生成一份物流异常工单草稿，不要确认。`,
  )
  await expectUniqueRequestId(page, () => page.getByRole('button', { name: '发送消息' }).click())

  await expect(page.locator('.order-summary__number')).toHaveText(liveData.orderNo)
  await expect(page.locator('.logistics-strip')).toBeVisible()
  await expect(page.locator('.message__draft .draft-card')).toBeVisible({ timeout: 180_000 })
  await expect(page.locator('.message__draft .draft-card .eyebrow')).toContainText('工单草稿')
  await expect(page.locator('.message__draft .draft-card .status-badge')).toContainText(/待确认|就绪|READY/i)
  await expect(page.locator('.message__draft .draft-card button')).toHaveText('确认创建工单')
  const latestAnswer = page.locator('.message--assistant').last()
  await expect(latestAnswer.locator('.citation-card').first()).toBeVisible()
  await expect(latestAnswer.locator('.citation-card__heading strong').first()).toContainText('物流')

  expect(mutations).toEqual([])
})
