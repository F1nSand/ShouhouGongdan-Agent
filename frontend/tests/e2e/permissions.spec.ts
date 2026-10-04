import { expect, test } from '@playwright/test'
import { loginAs, mockApi } from './support'

test('客服看不到审批入口，直接调用审批接口也会得到 403', async ({ page }) => {
  await mockApi(page)
  await loginAs(page, 'csr')
  await expect(page.getByRole('link', { name: '人工审批' })).toHaveCount(0)
  await page.goto('/approvals')
  await expect(page).toHaveURL(/\/workspace$/)

  const responseStatus = await page.evaluate(async () => {
    const response = await fetch('/api/v1/sensitive-actions/action-refund/decision', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'permission-probe' },
      body: JSON.stringify({ decision: 'APPROVE', reason: '直接权限校验', expectedVersion: 0 }),
    })
    return response.status
  })
  expect(responseStatus).toBe(403)
})
