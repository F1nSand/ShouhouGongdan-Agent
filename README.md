# 智能售后工单与库存调度 Agent

面向电商客服与仓库运营的 Java 智能助手。用户通过自然语言描述售后问题，系统关联订单和物流信息、检索售后政策、整理工单，并给出库存调度建议。退款或库存预留进入人工审批与可追踪的执行流程。

## 项目状态

本项目已打包为本地 Docker Desktop 演示版：Java 后端、PostgreSQL/pgvector、Redis 和 Vue 工作台共四项服务；业务数据为合成数据，DeepSeek 负责 Agent 规划，SiliconFlow 的 Qwen 向量模型负责政策检索。客服可查单、查政策和准备工单；退款经客服确认、主管审批后记入本地虚构退款账本。系统不调用支付网关，也不连接真实物流或 WMS。

2026-10-01 本机验收通过：真实模型订单查询、带政策引用的问答、工单草稿和退款审批闭环；完整 160 条 mock 评估执行 140 条，108 条通过、32 条失败，20 条审批流程缺少待审批 fixture 未执行。mock 结果不是模型质量指标。详见[本机验收报告](docs/reports/2026-10-01-acceptance.md)和[执行账本](docs/plans/2026-09-30-local-docker-demo-execution-log.md)。

演示订单、物流、商品、退款与政策均为合成数据。退款审批会真实更新本地订单退款累计额并写入幂等账本，但不会发生资金支付；库存审批只预留虚构源仓库存，不连接真实 WMS。样例政策不是商家的真实规则。

## 本机启动

需要已启动 Docker Desktop，并使用 Linux 容器。从项目根目录的 PowerShell 运行：

~~~powershell
.\scripts\start.ps1
~~~

打开 <http://127.0.0.1:38080>。查看状态和停止服务（保留数据）：

~~~powershell
.\scripts\status.ps1
.\scripts\stop.ps1
~~~

已有本机 `.env` 时启动脚本会复用它；不要分享或提交该文件。源码变化后运行 `.\scripts\start.ps1 -Build` 更新镜像。管理员首次初始化三份合成政策才运行 `.\scripts\init-demo.ps1`；该操作会调用 Qwen 并计入每日模型额度，已初始化的环境无需重复执行。旧的 `infra/compose.yaml` 仅保留给源码开发和独立测试。

## 验证

| 检查 | 命令 |
|---|---|
| 后端集成测试 | .\mvnw.cmd -Dmaven.repo.local=.m2/repository -pl backend -am test |
| 前端单元测试 | 在 frontend/ 目录运行 npm run test:unit |
| 前端生产构建 | 在 frontend/ 目录运行 npm run build |
| 浏览器流程 | 在 frontend/ 目录运行 npm run test:e2e |

浏览器端到端用例包括确定性 HTTP/SSE stub 和真实后端流程。2026-10-01 的本机真实模型与退款审批证据、mock 全量评估限制见[验收报告](docs/reports/2026-10-01-acceptance.md)。

## 首期功能

1. 关联订单，查询物流状态并识别异常。
2. 检索售后政策，展示版本、片段和引用。
3. Agent 整理工单草稿，客服确认后创建与追踪工单。
4. 查询多仓库存，按可调配量、运输时间和成本生成建议。
5. 提交退款或调拨申请，人工审批后更新本地退款账本或预留源仓库存。

所有查询、工具调用、确认、审批与执行均关联 traceId 和操作记录。

## 技术方案

Java 21、Spring Boot、Spring AI、Spring AI Alibaba Agent Framework、PostgreSQL + pgvector、Redis、Spring Security、Flyway、Vue 3 + TypeScript、Docker Compose。

技术版本与兼容依据见 [架构与技术选型](docs/03-architecture-and-stack.md)。

## 文档入口

- [完整项目简介](docs/01-project-overview.md)
- [产品需求与验收标准](docs/02-product-requirements.md)
- [文档目录与阅读顺序](docs/README.md)
- [开发实施计划](docs/plans/2026-09-26-aftersales-agent-implementation.md)
- [部署与运行规范](docs/10-deployment-and-operations.md)
- [演示与简历材料](docs/11-demo-and-resume.md)

## 核心流程

~~~mermaid
flowchart LR
    U[客服描述问题] --> A[Agent 识别需求]
    A --> Q[订单与物流查询]
    A --> K[政策检索]
    Q --> R[有依据的答复]
    K --> R
    R --> D[生成操作草稿]
    D --> C[用户确认]
    C --> T[创建工单]
    C --> P[提交敏感操作审批]
    P --> H[主管或仓库审批]
    H --> E[本地退款账本或库存预留]
    T --> L[业务与审计记录]
    E --> L
~~~

## 开发顺序

先建设业务数据、身份权限和普通业务接口，再接入政策检索与 Agent，随后完成确认、审批执行、工作台及评估。每阶段有独立交付门槛，具体任务见实施计划。
