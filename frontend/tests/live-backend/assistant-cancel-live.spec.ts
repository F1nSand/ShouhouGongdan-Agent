import { expect, test } from '@playwright/test'
import { liveData, login } from './support'

test('真实模型运行中可从工作台取消且不产生业务写入', async ({ page }) => {
  const writes: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && /\/api\/v1\/(?:tickets|action-drafts|inventory\/recommendations)/.test(url.pathname)) {
      writes.push(`${request.method()} ${url.pathname}`)
    }
  })

  await login(page)
  await page.getByRole('button', { name: '新建会话' }).click()
  await expect(page.getByRole('heading', { name: '把问题说给我听。' })).toBeVisible()
  await page.getByLabel('描述售后问题').fill(
    `请查询订单 ${liveData.orderNo} 的当前物流轨迹。`,
  )

  const eventRequest = page.waitForRequest((request) =>
    request.method() === 'GET' && /\/api\/v1\/agent-runs\/[^/]+\/events$/.test(new URL(request.url()).pathname),
  )
  await page.getByRole('button', { name: '发送消息' }).click()
  const streamRequest = await eventRequest
  const runId = new URL(streamRequest.url()).pathname.match(/agent-runs\/([^/]+)\/events$/)?.[1]
  expect(runId).toEqual(expect.stringMatching(/^[0-9a-f-]{36}$/i))

  const cancelResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/v1/agent-runs/${runId}/cancel`,
  )
  await page.getByRole('button', { name: '停止' }).click()
  const cancelResponse = await cancelResponsePromise
  expect(cancelResponse.ok()).toBeTruthy()
  const result = await cancelResponse.json() as { data?: { status?: string } }
  expect(result.data?.status).toBe('CANCELLED')
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0)
  expect(writes).toEqual([])
})
