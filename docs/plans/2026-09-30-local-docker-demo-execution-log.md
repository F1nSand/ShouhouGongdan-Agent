# 本地 Docker Desktop 演示版执行账本

执行计划：[最终执行计划](2026-09-30-local-docker-demo-plan.md)

## 总状态

- 状态：P1–P7 完成；正式演示栈运行中，验收材料已归档。
- 新增真实模型 HTTP 请求上限：本次执行累计不超过 10 次；失败也计入。
- 保留用户已有 .env、容器、项目及数据卷；不提交、不推送、不发布公网。

## P1：建立可复现基线（完成）

- Docker Engine 29.7.2、Compose v5.3.1 可用；Windows 本地 Java 21.0.11、Node 24.18.0、npm 11.16.0 可用。
- 两个模型 Key 均配置（只检查是否存在，没有读取或输出值）；SPRING_PROFILES_ACTIVE=live,deepseek。
- Docker 中已有 goodskill-demo、literature-workbench、openapi-consistency 等项目运行。宿主机 5432、6379 已占用；演示 Compose 不发布数据库、Redis、后端端口。
- 后端干净打包、前端单测/生产构建与 planner/审批/库存目标回归完成；T9 评测历史失败分类详见下文。基线证据与日志已记录。

## P2：本地演示退款闭环（完成）

- 新增显式 `REFUND_MODE=local-demo`：审批成功后在同一事务中更新虚构订单累计退款额、写幂等本地退款账本、落审批终态与审计；不调用支付接口。默认 `simulation` 行为保持不变。
- 部分/全额状态由订单已退金额和实付金额计算，不改物流状态；审批界面和订单摘要标明本地入账。
- `RefundSimulationTest` 与 `LocalDemoRefundIT`：7 项通过，覆盖部分/全退、幂等重放、并发防超退、未通过审批/跨租户/超额拒绝与账本写入失败回滚；前端生产构建通过。

## P3：每日模型额度（完成）

- 增加配置 `lifetime/daily`，旧构造与配置默认 lifetime；专用 demo 后续显式设置 daily、Asia/Shanghai。
- V7 新增按日期独立表和原子预留。限额错误含恢复时间；Agent run.failed 将包装异常归一为额度错误码；UI 显示每日/累计额度提示。
- 目标集成测试已新增：跨服务重建、上海午夜换桶、并发 100 抢占上限、真实 Spring AI 客户端失败计数、达到上限后请求不出网。
- `ModelRequestBudgetIT`：6 项通过；覆盖旧累计额度保持、按日额度重建、跨上海午夜、100 并发封顶、DeepSeek/Qwen 客户端共用拦截器及失败计数、超额后不出网。初次运行发现新测试将直接抛出的 Spring AI 额度异常误断言为 wrapped root cause；已按实际异常契约修正并单独复跑通过。
- 演示栈启用 `daily/Asia/Shanghai`，V5 lifetime 表旧计数保持 0。所有模型请求结束后，2026-10-01 每日记录为 10/50；本次执行硬上限 10/10。
- 最终全量 `mvnw.cmd -pl backend -am test`：78 项，0 failures、0 errors、1 skipped；唯一 skip 是 `LiveEmbeddingAcceptanceTest`，需显式开启专用环境变量。新增空库 V1–V7 迁移和旧计数升级路径均有 Testcontainers 覆盖。

## P4–P6：Compose、快捷脚本与镜像导入包（完成）

- Docker Engine 29.7.2；四项服务镜像按当前源码构建。BuildKit 在中文工作区路径报非 ASCII 错误，脚本用临时 `subst` ASCII 盘符解决；Maven Central TLS 偶发关闭连接后采用本机 Maven Wrapper 打包 JAR，再做运行时镜像构建。产物并非历史镜像。
- 主栈 `aftersales-local-demo` 健康运行，唯一映射为 `127.0.0.1:38080:80`；DB、Redis、backend 不映射宿主端口。`.env` 保留原文件；密钥不进入构建上下文、镜像包或报告。
- `start.ps1`、`stop.ps1`、`status.ps1`、`init-demo.ps1`、`reset-demo.ps1`、构建/导入/导出脚本均可用。已从项目目录外调用 `status.ps1`。stop/start 后订单退款额、账本、政策向量和预算计数保持。
- mock Playwright `test:e2e` 3/3；前端最终 `test:unit` 3/3、生产 build 成功。
- 镜像导出包位于 `dist/local-demo/`；四项镜像、文件清单及 SHA-256 生成成功。最终归档 SHA-256：`ad428a1503a586025faade9f9150de61baddd77999d50bd3766b80a9edd49bb3`。最终 import 脚本的包内哈希与四项 image ID 校验通过；使用临时本机 `.env` 从包目录启动、从目录外运行 `status.ps1` 均通过，临时 `.env` 随即移除，包不含 `.env`。

