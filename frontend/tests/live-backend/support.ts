import { expect, type Page } from '@playwright/test'

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`缺少必需环境变量 ${name}`)
  return value
}

export const liveData = {
  merchantCode: process.env.LIVE_TEST_MERCHANT_CODE?.trim() || 'DEMO_MERCHANT',
  username: requiredEnv('LIVE_TEST_USERNAME'),
  password: requiredEnv('LIVE_TEST_PASSWORD'),
  orderNo: requiredEnv('LIVE_SYNTHETIC_ORDER_NO'),
  skuCode: requiredEnv('LIVE_SYNTHETIC_SKU_CODE'),
}

export async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('商家编码').fill(liveData.merchantCode)
  await page.getByLabel('用户名').fill(liveData.username)
  await page.getByLabel('密码').fill(liveData.password)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await page.waitForURL('**/workspace')
}

export async function expectUniqueRequestId(page: Page, trigger: () => Promise<void>) {
  const runRequestPromise = page.waitForRequest((request) => {
    const url = new URL(request.url())
    return request.method() === 'POST' && /\/api\/v1\/conversations\/[^/]+\/runs$/.test(url.pathname)
  })
  await trigger()
  const request = await runRequestPromise
  const requestId = (request.postDataJSON() as { requestId?: unknown }).requestId
  expect(requestId).toEqual(expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i))
}
