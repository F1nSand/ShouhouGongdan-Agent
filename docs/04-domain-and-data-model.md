# 领域模型与数据库设计

## 通用约定

主数据库 PostgreSQL 16。业务 ID 为 UUID，由服务端生成；金额使用 bigint 分，数量使用 integer，时间使用 timestamptz，JSON 使用 jsonb。展示用订单号、工单号独立于内部 ID。

除 tenants 外，业务表统一包含 id、tenant_id、created_at、updated_at。审计、事件、工具调用等追加型表只有 created_at；不更新历史内容。tenant_id 来自身份上下文。

业务表以 id 为主键，同时设置 UNIQUE(tenant_id, id)。跨业务表关联使用复合外键 (tenant_id, resource_id)，防止引用其他商家对象。可选关联允许 resource_id 为空。查询和更新 SQL 均带 tenant_id；UUID 不作为权限边界。

## 身份与订单

| 表 | 主要字段与类型 | 约束 |
|---|---|---|
| tenants | id uuid、code varchar(64)、name text、status text | code 唯一；status ACTIVE/DISABLED |
| users | username varchar(64)、password_hash text、role text、status text | UNIQUE(tenant_id, username)；密码哈希；角色见安全文档 |
| customers | display_name text、phone_masked text、external_ref text | 无真实敏感数据的合成演示客户 |
| orders | order_no varchar(64)、customer_id uuid、status text、purchased_at timestamptz、shipped_at timestamptz 可空、paid_amount_cents bigint、refunded_amount_cents bigint、promised_delivery_at timestamptz、version bigint | UNIQUE(tenant_id, order_no)；0 ≤ refunded_amount_cents ≤ paid_amount_cents |
| order_items | order_id uuid、sku_id uuid、quantity integer、unit_price_cents bigint | quantity > 0；unit_price_cents ≥ 0 |
| logistics_events | order_id uuid、source_code text、external_event_id text、carrier_code text、tracking_no text、event_code text、event_time timestamptz、description text | 索引(tenant_id, order_id, event_time DESC)；UNIQUE(tenant_id, source_code, external_event_id) |

订单状态：PAID、SHIPPED、DELIVERED、CANCELLED。首期订单从固定数据导入，客服和 Agent 不能修改订单状态。

物流事件码：PICKED_UP、IN_TRANSIT、OUT_FOR_DELIVERY、DELIVERED、EXCEPTION。shipped_at 来自订单事实；无事件时以它作为物流等待计时起点。状态为 SHIPPED 却缺发货时间时返回数据不足，不能用购买时间代替。演示数据的 source_code=DEMO，external_event_id 为稳定的种子事件编号。

退款模拟不修改真实支付字段，refunded_amount_cents 仅作为退款额度计算的事实输入。

## 商品与库存

| 表 | 主要字段与类型 | 约束 |
|---|---|---|
| skus | sku_code varchar(64)、name text、category_code text、status text | UNIQUE(tenant_id, sku_code) |
| warehouses | code varchar(64)、name text、region_code text、status text | UNIQUE(tenant_id, code) |
| warehouse_routes | source_warehouse_id uuid、target_warehouse_id uuid、estimated_days integer、cost_cents_per_unit bigint | 两仓不同；天数和成本非负；UNIQUE(tenant_id, source_warehouse_id, target_warehouse_id) |
| inventory | warehouse_id uuid、sku_id uuid、on_hand integer、reserved integer、safety_stock integer、version bigint | UNIQUE(tenant_id, warehouse_id, sku_id)；数量非负；reserved ≤ on_hand |
| transfer_orders | action_id uuid、source_warehouse_id uuid、target_warehouse_id uuid、sku_id uuid、quantity integer、status text、version bigint | UNIQUE(tenant_id, action_id)；quantity > 0 |
| inventory_reservations | inventory_id uuid、transfer_order_id uuid、quantity integer、status text、expires_at timestamptz | UNIQUE(tenant_id, transfer_order_id)；status ACTIVE/RELEASED；quantity > 0 |

