export interface RunEvent {
  runId: string
  sequence: number
  eventType: string
  payload: Record<string, unknown>
  createdAt: string
}

export function appendUniqueRunEvent(events: RunEvent[], incoming: RunEvent): RunEvent[] {
  if (events.some((event) => event.runId === incoming.runId && event.sequence === incoming.sequence)) {
    return events
  }

  return [...events, incoming].sort((a, b) =>
    a.runId === b.runId ? a.sequence - b.sequence : a.createdAt.localeCompare(b.createdAt),
  )
}

export function formatCents(cents: number): string {
  if (!Number.isSafeInteger(cents)) return '金额无效'

  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(cents / 100)
}

const STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理',
  ASSIGNED: '已分派',
  IN_PROGRESS: '处理中',
  WAITING_APPROVAL: '待审批',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  PREPARING: '准备中',
  READY: '待确认',
  CONFIRMED: '已确认',
  INVALIDATED: '已失效',
  PENDING: '待审批',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  EXECUTING: '执行中',
  EXECUTED: '已执行',
  SUCCEEDED: '已完成',
  SIMULATED_SUCCEEDED: '模拟执行成功',
  FAILED: '执行失败',
  EXPIRED: '已过期',
  QUEUED: '排队中',
  RUNNING: '处理中',
  WAITING_CONFIRMATION: '待确认',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  SERVER_RESTARTED: '服务重启中断',
  UPLOADED: '已上传',
  PROCESSING: '处理中',
  READY_TO_PUBLISH: '待发布',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
}

export function statusLabel(status: string | null | undefined): string {
  if (!status) return '未知状态'
  return STATUS_LABELS[status] ?? '未知状态'
}

export function modelBudgetErrorMessage(errorCode: string | null | undefined): string | undefined {
  if (errorCode === 'MODEL_DAILY_BUDGET_EXHAUSTED') {
    return '今天的模型调用额度已用完，将于北京时间次日 00:00 恢复。'
  }
  if (errorCode === 'MODEL_LIFETIME_BUDGET_EXHAUSTED') {
    return '模型调用累计额度已用完，请检查本地额度配置。'
  }
  return undefined
}
