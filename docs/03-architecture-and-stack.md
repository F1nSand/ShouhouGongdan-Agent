# 系统架构与技术选型

## 架构决策

采用模块化单体：一个 Spring Boot 业务服务，一个 Vue 工作台，PostgreSQL 与 Redis 两个基础服务。模块按领域隔离，内部通过服务接口调用。这样可以在一次数据库事务中完成工单创建、审批状态、库存预留和审计记录，并减少首期部署依赖。

Agent 负责组织信息和提出操作草稿。订单状态、退款金额、库存约束、权限和审批都由后端确定性逻辑校验。

~~~mermaid
flowchart TB
    UI[Vue 客服与仓库工作台] --> API[REST / SSE / Spring Security]
    API --> RUN[Agent 运行服务]
    RUN --> AG[Spring AI Alibaba Agent]
    AG --> GW[受控工具网关]
    GW --> ORDER[订单与物流服务]
    GW --> RAG[政策检索服务]
    GW --> STOCK[库存建议服务]
    GW --> DRAFT[操作草稿服务]
    API --> CONFIRM[草稿确认服务]
    CONFIRM --> TICKET[工单服务]
    CONFIRM --> APPROVAL[敏感操作与审批]
    APPROVAL --> OUTBOX[持久化任务与执行器]
    OUTBOX --> EXEC[模拟退款 / 库存预留]
    RAG --> PG[(PostgreSQL + pgvector)]
    TICKET --> PG
    APPROVAL --> PG
    ORDER --> PG
    STOCK --> PG
    RUN --> PG
    EXEC --> PG
    API --> REDIS[(Redis 缓存与限流)]
    AG --> MODEL[DashScope ChatModel]
    RAG --> EMB[EmbeddingModel]
~~~

## 技术与版本基线

| 技术 | 基线 | 用途 |
|---|---|---|
| Java | 21 LTS | 后端语言与运行环境 |
| Maven | 3.9.x | 构建与依赖管理 |
| Spring Boot | 3.5.8 | 兼容基线，不使用 SNAPSHOT |
| Spring AI | 1.1.2 | ChatModel、EmbeddingModel 与工具抽象 |
| Spring AI Alibaba Agent Framework | 1.1.2.0 | Agent 编排与模型工具调用 |
| Spring AI Alibaba Extensions | 1.1.2.1 | DashScope starter 的版本管理 |
| PostgreSQL | 16 | 业务、审批、会话、审计和 outbox |
| pgvector | 0.8 系列 | 政策向量，余弦距离检索 |
| Redis | 7 系列 | 可撤销查询缓存与请求限流 |
| Spring JDBC、Security、Validation | 由 Boot 管理 | SQL、事务、权限与输入约束 |
| Flyway | 由 Boot 管理 | 数据库结构版本 |
| Apache PDFBox | 3.0 系列 | 文本 PDF 提取 |
| Vue / TypeScript / Vite | Vue 3.5，TypeScript 5，Vite 6 | 工作台 |
| Element Plus | 2 系列 | 表单、表格与审批页面 |
| Node.js | 22 LTS | 前端构建 |
| Docker Compose | v2 | 本地部署 |
| JUnit 5、Mockito、Testcontainers | 由构建基线锁定 | 后端和真实数据库测试 |
| Vitest、Playwright | 与前端构建锁定 | 前端与业务流程检查 |

框架版本基线依据官方发布标签的依赖定义：Spring AI Alibaba v1.1.2.0 的 POM 定义 Spring Boot 3.5.8、Spring AI 1.1.2、Extensions 1.1.2.1。[官方依赖定义](https://github.com/alibaba/spring-ai-alibaba/blob/v1.1.2.0/pom.xml)

这些版本用于兼容起点，不声明是当前最新版本。初始化构建时锁定全部实际补丁版本，提交 package-lock.json 和 Maven 依赖清单；发布前完成依赖漏洞与兼容检查。升级任何框架都重新执行模型工具调用、数据库、流式接口和审批库存测试。

## 模型选择与适配

- 聊天模型：DashScope qwen-plus，可通过配置替换；评测时记录实际服务返回的模型标识和时间。
- 默认向量模型：DashScope text-embedding-v3，1024 维；`live,deepseek` profile 独立使用 SiliconFlow `Qwen/Qwen3-Embedding-0.6B`，同为 1024 维；存储字段固定为 vector(1024)。
- 模拟模型：mock profile 提供固定响应，测试和本地展示可离线运行。
- 模型配置只从服务器环境读取，前端不接触模型 Key。

1024 维选择可依据 [DashScope Embedding 接口](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/) 和 [SiliconFlow Embedding 接口](https://api-docs.siliconflow.cn/docs/api/embeddings-post) 核对。更换模型后重新处理全部知识文档，检索按模型标识过滤，不能混用不同模型的向量。

## 模块边界

| 模块 | 责任 | 禁止事项 |
|---|---|---|
| identity | 身份、角色、商家范围 | 接受工具自行指定身份 |
| order / logistics | 订单事实与异常规则 | 让模型生成金额或物流记录 |
| knowledge | 文件解析、发布、检索和引用 | 检索未发布或其他商家内容 |
| inventory | 库存事实、建议、预留与释放 | 依靠 Redis 缓存判断可预留数量 |
| ticket | 工单与处理记录 | 重复草稿产生多个工单 |
| approval | 申请、决定、执行资格 | 审批人员修改已提交操作参数 |
| agent | 运行上下文、工具调用、结构化回答 | 直接访问数据库或执行任意 SQL |
| audit / jobs | 审计、事件回放、outbox 恢复 | 在业务事务内调用远程模型 |

## 目标目录

以下是待开发应用的目标结构，不表示目录或功能已经存在。

~~~text
aftersales-agent/
├── pom.xml
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/aftercare/
│       │   ├── AftersalesApplication.java
│       │   ├── identity/
│       │   ├── order/
│       │   ├── logistics/
│       │   ├── knowledge/
│       │   ├── inventory/
│       │   ├── ticket/
│       │   ├── approval/
│       │   ├── agent/
│       │   ├── audit/
│       │   └── jobs/
│       ├── main/resources/db/migration/
│       └── test/java/com/aftercare/
├── frontend/
│   └── src/{views,components,api,stores,router}/
├── infra/
│   ├── compose.yaml
│   └── nginx/default.conf
└── docs/
~~~

根 Maven 聚合只包含 backend。前端单独构建并由 Nginx 提供，Nginx 代理 /api 到后端。

## 关键决策

1. 使用 PostgreSQL + pgvector，业务数据和知识索引统一部署；首期不引入第二种关系数据库。
2. Spring JDBC 编写显式库存事务和对象权限查询；避免隐藏关键锁和更新条件。
3. Redis 不保存唯一审批状态或库存真值，故障时查询回退数据库，限流在进程内保守降级。
4. Spring AI Alibaba 使用受限工具集合；首期不提供 Shell、Python 任意执行和任意 URL 抓取。
5. 首期不要求 Nacos、MCP、A2A；后续外部系统集成通过适配器或这些协议扩展。
6. 使用数据库 outbox，不引入消息队列。任务采用短事务领取、事务外执行和幂等完成。

## 性能与可靠性

Agent 与文件处理使用有界执行器，任务数超过容量时返回 429 或 503。模型请求、工具调用和整轮运行都有期限。调用远程模型时不持有数据库锁。

单实例首期支持 10 个并发 Agent 运行，默认线程池 8、队列 32；具体容量需测量后调整。失效的会话运行标记为 FAILED，审批任务通过 outbox 恢复。所有缓存键包含 tenantId 和资源标识。
