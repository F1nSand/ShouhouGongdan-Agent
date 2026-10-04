# 智能售后工单与库存调度 Agent 实施计划

日期：2026-09-26。状态：Task 9 部分验收项依赖 Docker Compose、目标硬件和 live API Key，尚未完成。各任务状态以 `progress.md` 为准。

## 目标与架构

交付一个可本地运行、带可视化工作台的 Java 售后助手：订单物流查询、政策检索引用、工单确认创建、库存调拨建议、人工审批和操作审计。

采用单 Spring Boot 服务、Vue 工作台、PostgreSQL + pgvector、Redis；Agent 与业务命令分离。先把确定性业务接口与事务做正确，再接入模型和工作台。

## 全局约束

- Java 21；Spring Boot 3.5.8；Spring AI 1.1.2；Agent Framework 1.1.2.0；Extensions 1.1.2.1。
- 业务包 com.aftercare，后端 backend，前端 frontend，部署 infra。
- 所有业务记录、查询、检索与更新受 tenant_id 和对象权限保护。
- 金额以分存储，数量为整数，时间为 UTC。
- 模型最多 6 次调用、工具最多 8 次调用、自动运行最多 60 秒，重试计入预算。
- 首期退款为模拟执行；调拨执行只创建本地单据并预留库存。
- 确认工单后才能写入；退款和库存预留必须通过对应角色审批。
- 实际写命令不注册为模型自由调用工具。
- 数据库事务、业务唯一键和 command_dedup 联合实现幂等。
- 默认测试 profile 使用 mock，不自动调用实时付费模型。

## 文件与接口约定

以下 Files 按目标路径创建。测试命令在 Maven Wrapper 与前端构建完成后运行。每个阶段先落实列出的失败测试，再实现，再运行同一测试确认通过；阶段收尾执行 review-test-simplify，对功能、权限、必要测试与复杂度审阅。

实施进度独立使用 progress.md，首行更新为本计划路径。完成一项必须记录真实测试命令、输出结论及变更范围；不凭设计文档标记开发完成。

## Task 1：工程与运行基础

**Files**

- Create: pom.xml、.mvn/wrapper/maven-wrapper.properties、mvnw、mvnw.cmd。
- Create: backend/pom.xml、backend/src/main/java/com/aftercare/AftersalesApplication.java。
- Create: backend/src/main/resources/application.yml、application-mock.yml、application-live.yml。
- Create: frontend/package.json、package-lock.json、vite.config.ts、src/main.ts。
- Create: infra/compose.yaml、backend/Dockerfile、frontend/Dockerfile、infra/nginx/default.conf、.env.example。
- Modify: .gitignore，加入 .env、target、node_modules、上传和报告原始数据的适当忽略规则。

**Interfaces**

- Produces: backend 8081，web 8080，前端开发 5173。
- Produces: mock profile 可在无 DASHSCOPE_API_KEY 时启动。
- Produces: PostgreSQL + pgvector 与 Redis 的健康检查。

**Steps**

- [ ] 固定 Maven/BOM 与前端依赖，提交完整 Wrapper 和锁文件。
- [ ] 创建 ApplicationSmokeTest.mockStartsWithoutModelKey：清空模型 Key，以 mock profile 启动，liveness 返回 UP。
- [ ] 创建 DependencyConfigTest.embeddingDimensionRejectsMismatch：维度不为 1024 时启动校验失败，错误可读。
- [ ] 配置有界执行器、UTC 时钟、日志 traceId 和受保护的 Actuator。
- [ ] 执行 ./mvnw.cmd -pl backend -am test 和前端 npm run build。
- [ ] Docker 启动 web/backend/postgres/redis，验证页面和健康检查。

## Task 2：数据库、身份和商家隔离

**Files**

