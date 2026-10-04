# REST、SSE 与 Agent 工具契约

## 基础约定

接口前缀 /api/v1，JSON 使用 camelCase。内部 UUID 作为路径 ID，金额字段以 Cents 结尾，时间用 ISO 8601 UTC。登录后使用 Authorization: Bearer 访问令牌。

tenantId、actorId、role、traceId 由服务器从认证上下文绑定，不允许模型或客户端替换商家范围。会话和 SSE 仅向创建者开放；管理员查看审计不等于拥有所有会话读取权限。

普通响应：

~~~json
{
  "code": "OK",
  "data": {"ticketId": "uuid", "status": "OPEN"},
  "traceId": "server-trace-id"
}
~~~

错误响应：

~~~json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "当前可调配库存不足，请重新生成调拨建议",
  "data": null,
  "traceId": "server-trace-id"
}
~~~

POST 命令要求 Idempotency-Key 请求头，创建 Agent 运行除外，它通过 requestId 去重。重复同参数返回原始结果；同 Key 不同参数返回 409 IDEMPOTENCY_CONFLICT。

列表支持 page 从 1 开始，size 默认 20、最大 100，响应 items、page、size、total。查询参数允许的排序字段逐接口白名单校验。

## 身份与业务接口

| 方法与路径 | 输入或用途 | 权限 |
|---|---|---|
| POST /auth/login | merchantCode、username、password | 未登录，限流 |
| GET /auth/me | 当前账号、角色和商家 | 已登录 |
| GET /orders?orderNo=... | 精确订单号 | 客服、主管、仓库、管理员 |
| GET /orders/{orderId} | 订单详情 | 同上，对象隔离 |
| GET /orders/{orderId}/logistics | 事件、异常码、计算时间 | 同上 |
| GET /inventory?skuCode=... | 各仓实存、预留、安全库存、版本 | 内部角色 |
| POST /inventory/recommendations | skuId、targetWarehouseId、quantity | 客服、主管、仓库、管理员 |
| GET /conversations | 当前用户会话列表，分页 | 会话创建者 |
| POST /conversations | title 可省略 | 客服、主管、仓库、管理员 |
| GET /conversations/{conversationId}/messages | 历史消息及引用 | 会话创建者 |
| POST /conversations/{conversationId}/runs | message、requestId | 会话创建者 |
| GET /agent-runs/{runId} | 运行状态与结构化结果 | 会话创建者 |
| GET /agent-runs/{runId}/events | SSE，支持 Last-Event-ID | 会话创建者 |
| POST /agent-runs/{runId}/cancel | 取消活跃运行 | 会话创建者 |
| GET /action-drafts/{draftId} | 草稿与当前有效性 | 草稿创建者 |
| POST /action-drafts | 手工 CREATE_TICKET 草稿，type、typedPayload | 客服、主管、管理员 |
| POST /action-drafts/{draftId}/confirm | 无业务参数，使用保存的草稿 | 草稿创建者并校验动作权限 |
| POST /action-drafts/{draftId}/invalidate | 原因 | 草稿创建者 |

订单与工单可跨客服共享，会话和草稿保持创建者所有。仓库人员看到与库存审批有关的订单字段时仅展示业务必要信息。

手工草稿 typedPayload 包含 orderId 可空、category、title、description、priority；服务器设置 origin=MANUAL，不接受用户填写 runId、tenantId 或引用对象。模型不可用时，工作台通过此接口再确认草稿创建工单。该 POST 要求 Idempotency-Key。

运行请求示例：

~~~json
{
  "requestId": "b97579a5-dc9f-4504-9a68-38dcd47252e8",
  "message": "订单 AF20260926001 超过预计送达时间，请帮我整理售后工单"
}
~~~

返回 202，data 包含 runId、status、eventsUrl。相同 requestId 与相同输入返回已创建运行；不同输入返回 409。单次输入最多 4000 个字符，单会话最多一个活跃 QUEUED/RUNNING 运行，超限返回 RUN_ALREADY_ACTIVE。

## 工单与审批接口