调拨单首期状态：RESERVED、CANCELLED、EXPIRED。RESERVED 表示本地库存已预留、等待后续实物发运流程，不等于商品已到达目标仓。

建议和执行均采用：

~~~text
available = on_hand - reserved
allocatable = max(available - safety_stock, 0)
~~~

预留时由数据库原子更新进行并发保护，不能依赖 Agent 计算的数量或 Redis 缓存：

~~~sql
UPDATE inventory
SET reserved = reserved + :quantity,
    version = version + 1,
    updated_at = now()
WHERE tenant_id = :tenantId
  AND id = :inventoryId
  AND version = :currentVersion
  AND on_hand - reserved - safety_stock >= :quantity;
~~~

执行器先读取当前库存并取得 currentVersion。影响行数为 0 时重新读取并校验一次；仍失败返回 INVENTORY_CONFLICT 或 INSUFFICIENT_STOCK。预留、调拨单、reservation、成功状态和审计必须在同一事务提交。

预留有效期 24 小时。定时任务将过期 ACTIVE 预留改为 RELEASED，reserved 减去对应 quantity，调拨单改为 EXPIRED，同事务写审计；状态条件保证重复释放无效果。取消操作同样走受控仓库服务并写审计。取消和过期释放先锁定调拨单，条件匹配 RESERVED 与 version，再更新预留状态；只有成功改变 ACTIVE 状态的事务释放数量，调拨单 version 同时递增。首期不实现发运后扣减 on_hand 或目标仓入库。

## 会话与 Agent 运行

| 表 | 主要字段与类型 | 约束 |
|---|---|---|
| conversations | owner_user_id uuid、title text、status text | 首期仅创建者可读取会话 |
| messages | conversation_id uuid、run_id uuid 可空、role text、content text、structured_answer jsonb | role USER/ASSISTANT；内容持久化后用于展示 |
| agent_runs | conversation_id uuid、actor_id uuid、request_id uuid、input_hash char(64)、status text、deadline_at timestamptz、trace_id varchar(64)、error_code text 可空 | UNIQUE(tenant_id, conversation_id, request_id) |
| run_events | run_id uuid、sequence bigint、event_type text、payload jsonb | UNIQUE(tenant_id, run_id, sequence)；按顺序追加 |
| tool_invocations | run_id uuid、invocation_id varchar(128)、tool_name text、input_summary jsonb、output_summary jsonb、status text、duration_ms bigint、error_code text 可空 | UNIQUE(tenant_id, run_id, invocation_id)；不记录完整密钥或原始个人信息 |

运行状态：QUEUED、RUNNING、WAITING_CONFIRMATION、COMPLETED、FAILED、CANCELLED。

WAITING_CONFIRMATION 表示回答已生成操作草稿，Agent 不持有数据库事务等待人操作。确认由普通命令接口完成并向会话追加操作结果；不恢复一个长期悬挂的模型请求。多份草稿全部确认或失效后，运行转 COMPLETED。

服务重启后遗留 QUEUED/RUNNING 运行转 FAILED，原因 SERVER_RESTARTED；客户端可发起新运行。WAITING_CONFIRMATION 的草稿仍按有效期处理。敏感操作执行任务按独立 outbox 恢复。

## 草稿、工单、审批与幂等