- Create: backend/src/main/resources/db/migration/V1__identity_order_inventory.sql。
- Create: backend/src/main/resources/db/migration/V2__conversation_workflow_knowledge.sql。
- Create: backend/src/main/java/com/aftercare/identity/{ActorContext,Role,SecurityConfig,AuthService,AuthController}.java。
- Create: backend/src/main/java/com/aftercare/common/{ApiResponse,ApiException,TraceFilter}.java。
- Create: backend/src/main/java/com/aftercare/demo/DemoSeedService.java。
- Create: backend/src/test/java/com/aftercare/identity/TenantIsolationIT.java。

**Interfaces**

~~~java
record ActorContext(UUID tenantId, UUID actorId, Role role, String traceId) {}
AuthResult login(String merchantCode, String username, String password);
ActorContext currentActor();
~~~

- Produces: 数据模型文档列出的表、复合外键、状态约束、业务唯一键。
- Produces: 登录、auth/me、角色和对象权限。
- Produces: 两个商家的可复现隔离 fixture，主演示商家 100 订单、20 SKU、3 仓库。

**Steps**

- [ ] 用 Testcontainers 初始化 PostgreSQL + pgvector，验证完整迁移可从空库执行。
- [ ] TenantIsolationIT.otherTenantOrderIs404：A 令牌请求 B 订单返回 404。
- [ ] AuthIT.loginBindsMerchantAndRole：商家编码定位用户，令牌绑定正确商家和角色。
- [ ] AuditPrivilegeIT.runtimeCannotUpdateAuditHistory：运行账号追加成功，更新/删除失败。
- [ ] 实现 BCrypt、30 分钟令牌、用户状态、限流与统一错误。
- [ ] 设计种子幂等导入，不覆盖已有工单或库存预留。
- [ ] 执行 ./mvnw.cmd -pl backend -am test。

## Task 3：订单、物流、库存与建议

**Files**

- Create: backend/src/main/java/com/aftercare/order/{OrderService,OrderRepository,OrderController,OrderView}.java。
- Create: backend/src/main/java/com/aftercare/logistics/{LogisticsService,LogisticsRules,LogisticsView}.java。
- Create: backend/src/main/java/com/aftercare/inventory/{InventoryService,InventoryRepository,TransferRecommendationService,InventoryController}.java。
- Create: backend/src/test/java/com/aftercare/logistics/LogisticsRulesTest.java。
- Create: backend/src/test/java/com/aftercare/inventory/TransferRecommendationTest.java。

**Interfaces**

~~~java
OrderView query(ActorContext actor, String orderNo);
LogisticsView queryLogistics(ActorContext actor, UUID orderId);
InventoryView queryInventory(ActorContext actor, String skuCode);
TransferRecommendation recommend(ActorContext actor, UUID skuId,
    UUID targetWarehouseId, int quantity);
~~~

**Steps**

- [ ] logisticsNoUpdateAtBoundary：48 小时不异常，49 小时返回 NO_UPDATE。
- [ ] logisticsOverdueAndCarrierException：分别验证承诺日期与承运事件原因可并存。
- [ ] recommendationRespectsSafetyStock：on_hand=20、reserved=5、safety_stock=3，allocatable=12，申请 13 不产生方案。
- [ ] recommendationSortsDeterministically：相同时间按成本再仓库 ID 排序。
- [ ] 实现确定性规则、脱敏 DTO、异常原因和路线排序。
- [ ] 查询缓存包含商家与资源 ID，执行路径不使用缓存库存。
- [ ] 执行 ./mvnw.cmd -pl backend -am test。

## Task 4：政策导入、发布与 RAG

**Files**

- Create: backend/src/main/java/com/aftercare/knowledge/{DocumentService,KnowledgeIngestionWorker,TextChunker,PolicyRetriever,CitationValidator,KnowledgeController}.java。
- Create: backend/src/main/java/com/aftercare/knowledge/{KnowledgeRepository,PgVectorRepository,EmbeddingProvider}.java。
- Create: backend/src/test/java/com/aftercare/knowledge/{PolicyVersionIT,CitationValidatorTest,KnowledgeIngestionIT}.java。
- Create: backend/src/test/resources/policies/ 文本、历史版本与扫描 PDF fixture。