## P7：最终回归与本机验收（完成）

- 最终后端：`mvnw.cmd -pl backend -am test` → 78 tests，0 failures，0 errors，1 intentional skip。前端：`npm run test:unit` → 3/3，`npm run build` 成功；mock Playwright → 3/3。
- 160 条 mock 评估：140 执行，108 pass、32 fail、20 not run（缺少待审批 action fixture）。分类：订单/物流 30/30、工单 20/20、库存 20/20、政策问答 16/30、对抗 22/40。失败集中于确定性 mock 的政策相似度与对抗工具期望；不将 mock 结果写成模型质量指标。逐项证据见 `docs/reports/2026-10-01-evaluation-mock/`。
- 真实模型验收硬上限 10 次：三份合成政策 Qwen embedding 入库 3 次；初次 POL-001 检索未命中仍按 2 次记数；local-demo 检索门槛从 0.65 校准为 0.35 后，POL-001 引用问答 2 次通过；TKT-001 工单草稿 1 次通过；退款审批浏览器用例 1 次通过；ORD-001 只读订单查询 1 次通过。合计 DeepSeek 5 次、Qwen 5 次；数据库记录 daily=10、旧 lifetime=0。固定 20 条真模型评测未运行。
- 本地政策数据在 0.35 阈值下命中已发布退款政策并返回引用。真实浏览器流程由客服提交 1 元申请、主管批准，`LOCAL_DEMO_LEDGER` 成功入账；订单累计退款 100 分、账本 1 行。没有调用支付渠道。
- 备份/恢复演练使用独立临时卷；恢复后数据库计数为 daily=10/lifetime=0、退款账本 1、订单退款 100 分、已发布版本 3、向量片段 3；四项服务 healthy，readiness UP，工作台 HTTP 200。仅删除本次创建的临时栈与三只临时卷。
- 正式栈 stop/start 与根目录外状态检查通过，数据计数仍为 `10|0|1|100|3|3`，工作台 HTTP 200。恢复演练曾发现 Compose 固定卷名会造成跨项目复用；临时容器立即停止，随后用显式唯一卷名完成验证，正式卷数据复核一致。
- 汇总报告：[2026-10-01 本机验收](../reports/2026-10-01-acceptance.md)。未做压力性能、完整真模型质量或公网生产验收，不将它们标为完成。

## 2026-10-03 Windows PowerShell 5.1 启动兼容修复

- 复现：UTF-8 无 BOM 的 start.ps1 在 Windows PowerShell 5.1 中出现 5 个解析错误，在 PowerShell 7 中无错误。
- 修复：10 个源码 PowerShell 脚本统一保存为 UTF-8 BOM，同步镜像包脚本及 manifest 文件校验值。
- 补全按用户安装的 Docker Desktop CLI 路径；修正 Windows PowerShell 5.1 将 Docker stderr 进度信息视作终止异常的问题，保留退出码检查。
- 验证：20 份源码及镜像包脚本在 Windows PowerShell 5.1、PowerShell 7 中均可解析；包文件哈希匹配；Windows PowerShell 5.1 实际执行 start.ps1 成功退出，后端 readiness UP、网页 HTTP 200。
- Docker Desktop 启动后完成验证；未调用模型 API，未修改 .env、镜像归档或业务数据卷。
## 2026-10-03 演示账号密码同步与登录提示修复

- 用户更新四个 DEMO_*_PASSWORD 后，登录仍返回 401；确认 DemoSeedService 原先只创建账号，不更新已有密码。
- 演示初始化开启时，后端启动同步已有演示账号的 BCrypt 密码哈希，保留角色、状态和业务数据。
- 登录请求不携带旧 token，登录失败不触发会话过期跳转；前端支持读取 data.message，删除固定默认密码提示。
- 后端 Maven 打包成功（跳过测试），前端 Docker 镜像构建成功；重新导出镜像包并启动更新后的本地栈。
- 四个 DEMO_MERCHANT 账号 csr、supervisor、warehouse、admin 使用 .env 配置登录均返回 HTTP 200；readiness UP；镜像包文件哈希匹配。
- 镜像归档 SHA-256：ab29286a36908b2c364eae5ddded197c9ee6a1cb91b525466853383d35271e6e。
- 全程未调用模型 API，未重置或删除业务数据卷；操作前业务计数为订单 101、工单 0、退款账本 1、知识版本 3。