| 表 | 主要字段与类型 | 约束 |
|---|---|---|
| action_drafts | origin text、run_id uuid 可空、invocation_id varchar(128) 可空、conversation_id uuid 可空、created_by uuid、type text、payload jsonb、payload_hash char(64)、evidence_snapshot jsonb、status text、expires_at timestamptz、confirmed_result jsonb 可空 | origin AGENT/MANUAL；status PREPARING/READY/CONFIRMED/INVALIDATED/EXPIRED；默认有效期 30 分钟 |
| tickets | ticket_no varchar(64)、draft_id uuid、source_run_id uuid 可空、order_id uuid 可空、category text、title varchar(120)、description text、priority text、status text、evidence_snapshot jsonb、created_by uuid、assigned_to uuid 可空、resolution text 可空、version bigint | UNIQUE(tenant_id, ticket_no)；UNIQUE(tenant_id, draft_id) |
| ticket_events | ticket_id uuid、actor_id uuid、event_type text、from_status text 可空、to_status text 可空、comment text | 追加记录 |
| sensitive_actions | draft_id uuid、ticket_id uuid 可空、requested_by uuid、type text、payload jsonb、payload_hash char(64)、evidence_snapshot jsonb、status text、expires_at timestamptz、execution_result jsonb 可空、error_code text 可空、version bigint | type REFUND_REQUEST/STOCK_TRANSFER；UNIQUE(tenant_id, draft_id)；默认有效期 24 小时 |
| approval_decisions | action_id uuid、decided_by uuid、decision text、reason text、payload_hash char(64) | UNIQUE(tenant_id, action_id)；decision APPROVE/REJECT |
| command_dedup | actor_id uuid、command_name text、idempotency_key varchar(128)、request_hash char(64)、result jsonb、status text | UNIQUE(tenant_id, actor_id, command_name, idempotency_key) |
| outbox_jobs | aggregate_id uuid、job_type text、status text、attempts integer、available_at timestamptz、lease_until timestamptz 可空、lease_owner text 可空、lease_version bigint、last_error text 可空 | UNIQUE(tenant_id, aggregate_id, job_type)；status READY/LEASED/DONE/FAILED |

工单状态机：

~~~mermaid
stateDiagram-v2
    [*] --> OPEN
    OPEN --> ASSIGNED: 分派
    ASSIGNED --> IN_PROGRESS: 开始处理
    IN_PROGRESS --> WAITING_APPROVAL: 提交敏感申请
    WAITING_APPROVAL --> IN_PROGRESS: 申请终态
    IN_PROGRESS --> RESOLVED: 写入解决摘要
    RESOLVED --> CLOSED: 确认归档
    RESOLVED --> IN_PROGRESS: 重新处理
~~~

申请终态包括 REJECTED、EXPIRED、FAILED、SUCCEEDED、SIMULATED_SUCCEEDED。业务更新通过专用 transition 方法校验，不接受任意 status 覆盖。

关联工单的敏感申请只能在工单 IN_PROGRESS 或 WAITING_APPROVAL 时提交。客服先领取工单，再开始处理。存在未结束申请时保持 WAITING_APPROVAL，最后一个关联申请进入终态后转 IN_PROGRESS；不能因其中一项完成就忽略其他待审批申请。

敏感操作状态机：

~~~mermaid
stateDiagram-v2
    [*] --> PENDING_APPROVAL
    PENDING_APPROVAL --> APPROVED
    PENDING_APPROVAL --> REJECTED
    PENDING_APPROVAL --> EXPIRED
    APPROVED --> EXECUTING
    APPROVED --> EXPIRED
    EXECUTING --> SUCCEEDED: 本地库存预留
    EXECUTING --> SIMULATED_SUCCEEDED: 模拟退款
    EXECUTING --> FAILED
~~~

APPROVED → EXECUTING 前验证申请未过期。EXECUTING 后由任务幂等恢复，不因为审批期限经过就强行改为 EXPIRED。首期失败后新建草稿和新申请，不提供直接把 FAILED 状态改回 APPROVED 的接口。

草稿 payload 一经生成不可修改。确认时按服务器保存的 payload 执行，只接受 draftId；修改业务参数要生成新草稿。退款 payload 保存 orderId、amountCents、reason；调拨保存 skuId、sourceWarehouseId、targetWarehouseId、quantity、suggestedInventoryVersion 和 recommendationSnapshot，后者保存 calculatedAt、allocatable、estimatedDays、costCentsPerUnit。