**Interfaces**

~~~java
KnowledgeVersionView upload(ActorContext actor, UploadPolicyCommand command);
void publish(ActorContext actor, UUID versionId);
PolicyEvidence retrieve(ActorContext actor, String query, UUID orderId,
    String categoryCode);
ValidatedAnswer validate(AssistantAnswer answer, PolicyEvidence evidence);
~~~

**Steps**

- [x] PolicyVersionIT.oldOrderUsesOldPolicy：KnowledgeWorkflowIT 验证旧购买日期只命中旧有效区间。
- [x] PolicyVersionIT.unpublishedAndOtherTenantExcluded：KnowledgeWorkflowIT 验证草稿和跨商家片段均排除。
- [x] CitationValidatorTest.rejectsUnknownChunkAndFakeQuote：无证据 ID 或伪引用拒绝。
- [x] KnowledgeIngestionIT.duplicateUploadDoesNotDuplicateChunks：KnowledgeWorkflowIT 验证重复上传不增加片段。
- [x] KnowledgeIngestionIT.scannedPdfHasReadableFailure：KnowledgeWorkflowIT 验证空文本扫描文件转 FAILED 并返回 EMPTY_TEXT。
- [x] 实现 PDFBox 解析、500/800/100 字符切分规则、1024 维校验、outbox 导入。
- [x] 实现发布事务、有效区间、余弦检索、引用快照。
- [x] 执行完整后端测试套件（Maven 本地仓库在工作区）：22 tests 全部通过；Testcontainers 从空库验证 Flyway v1–v3 与 mock 向量检索。

## Task 5：操作草稿与工单确认

**Files**

- Create: backend/src/main/java/com/aftercare/ticket/{TicketService,TicketRepository,TicketController,TicketStateMachine}.java。
- Create: backend/src/main/java/com/aftercare/approval/{ActionDraftService,ActionDraftRepository,DraftController,CommandDedupService}.java。
- Create: backend/src/main/java/com/aftercare/audit/{AuditService,AuditRepository}.java。
- Create: backend/src/test/java/com/aftercare/ticket/{DraftConfirmationIT,TicketTransitionTest}.java。

**Interfaces**

~~~java
DraftView createDraft(ActorContext actor, UUID runId, DraftCommand command);
DraftView createManualDraft(ActorContext actor, ManualTicketDraftCommand command,
    String idempotencyKey);
ConfirmedActionResult confirm(ActorContext actor, UUID draftId,
    String idempotencyKey);
TicketView transition(ActorContext actor, UUID ticketId, TicketStatus target,
    String comment, long expectedVersion);
~~~

**Steps**

- [x] DraftConfirmationIT.repeatedConfirmReturnsSameTicket：10 次并发确认只一个 tickets 行。
- [x] DraftConfirmationIT.changedIdempotentInputIsConflict：相同 Key 不同资源返回 409。
- [x] DraftConfirmationIT.confirmDoesNotAcceptPayloadOverride：确认 DTO 无金额或业务参数覆盖字段。
- [x] DraftConfirmationIT.manualDraftWorksWithoutRun：无模型、无 runId 时，人工草稿经确认创建一个工单。
- [x] DraftConfirmationIT.preparingDraftCannotConfirm：未校验的 Agent 草稿返回 DRAFT_NOT_READY。
- [x] TicketTransitionTest.resolutionRequiresSummary：缺摘要不能转 RESOLVED，非法路径不能更新。
- [x] 实现草稿锁定、30 分钟过期、不可修改 payload、证据快照、唯一结果和审计事务。
- [x] 实现工单列表、分派、处理事件及状态机。
- [x] 执行后端完整测试套件：32 tests 全部通过；目标用例单独运行 10 tests 全部通过。

## Task 6：敏感审批、执行与库存事务

**Files**

