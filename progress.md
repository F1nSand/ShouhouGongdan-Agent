# 进度账本 — 实施计划与后续验收

> 最新状态（2026-10-01）：用户批准的本地 Docker Desktop 演示方案 P1–P7 已完成。四项服务在 `127.0.0.1:38080` 运行；mock、真实模型、退款审批、镜像导出/导入和配对数据恢复均有记录。真实请求本次共 10 次；完整 mock 评估 108/140 通过，32 失败、20 条审批样例未执行，不能作为模型质量分数。详见[本机验收报告](docs/reports/2026-10-01-acceptance.md)及[执行账本](docs/plans/2026-09-30-local-docker-demo-execution-log.md)。

# 历史进度账本 — plan: docs/plans/2026-09-26-aftersales-agent-implementation.md

目标：按实施计划交付可本地运行的售后助手、业务 API、可视化工作台、测试与运维材料。执行记录只写已实际运行的命令和观察结果。

- Task 1: code complete; Compose runtime integration pending — Wrapper 命令测试与前端生产构建通过；PostgreSQL/pgvector 容器测试通过，Compose 全栈启动尚未执行。
- Task 2: complete — 空库迁移、审计写入权限、跨商家对象隔离、JWT 身份和重复种子导入均有容器/HTTP 验证。
- Task 3: complete — 订单/物流查询、库存查询、调拨建议接口和确定性业务规则已实现。
- Task 4: complete — PDF/TXT/Markdown 入库、outbox 切块与 embedding、版本发布、订单日期政策检索、租户/状态隔离、证据引用校验；mock 与 live DashScope embedding provider 已接入。
- Task 5: complete — 操作草稿、工单确认、状态机、工单查询/分派/事件和审计已实现；确认接口与并发/租户边界验证通过。
- Task 6: complete — 角色审批、自批禁止、事务 outbox、模拟退款、库存原子预留、租约接管/续期、重试退避及过期释放已实现并验证。
- Task 7: complete — mock/live 意图识别、白名单工具、会话和运行 API、SSE 回放、答复引用校验、草稿确认事件和运行恢复已实现并验证。
- Task 8: complete — Vue 工作台、REST/SSE 接入、工单/库存/审批/知识库/审计页面均已实现；审计查询 API 按管理员和商家范围保护。
- Task 9: in_progress — 评测数据、CI、部署配置、报告和演示材料已完成；Docker Compose 实际启动、4核8GB性能测试和真实模型评测依赖当前环境缺失的工具/Key，未标记通过。

Task 9 当前验证（2026-09-27）：新增可确定性重建的 120 条业务与 40 条安全对抗 JSONL 语料、三份管理员手动导入的合成演示政策、随机本地环境生成器、Compose 持久政策卷/回环绑定/SSE Nginx 路由、可选择 mock/live 的 Compose profile、无真实模型 Key 的 CI workflow 和七分钟演示材料。Luna 只读审查发现的验证报告缺失与 live 配置不匹配问题已修正。发现一处硬编码 Authorization Bearer 凭据候选，当前代码改为可选读取 MCP_AUTH_TOKEN；不在日志或报告中保留其值。

Task 9 验证：mvn -Dmaven.repo.local=.m2/repository -pl backend -am test → PASS（52 tests，0 failures/errors/skips；Testcontainers PostgreSQL 16 + pgvector，Flyway v1–v4）。npm run test:unit → PASS（3 tests）；npm run build → PASS（Vite 7.3.6）；npm run test:e2e → PASS（3 tests，确定性 HTTP/SSE stub）；npm audit --audit-level=high → PASS（0 vulnerabilities）。评测 JSONL 解析检查 → PASS（160 条、唯一 caseId、字段完整）；本地环境脚本临时运行检查 → PASS（JWT 为 32 字节标准 Base64，迁移用户名密码与 Postgres 初始化账号一致），随后删除临时 .env。Markdown 本地链接检查覆盖 15 个文件且均存在，git diff --check 无空白错误。

Task 9 未完成门槛：Docker/Compose CLI 未安装，无法跑完整 Compose 首启、应用重启与政策卷持久性、Nginx SSE 代理；机器为 18 个逻辑 CPU/约 31.47 GiB，而非 4核8GB，未生成 10 并发/100 预热 P95；未配置 DASHSCOPE_API_KEY，未执行最多 50 次真实模型评测。Playwright 使用 stub，不代表前端到真实后端联调。详见 docs/reports/2026-09-27-validation.md。

环境基线（2026-09-27）：Java 21.0.11、Maven 3.9.16、Node v24.18.0、npm 11.16.0；Docker CLI 未在 PATH，但 Testcontainers 成功连上 Docker Desktop Engine；未发现本地 PostgreSQL 工具。

Task 1 验证：`mvnw.cmd -pl backend -am test` → PASS（2 tests）；`npm ci --no-audit --no-fund` → PASS（47 packages）；`npm run build` → PASS。Maven 与前端 build 在沙箱外通过，因 Windows JDK/Node 子进程对沙箱工作区路径的文件解析限制。Docker compose 运行验证尚未执行（`docker` 命令不存在）。

Task 2 验证：`mvn -Dmaven.repo.local=.m2/repository -pl backend -am test` → PASS（14 tests，0 failures/errors/skips）。包括 Testcontainers 空库 Flyway 迁移与 vector(1024) 类型检查、审计表运行角色不可更新/删除、跨商家订单 API 返回 404、AuthService 商家/角色绑定、演示种子重复执行无重复记录。