AGENT 草稿必须有 run_id、invocation_id 和 conversation_id，设置部分唯一键 UNIQUE(tenant_id, run_id, invocation_id) WHERE origin='AGENT'。模型创建后先 PREPARING，保存校验后的回答与证据后转 READY；回答失败则 INVALIDATED。MANUAL 草稿只支持 CREATE_TICKET，run_id 和 invocation_id 可空，按命令去重后直接 READY。

evidence_snapshot 保存服务器验证过的政策标题、版本、chunkId、引用文字、定位，以及必要的订单或库存事实；缺证据时保存明确的缺失原因。确认时将快照复制到工单或申请，使审批人员可以看到申请时的依据。确认不能接受客户端替换证据。ticket_citations 关联最终回答中的实际引用；审批页主要通过 sensitive_actions.evidence_snapshot 阅读依据，不需要访问申请人的私有会话。

## 知识与审计

| 表 | 主要字段与类型 | 约束 |
|---|---|---|
| knowledge_documents | title text、category_code text、status text、current_published_version_id uuid 可空 | 文档主体；status ACTIVE/ARCHIVED |
| knowledge_versions | document_id uuid、version_no integer、content_hash char(64)、file_path text、content_type text、status text、effective_from timestamptz、effective_to timestamptz 可空、published_at timestamptz 可空 | UNIQUE(tenant_id, document_id, version_no)；有效期左闭右开，结束须晚于开始 |
| knowledge_chunks | version_id uuid、chunk_index integer、content text、page_no integer 可空、section_title text 可空、embedding vector(1024) 可空、embedding_model text | UNIQUE(tenant_id, version_id, chunk_index) |
| answer_citations | run_id uuid、message_id uuid、chunk_id uuid、source_snapshot jsonb | 保留标题、版本、定位、引用文字与有效时间快照 |
| ticket_citations | ticket_id uuid、citation_id uuid | UNIQUE(tenant_id, ticket_id, citation_id)；确认时仅绑定 source_run_id 中实际保存的引用 |
| audit_events | actor_id uuid 可空、actor_role text、trace_id text、action text、resource_type text、resource_id uuid 可空、result text、parameter_summary jsonb、reason text 可空 | 追加写；应用账号无更新/删除权限 |

知识版本状态：UPLOADED、PROCESSING、READY、PUBLISHED、FAILED、ARCHIVED。仅管理员可发布 READY 版本；知识任务使用 outbox_jobs 的 KNOWLEDGE_INGEST 类型。

同文档、同 content_hash 的重复上传复用已有版本，不重复入库。若元数据或生效日期需要变化，创建一个新的版本记录，再复用文字提取结果。不同文档相同内容允许存在，避免不同政策主体被误合并。

历史已发布版本保留 PUBLISHED 状态与生效时间区间，通过 current_published_version_id 标识当前版本。历史订单依然可以检索当时有效的 PUBLISHED 版本；停用政策改为 ARCHIVED 后不用于新检索，既有引用快照保留。

## 索引与事务

向量索引按余弦距离 HNSW 建立；检索必须带 tenant_id、有效期和版本状态条件。演示数据量较小时允许精确扫描，不以近似索引牺牲商家隔离或漏掉过滤后的有效片段。

其他关键索引：工单(tenant_id, status, created_at)、申请(tenant_id, status, expires_at)、草稿(tenant_id, status, expires_at)、outbox(status, available_at)、审计(tenant_id, trace_id, created_at)。

确认事务：锁定 READY 草稿 → 校验用户和有效期 → 写工单或申请 → 保存结果 → 草稿 CONFIRMED → 审计 → 提交。审批事务：状态条件更新 → 审批记录 → outbox → 审计 → 提交。

command_dedup 用于命令请求级幂等，业务唯一约束用于效果级幂等。相同 Key 与不同 request_hash 返回冲突。记录保留至少 90 天；业务唯一约束保留到对象生命周期结束。
