import { expect, test } from '@playwright/test'
import { loginAs, mockApi } from './support'

test('客服确认工单并提交退款申请，由主管审批并显示本地演示账本结果', async ({ page }) => {
  await mockApi(page)
  await loginAs(page, 'csr')

  await page.getByLabel('描述售后问题').fill('订单 AF20260926001 还没收到，帮我查物流并整理工单')
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page.getByRole('button', { name: '确认创建工单' })).toBeVisible()
  await expect(page.getByRole('region', { name: '售后助手对话' }).getByText('售后服务政策')).toBeVisible()
  await page.getByRole('button', { name: '确认创建工单' }).click()
  await expect(page.getByText('工单 AFTER-2026-0001 已创建', { exact: true })).toBeVisible()

  await page.getByLabel('描述售后问题').fill('订单 AF20260926001 帮我申请退款 120 元')
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page.getByRole('button', { name: '提交审批申请' })).toBeVisible()
  await page.getByRole('button', { name: '提交审批申请' }).click()
  await expect(page.getByText('申请已提交，等待审批。')).toBeVisible()

  await page.getByRole('button', { name: '退出登录' }).click()
  await loginAs(page, 'supervisor')
  await page.getByRole('link', { name: '人工审批' }).click()
  await expect(page.getByText('退款审批 / action-r')).toBeVisible()
  await page.getByRole('button', { name: '批准申请' }).click()
  await page.getByLabel(/决定原因/).fill('订单物流超出承诺时效，符合退款申请条件。')
  await page.getByRole('button', { name: '确认批准' }).click()
  await expect(page.getByText('已完成', { exact: true })).toBeVisible()
  await expect(page.getByText('演示订单退款已入账')).toBeVisible()
  await expect(page.getByText(/LOCAL_DEMO_LEDGER.*externalPayment.*false/)).toBeVisible()
})