| 方法与路径 | 输入或用途 | 权限 |
|---|---|---|
| GET /tickets | orderId、status、category 可选 | 客服、主管、仓库、管理员 |
| GET /tickets/{ticketId} | 工单、事件、关联申请 | 内部角色，对字段脱敏 |
| POST /tickets/{ticketId}/assign | assigneeId、expectedVersion | 主管/管理员可分派；客服仅可领取给自己 |
| POST /tickets/{ticketId}/transition | toStatus、comment、expectedVersion | 分派客服、主管或管理员；按状态规则 |
| GET /sensitive-actions | status、type、ticketId | 申请人、该类型审批角色、管理员 |
| GET /sensitive-actions/{actionId} | 参数、证据快照、审批和执行结果 | 同上 |
| POST /sensitive-actions/{actionId}/decision | decision、reason、expectedVersion | 退款：主管/管理员；调拨：仓库/管理员 |
| GET /transfer-orders | status、skuId | 仓库、管理员 |
| POST /transfer-orders/{transferId}/cancel | reason、expectedVersion | 仓库、管理员 |

POST /tickets 不作为自由表单创建入口。统一通过草稿确认创建，以保留会话依据、确认记录和幂等边界。审批接口不能接受 orderId、amountCents、skuId 或 quantity 的覆盖值。

客服领取 OPEN 工单后转 ASSIGNED，再通过 transition 开始处理。存在关联待审批申请时不能跳过 WAITING_APPROVAL 直接解决工单。

decision 只能为 APPROVE/REJECT，reason 必填，长度 1–500 字。申请人自批返回 SELF_APPROVAL_FORBIDDEN，版本变化返回 VERSION_CONFLICT。正常批准返回 APPROVED，随后由 outbox 执行，不同步等待外部请求。

库存建议响应示例：

~~~json
{
  "code": "OK",
  "data": {
    "skuId": "uuid",
    "targetWarehouseId": "uuid",
    "quantity": 5,
    "calculatedAt": "2026-09-26T08:00:00Z",
    "candidates": [{
      "sourceWarehouseId": "uuid",
      "available": 20,
      "allocatable": 10,
      "inventoryVersion": 3,
      "estimatedDays": 2,
      "costCentsPerUnit": 300
    }]
  },
  "traceId": "server-trace-id"
}
~~~

## 知识与审计接口

| 方法与路径 | 输入或用途 | 权限 |
|---|---|---|
| POST /knowledge/documents | multipart：file、title、categoryCode、effectiveFrom、effectiveTo 可选 | 管理员 |
| POST /knowledge/documents/{documentId}/versions | 同上，创建新版本 | 管理员 |
| GET /knowledge/documents | 文档、版本与处理状态 | 管理员 |
| GET /knowledge/versions/{versionId} | 状态、片段预览、失败原因 | 管理员 |
| POST /knowledge/versions/{versionId}/publish | 无参数，校验日期与 READY | 管理员 |
| POST /knowledge/versions/{versionId}/archive | reason | 管理员 |
| POST /knowledge/search | query、orderId 可选、categoryCode 可选 | 内部角色，已发布内容 |
| GET /citations/{citationId} | 已保存的引用快照 | 对应会话创建者，或对应工单可见角色 |
| GET /audit-events | traceId、resourceId、action、from、to | 管理员；主管可看本人审批关联记录 |

上传返回 202 与 documentId、versionId、status=UPLOADED，异步处理可轮询版本。发布操作检查同类别、同文档版本有效区间不重叠；若替换当前版本，在单事务设置旧版本 effectiveTo 和新版本 effectiveFrom，不能倒置或抹除历史区间。

## SSE 事件

前端采用 fetch 读取 SSE，携带 Authorization；原生 EventSource 不直接支持自定义令牌请求头。不要把访问令牌放 URL。

~~~text
id: 12
event: citation
data: {"citationId":"uuid","documentTitle":"售后政策","version":2,"page":3}

id: 13
event: answer.delta
data: {"text":"根据购买时适用的售后政策……"}

id: 14
event: answer.final
data: {"answer":"完整答复","citationIds":["uuid"],"draftIds":["uuid"],"needsHuman":true}
~~~

事件：run.started、tool.started、tool.completed、citation、draft.created、answer.delta、answer.final、action.result、run.failed、run.cancelled、run.completed。状态等待确认通过 answer.final 内的 draftIds 表达，不对终止的模型请求保持无限连接。

