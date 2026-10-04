import { expect, test } from '@playwright/test'
import { loginAs, mockApi } from './support'

test('库存建议可转为调拨申请草稿并由仓库审批', async ({ page }) => {
  const api = await mockApi(page)
  await loginAs(page, 'csr')
  await page.getByRole('link', { name: /库存调度/ }).click()
  await page.getByRole('button', { name: '查询演示商品' }).click()
  await expect(page.getByText('轻量旅行水杯')).toBeVisible()
  await page.getByRole('button', { name: '生成建议' }).click()
  await expect(page.locator('.candidate-row').getByText('华东仓', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '生成申请草稿' }).click()
  await expect(page.getByRole('button', { name: '提交审批申请' })).toBeVisible()
  await page.getByRole('button', { name: '提交审批申请' }).click()
  await expect(page.getByText('申请已提交，等待审批。')).toBeVisible()
  expect(api.actions).toHaveLength(1)
  expect(api.actions[0].status).toBe('PENDING_APPROVAL')

  await page.getByRole('button', { name: '退出登录' }).click()
  await loginAs(page, 'warehouse')
  await page.getByRole('link', { name: '人工审批' }).click()
  await expect(page.getByText('库存调拨审批 / action-s')).toBeVisible()
  await page.getByRole('button', { name: '批准申请' }).click()
  await page.getByLabel(/决定原因/).fill('来源仓可调配数量满足申请，保留安全库存后批准。')
  await page.getByRole('button', { name: '确认批准' }).click()
  await expect(page.getByText('已完成')).toBeVisible()
})
