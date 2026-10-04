import { describe, expect, it } from 'vitest'
import { appendUniqueRunEvent, formatCents, modelBudgetErrorMessage, statusLabel, type RunEvent } from './display'

const event = (runId: string, sequence: number, eventType = 'tool.started'): RunEvent => ({
  runId,
  sequence,
  eventType,
  payload: {},
  createdAt: '2026-09-27T00:00:00Z',
})

describe('display helpers', () => {
  it('deduplicates events by run id and sequence without dropping other runs', () => {
    const first = event('run-a', 1)
    const next = event('run-a', 2, 'answer.final')

    expect(appendUniqueRunEvent([first], event('run-a', 1, 'replayed'))).toEqual([first])
    expect(appendUniqueRunEvent([first], next)).toEqual([first, next])
    expect(appendUniqueRunEvent([first], event('run-b', 1))).toHaveLength(2)
  })

  it('formats integer cents as Chinese yuan with two decimal places', () => {
    expect(formatCents(125)).toBe('¥1.25')
    expect(formatCents(10000)).toBe('¥100.00')
    expect(formatCents(0)).toBe('¥0.00')
  })

  it('uses readable labels for business states and a safe fallback', () => {
    expect(statusLabel('WAITING_APPROVAL')).toBe('待审批')
    expect(statusLabel('IN_PROGRESS')).toBe('处理中')
    expect(statusLabel('APPROVED')).toBe('已批准')
    expect(statusLabel('SIMULATED_SUCCEEDED')).toBe('模拟执行成功')
    expect(statusLabel('UNKNOWN_STATE')).toBe('未知状态')
    expect(modelBudgetErrorMessage('MODEL_DAILY_BUDGET_EXHAUSTED'))
      .toBe('今天的模型调用额度已用完，将于北京时间次日 00:00 恢复。')
    expect(modelBudgetErrorMessage('MODEL_LIFETIME_BUDGET_EXHAUSTED'))
      .toBe('模型调用累计额度已用完，请检查本地额度配置。')
    expect(modelBudgetErrorMessage('AGENT_RUN_FAILED')).toBeUndefined()
  })
})