- Create: backend/src/main/java/com/aftercare/approval/{SensitiveActionService,ApprovalService,ApprovalController,ActionExecutor,RefundSimulationAdapter}.java。
- Create: backend/src/main/java/com/aftercare/jobs/{OutboxRepository,OutboxWorker,ExpirationWorker}.java。
- Create: backend/src/main/java/com/aftercare/inventory/{InventoryReservationService,TransferOrderService}.java。
- Create: backend/src/test/java/com/aftercare/approval/{ApprovalAuthorizationIT,ActionRecoveryIT}.java。
- Create: backend/src/test/java/com/aftercare/inventory/InventoryConcurrencyIT.java。

**Interfaces**

~~~java
DecisionResult decide(ActorContext actor, UUID actionId, Decision decision,
    String reason, long expectedVersion, String idempotencyKey);
ExecutionResult execute(UUID tenantId, UUID actionId);
TransferOrderView reserveForApprovedAction(UUID tenantId, UUID actionId);
int releaseExpiredReservations(Instant now);
~~~

**Steps**

- [x] ApprovalAuthorizationIT.csrCannotApproveAndRequesterCannotSelfApprove：角色及自批均拒绝。
- [x] ApprovalAuthorizationIT.concurrentDecisionsHaveOneWinner：批准/拒绝并发只有一个决定。
- [x] InventoryConcurrencyIT.twentyReservationsKeepSafetyStock：实存10、安全库存2、20次独立申请，各预留1，恰好8次成功。
- [x] ActionRecoveryIT.duplicateExecutionHasOneEffect：同 actionId 重试只有一个调拨单和 reservation。
- [x] ActionRecoveryIT.workerCrashRecoveredAfterLease：领取后模拟崩溃，60秒租约过期可接管。
- [x] RefundSimulationTest.noPaymentMutation：结果带 simulation=true，退款总额不变。
- [x] 实现批准事务、outbox 租约、续租、业务复核和1/5/30秒重试。
- [x] 实现24小时预留到期释放与申请过期处理；重复释放只影响一次。
- [x] 执行后端完整测试套件：41 tests 全部通过；任务 6 的 15 个目标测试全部通过。

## Task 7：Agent 工具、运行与 SSE

**Files**

- Create: backend/src/main/java/com/aftercare/agent/{AgentRunService,AgentController,ToolGateway,AgentOrchestrator,RunEventService,AnswerAssembler}.java。
- Create: backend/src/main/java/com/aftercare/agent/{MockChatProvider,DashScopeChatProvider,ConversationService}.java。
- Create: backend/src/test/java/com/aftercare/agent/{ToolGatewayTest,AgentBudgetTest,SseReplayIT,PromptInjectionTest}.java。

**Interfaces**

~~~java
AgentRunView start(ActorContext actor, UUID conversationId,
    UUID requestId, String message);
ToolResult execute(ActorContext actor, UUID runId, String invocationId,
    String toolName, JsonNode parameters);
Flux<ServerSentEvent<VisibleRunEvent>> events(ActorContext actor,
    UUID runId, long afterSequence);
~~~

**Steps**

- [x] ToolGatewayTest.identityCannotBeOverridden：工具入参携带身份字段不改变绑定上下文。
- [x] ToolGatewayTest.writeAndApprovalToolsNotRegistered：工具白名单无真实写入/批准命令。
- [x] AgentBudgetTest.callsAndDeadlineAreBounded：第7次模型、第9次工具或60秒后均停止。
- [x] SseReplayIT.reconnectGetsOnlyLaterEvents：从12回放只取大于12，跨会话访问拒绝。
- [x] PromptInjectionTest.policyCannotApproveAction：恶意政策不生成敏感执行资格。
- [x] 实现先校验回答再推送内容，工具进度实时发送，保存引用和最终结果。
- [x] 实现 WAITING_CONFIRMATION 与草稿确认后的会话事件。
- [x] 服务重启对未完成模型运行标记 SERVER_RESTARTED，独立审批任务继续恢复。
- [x] 执行后端完整套件：50 tests 全部通过；目标测试 9 tests 全部通过，并完成 mock 订单查询/工单确认整链验证。

## Task 8：可视化工作台