Task 3 验证：同一完整 Maven 命令 PASS（14 tests）。`LogisticsRulesTest` 3 cases；`TransferRecommendationTest` 3 cases；`DemoSeedIT` 经 HTTP 查询 3 仓库存并获取确定性调拨候选；`OrderTenantIsolationIT` 验证 ID 和订单号查询都受 token 商家范围限制。种子 SQL 时间参数已转换为 JDBC 可绑定的 `Timestamp`。

Task 4 验证（2026-09-27）：`mvn -Dmaven.repo.local=.m2/repository -pl backend -am test` → PASS（22 tests，0 failures/errors/skips）；Testcontainers PostgreSQL/pgvector 从空库应用 Flyway v1–v3（含 HNSW 索引）。`KnowledgeWorkflowIT` 3 cases 覆盖重复入库、扫描 PDF 失败原因、按订单购买日期命中历史政策并排除草稿及其他租户；引用校验和文本切分/embedding 单测通过。mock profile 的 DashScope 自动配置关闭，mock 相似度阈值为 0.60，live 默认阈值仍为 0.65。

Task 5 验证（2026-09-27）：`mvn -Dmaven.repo.local=.m2/repository -pl backend -am -Dtest=TicketTransitionTest,DraftConfirmationIT test` → PASS（10 tests，0 failures/errors/skips）；随后完整后端 `mvn -Dmaven.repo.local=.m2/repository -pl backend -am test` → PASS（32 tests，0 failures/errors/skips）。测试使用 Testcontainers PostgreSQL/pgvector 并从空库应用 Flyway v1–v3。首次验证发现测试 DTO 导入缺失，修正后暴露 HTTP 确认用例遗漏 `Idempotency-Key` 请求头；补齐后目标用例与完整套件均通过。静态审查修正了重复 Agent 草稿误写创建审计和通过 transition 直接进入 ASSIGNED 造成无处理人的问题。

Task 6 验证（2026-09-27）：目标测试 `mvn -Dmaven.repo.local=.m2/repository -pl backend -am -Dtest=ApprovalAuthorizationIT,InventoryConcurrencyIT,ActionRecoveryIT,RefundSimulationTest,DraftConfirmationIT test` → PASS（15 tests，0 failures/errors/skips）；完整后端 `mvn -Dmaven.repo.local=.m2/repository -pl backend -am test` → PASS（41 tests，0 failures/errors/skips）。Testcontainers PostgreSQL/pgvector 从空库应用 Flyway v1–v3。覆盖客服审批拒绝、申请人自批拒绝、并发决定单赢家、确认调拨只建待审批申请、实存 10/安全库存 2 时 20 个并发调拨只成功 8 个、重复执行单一调拨/预留、60 秒租约接管和 fencing、1/5/30 秒重试、退款模拟不修改支付金额、24 小时预留过期后只释放一次。修正恢复测试的可用时间夹具后全套通过；Testcontainers 类禁用了自动 worker 并关闭各自 Spring 上下文，避免容器关闭后后台调度继续访问。

Task 5 接口另按 API 文档调整为 `type + typedPayload`；人工确认接口仅接收 draftId 和 Idempotency-Key。`git diff --check` 未报告 whitespace 错误（仅提示既有文件换行符规范化）。

Task 7 验证（2026-09-27）：目标测试 `mvn -Dmaven.repo.local=.m2/repository -pl backend -am -Dtest=AgentBudgetTest,ToolGatewayTest,PromptInjectionTest,SseReplayIT,AgentRunFlowIT -Dsurefire.failIfNoSpecifiedTests=false test` → PASS（9 tests，0 failures/errors/skips）；完整后端 `mvn -Dmaven.repo.local=.m2/repository -pl backend -am test` → PASS（50 tests，0 failures/errors/skips）。PostgreSQL/pgvector Testcontainers 从空库应用 Flyway v1–v4。覆盖 6/8/60 秒预算边界、身份上下文不可被工具参数覆盖、模型工具白名单、恶意政策不能授权审批、游标 12 只回放 13/14 与会话所有者隔离、mock 订单查询整链、工单确认前不落工单及确认后 action.result 消息/事件事务写入。配置模型调用 20 秒、只读工具 5 秒、JDBC 查询 3 秒超时；并用行锁和条件状态迁移处理运行取消/完成竞态。首次完整回归发现无数据库 smoke profile 会误触发重启恢复查询；增加可配置恢复开关后 smoke 用例及全套回归通过。
- Task 8 验证（2026-09-27）：`npm run test:unit` → PASS（3 tests）；`npm run build` → PASS；`npm run test:e2e` → PASS（3 tests，售后工单/退款审批/模拟结果，库存建议/调拨审批，客服隐藏入口和直接请求 403）。`mvn -Dmaven.repo.local=.m2/repository -pl backend -am test` 在审计集成测试加入前 → PASS（50 tests）；新 `AuditQueryIT` 目标运行 → PASS（2 tests），验证管理员角色、跨商家隔离与分页。首轮 E2E 发现用户和助手消息重复显示同一草稿、审批后待处理列表隐藏已执行结果；分别修正草稿归属和审批后自动切换全部记录，再全流程通过。前端使用真实 HTTP/SSE 契约，E2E 的后端接口由确定性路由 stub 驱动。