run_events 是持久化的可见事件，不保存完整思维链。Last-Event-ID 是当前 run 的 sequence，从大于该序号处回放。无事件时每 15 秒发送一次注释心跳。重连校验运行权限，重复事件由前端按 runId+sequence 去重。

若游标超过当前序号返回 INVALID_EVENT_CURSOR；回放数据已清理返回 EVENT_HISTORY_EXPIRED，客户端获取最终运行结果。run.completed、run.failed、run.cancelled 或带草稿的 answer.final 后可以关闭事件流；后续确认结果通过消息刷新和工单/申请详情读取。

取消可用于 QUEUED/RUNNING/WAITING_CONFIRMATION，停止后续模型与查询，把该运行未确认的 PREPARING/READY 草稿转 INVALIDATED；已经确认的工单、审批申请和执行任务保持独立生命周期。

## Agent 工具白名单

工具上下文固定为 ActorContext(tenantId, actorId, role, traceId) 和 runId。以下业务参数均不含 tenantId 或 actorId。

| 工具名 | 输入 | 返回与限制 |
|---|---|---|
| query_order | orderNo | OrderView，精确查询、脱敏 |
| query_logistics | orderId | LogisticsView，事件与确定性异常码 |
| retrieve_policy | query、orderId 可选、categoryCode 可选 | PolicyEvidence，最多 8 个有效片段 |
| query_inventory | skuCode | InventoryView，含版本与计算时间 |
| recommend_transfer | skuId、targetWarehouseId、quantity | TransferRecommendation，最多 3 个方案 |
| draft_ticket | orderId 可空、category、title、description、priority | DraftView，不创建工单 |
| draft_sensitive_action | type、ticketId 可空、typedPayload | DraftView，校验类型与事实，不执行；type 为 REFUND_REQUEST 或 STOCK_TRANSFER |

实际 create_ticket 和 submit_sensitive_action 属于确认服务内部命令，未注册为模型可任意选择的工具。用户确认草稿后，系统执行相应命令并将结果追加到会话，完成 Agent 辅助创建工单的完整交互。

敏感工具的 REFUND_REQUEST 映射为 REQUEST_REFUND 草稿，STOCK_TRANSFER 映射为 REQUEST_STOCK_TRANSFER 草稿；确认后才生成对应 sensitive_actions。工具生成工单草稿时由服务器关联本轮引用，模型不能绑定其他会话的 citationId。

工具返回统一 ToolResult(success, data, errorCode, retryable, traceId)。参数校验失败不重试；只读网络超时最多重试一次。草稿创建用 runId+工具调用 ID 去重，模型重复尝试不产生重复卡片。

Agent 草稿初始 PREPARING，完成答复与证据校验后 READY，并发出 draft.created。PREPARING 不允许确认，返回 409 DRAFT_NOT_READY。工单与申请详情携带 evidenceSnapshot，审批角色可查看快照，不获得私有会话读取权限。

## 错误分类

| HTTP | 错误码示例 | 处理 |
|---|---|---|
| 400 | INVALID_ARGUMENT、INVALID_TRANSITION、INVALID_EVENT_CURSOR | 修正输入 |
| 401 | UNAUTHENTICATED、TOKEN_EXPIRED | 重新登录 |
| 403 | FORBIDDEN、SELF_APPROVAL_FORBIDDEN | 展示无权限，不泄露对象详情 |
| 404 | RESOURCE_NOT_FOUND | 不存在或不在当前商家范围 |
| 409 | VERSION_CONFLICT、IDEMPOTENCY_CONFLICT、INSUFFICIENT_STOCK、DRAFT_ALREADY_INVALIDATED | 刷新业务状态 |
| 410 | DRAFT_EXPIRED、ACTION_EXPIRED、EVENT_HISTORY_EXPIRED | 重新生成或读取最终结果 |
| 429 | RATE_LIMITED、RUN_ALREADY_ACTIVE | 展示重试建议 |
| 503 | MODEL_UNAVAILABLE、DEPENDENCY_UNAVAILABLE | 说明暂不可用并支持手工业务接口 |

失败日志记录 traceId、状态和脱敏参数；客户端不接收数据库堆栈、模型 Key 或系统提示词。