**Files**

- Create: frontend/src/views/{LoginView,WorkspaceView,TicketListView,TicketDetailView,InventoryView,ApprovalView,KnowledgeView,AuditView}.vue。
- Create: frontend/src/components/{CitationPanel,ActionDraftCard,ToolProgress,StatusBadge}.vue。
- Create: frontend/src/api/{http,agent,tickets,inventory,approvals,knowledge}.ts。
- Create: frontend/src/stores/{auth,conversation}.ts、src/router/index.ts。
- Create: frontend/tests/e2e/{aftersales,inventory,permissions}.spec.ts。

**Interfaces**

- Consumes: 接口文档定义的 REST、状态枚举与 SSE。
- Produces: 用户确认与审批按钮，对象详情、引用与错误恢复。

**Steps**

- [x] Vitest 验证事件序号去重、金额分转元和状态文案。
- [x] Playwright.aftersales：客服查询→确认唯一工单→提交退款→主管批准→模拟执行标识。
- [x] Playwright.inventory：建议→审批→库存预留前后数量与单据。
- [x] Playwright.permissions：客服无审批入口、直接接口也403；后端审批权限和自批限制由 Task 6 集成用例覆盖。
- [x] 实现 fetch SSE 令牌、序号游标重连、401 令牌过期登录提示和消息结果恢复。
- [x] 显示知识版本状态、模拟退款、审批后状态与业务失败原因；工作台提供手工工单备用流程。
- [x] 为已定义的审计查询合同补充管理员只读 API、租户隔离和分页筛选。
- [x] 执行 `npm run test:unit`、`npm run build`、`npm run test:e2e`。

## Task 9：评测、部署与展示

**Files**

- Create: backend/src/test/resources/evaluation/ 120业务与40对抗用例。
- Create: docs/reports/ 实际测试和评测报告。
- Create: .github/workflows/ci.yml。
- Create: scripts/New-LocalEnv.ps1、scripts/Generate-EvaluationCases.mjs、docs/demo/policies/。
- Modify: README.md、docs/README.md、docs/10-deployment-and-operations.md，按实际交付更新命令与状态。

**Interfaces**

- Consumes: 全部模块、固定数据、mock/live profiles、演示脚本。
- Produces: 可复现部署、实测报告、展示证据。

**Steps**

- [x] 新增 CI workflow 运行后端测试、前端单元/构建与 mock 端到端；不注入真实模型 Key。相同命令已在本机执行，远端 GitHub Actions 尚未触发。
- [x] 创建 120 条业务、40 条安全对抗语料及可重建脚本；JSONL 数量、ID 唯一性和必需字段已检查。语料尚未作为 live 模型评测执行。
- [ ] 在4核8GB基线跑10并发、100次预热后请求，按测试文档保存分子、分母与P95。
- [ ] 真实模型评测显式启用，最多50次调用，记录模型、参数、预算和失败案例。
- [ ] 验证空库首次启动、重启恢复、上传文件持久卷和SSE反向代理。当前 Docker/Compose CLI 缺失；Nginx/Compose 配置已实现但未实测。
- [x] 检查仓库凭据特征；一处硬编码 Authorization 值已改为可选读取 MCP_AUTH_TOKEN，复查未发现常见模型/API 密钥格式。
- [x] 完成七分钟演示脚本，并记录当前 HEAD、实际命令、验证报告和限制；在线演示与视频未发布。
- [ ] 根据实测值生成简历表述，不复用目标指标作为成绩。

当前验证记录：docs/reports/2026-09-27-validation.md。完整 Compose、性能和真实模型门槛未通过前，不声称 Task 9 全部完成。

## 最终验收

五个首期功能均可在工作台演示；业务与权限测试通过；幂等、并发和恢复无额外业务效果；政策答复有有效引用；敏感操作的模拟/预留范围明确；启动命令、环境模板、报告与代码相符。

实施过程中变更范围或接口时先同步规格与测试，不在实现中悄悄增加未定义的外部系统或敏感操作。
