import { expect, test } from '@playwright/test'
import { liveData, login } from './support'

test('真实 SKU 库存查询只读展示各仓快照', async ({ page }) => {
  let inventoryReads = 0
  let recommendations = 0
  const recommendationRequests: Array<{ quantity?: number }> = []
  const writes: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/inventory' && request.method() === 'GET') inventoryReads += 1
    if (url.pathname === '/api/v1/inventory/recommendations' && request.method() === 'POST') {
      recommendations += 1
      recommendationRequests.push(request.postDataJSON() as { quantity?: number })
    } else if (url.pathname.startsWith('/api/v1/inventory') && request.method() !== 'GET') {
      writes.push(`${request.method()} ${url.pathname}`)
    }
  })

  await login(page)
  await page.getByRole('link', { name: /库存调度/ }).click()
  await page.getByLabel('商品编码').fill(liveData.skuCode)
  await page.getByRole('button', { name: '查询库存' }).click()

  await expect(page.locator('.inventory-product-heading')).toContainText(liveData.skuCode)
  await expect(page.locator('.stock-card').first()).toBeVisible()
  const stockBefore = await page.locator('.stock-card__amount').allTextContents()

  await page.getByLabel('申请数量').fill('2')
  await page.getByRole('button', { name: '生成建议' }).click()
  await expect(page.locator('.candidate-row').first()).toBeVisible()

  await page.getByLabel('申请数量').fill('99')
  await page.getByRole('button', { name: '生成建议' }).click()
  await expect(page.locator('.no-candidates')).toContainText('没有满足安全库存')
  const refreshPromise = page.waitForResponse((response) =>
    response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/v1/inventory',
  )
  await page.getByRole('button', { name: '查询库存' }).click()
  await refreshPromise
  await expect(page.locator('.stock-card__amount')).toHaveCount(stockBefore.length)
  expect(await page.locator('.stock-card__amount').allTextContents()).toEqual(stockBefore)

  expect(inventoryReads).toBeGreaterThan(0)
  expect(recommendations).toBe(2)
  expect(recommendationRequests.map(({ quantity }) => quantity)).toEqual([2, 99])
  expect(writes).toEqual([])
})
