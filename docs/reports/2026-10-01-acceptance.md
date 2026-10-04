# 本地 Docker Desktop 演示版验收报告

日期：2026-10-01（Asia/Shanghai）  
工作目录：`智能售后工单Agent`  
计划：[最终执行计划](../plans/2026-09-30-local-docker-demo-plan.md)；逐项账本：[执行日志](../plans/2026-09-30-local-docker-demo-execution-log.md)

## 验收结论

本地 Docker Desktop 演示栈已构建、启动并通过所列功能验收。工作台当前地址：<http://127.0.0.1:38080>。日常运行方式：

```powershell
.\scripts\start.ps1
```

查看状态：`.\scripts\status.ps1`；停止并保留数据：`.\scripts\stop.ps1`。系统面向本地演示，不配置公网入口。

业务订单、物流、商品、退款、用户和政策均为合成数据。退款流程真实写入本地数据库退款账本并更新虚构订单退款累计额，但不调用支付网关。库存调拨只预留虚构源仓数量，不代表实物发货、运输或目标仓入库。

## 环境与部署

- Docker Desktop Linux Engine 29.7.2；Docker Compose 插件用于独立项目 `aftersales-local-demo`。
- 容器：Vue/Nginx `web`、Spring Boot `backend`、PostgreSQL 16 + pgvector、Redis。
- 唯一宿主映射：`127.0.0.1:38080 -> 80/tcp`。数据库、缓存和后端没有宿主机端口映射。
- 正式栈 stop/start 后仍可访问；从项目目录外运行 `status.ps1` 也成功。Web 返回 HTTP 200，后端 readiness 为 UP。
- Docker BuildKit 无法处理当前中文路径时，构建脚本通过临时 ASCII `subst` 盘符构建。Maven Central 曾发生 TLS 对端关闭；最终运行时镜像使用本机 Maven Wrapper 打出的当前源码 JAR，未复用旧业务镜像。
- 本机 `.env` 已保留并复用。报告、镜像包和 Git 跟踪文件均不包含 API Key 或密码值。

## 模型和真实请求

聊天使用 DeepSeek `deepseek-flash`；政策向量使用 SiliconFlow `Qwen/Qwen3-Embedding-0.6B`（1024 维）。聊天与 embedding 使用分别配置的 API 地址和 Key。local-demo 的最低检索相似度设置为 0.35；第一次真实政策查询在 0.65 门槛下没有命中引用，校准后同一案例返回已发布政策引用。

本轮真实模型 HTTP 请求总计 **10 次**，失败也计数。数据库每日桶最终记录 10/50，旧 lifetime 桶为 0；本轮外部执行硬上限 10/10，之后未再发送模型请求。

| 验收步骤 | 请求数 | 结果 |
|---|---:|---|
| 上传/发布三份合成政策 | Qwen 3 | 3 个已发布版本及 3 个向量片段 |
| 首次 `POL-001` 查询 | DeepSeek 1 + Qwen 1 | API 请求计入；相似度门槛过高，未命中引用 |
| 校准门槛后的 `POL-001` | DeepSeek 1 + Qwen 1 | PASS；返回退款政策引用 |
| `TKT-001` 工单草稿 | DeepSeek 1 | PASS；命中预期工具并生成草稿 |
| 退款审批浏览器流程 | DeepSeek 1 | PASS；客服确认、主管审批、本地账本入账 |
| `ORD-001` 订单只读查询 | DeepSeek 1 | PASS |
| **合计** | **DeepSeek 5 + Qwen 5 = 10** | 完成硬上限内验收 |

结果文件：

- [真实政策问答结果](evaluation-2026-10-01-live/results.jsonl)
- [真实工单草稿结果](2026-10-01-live-ticket/results.jsonl)
- [真实订单查询结果](2026-10-01-live-order/results.jsonl)
- [完整 mock 评估报告](2026-10-01-evaluation-mock/summary.json)

三个真实评测各只有一个案例，退款流程也只运行一条浏览器用例；它们只证明所测路径可工作，不构成模型准确率或整体成功率评估。

## 自动化回归

- 后端：`mvnw.cmd -pl backend -am test` → **78 项，0 failures，0 errors，1 skipped**。唯一跳过项为需显式启用真实模型的 `LiveEmbeddingAcceptanceTest`。Testcontainers PostgreSQL/pgvector 从空库运行 V1–V7；另有升级测试检查旧累计计数在 V6/V7 后保留。
- 前端单测：`npm run test:unit` → **3/3 通过**。
- 前端生产构建：`npm run build` → 类型检查与 Vite 构建成功。
- mock Playwright 浏览器流程：**3/3 通过**。
- 真实后端 Playwright 退款审批流程：**1/1 通过**。申请人为客服，审批人为主管；订单累计退款额增加 100 分，账本写入 1 条，执行模式 `LOCAL_DEMO_LEDGER`。
- Compose 配置以 `docker compose config --quiet` 验证；脚本均不打印完整 Compose 环境或 `.env`。

## Mock 全量评估

评估集共 160 条，140 条具有可运行前置条件并实际执行：**108 通过、32 失败**；另外 20 条审批工作流因没有对应待审批 action fixture 而未执行。

| 分类 | 通过/执行 | 失败/执行 |
|---|---:|---:|
| 订单与物流 | 30/30 | 0 |
| 工单 | 20/20 | 0 |
| 库存 | 20/20 | 0 |
| 政策问答 | 16/30 | 14 |
| 对抗用例 | 22/40 | 18 |

政策失败主要与确定性 mock 的相似度/字符 n-gram 行为有关；对抗失败与 mock planner 的工具选择期望有关。结果不能解释为 DeepSeek 或 Qwen 的质量分数；被跳过的审批样例也不能计作通过。下一阶段仍需补审批 fixtures、改进 mock 覆盖，并执行更大规模的真模型质量评估。

## 持久化与恢复

- 对正式栈停止并重新启动后，工作台 HTTP 200，政策和向量仍为 3 个已发布版本/3 个片段，退款账本仍 1 条、订单累计退款仍 100 分，模型额度仍为 daily 10/lifetime 0。
- 将数据库 dump 与政策文件 tar 作为配对备份，在三只显式唯一命名的临时卷中恢复。恢复后数据库计数与上述值相同，四项服务 healthy、readiness UP、工作台 HTTP 200。
- 恢复演练期间发现正式 Compose 使用固定卷名，单纯更换 `-p` 项目名不足以隔离数据。临时容器在写入前停止；后续为恢复演练覆盖三只卷为唯一名称，并复核正式数据一致。演练后只清理了本次创建的临时项目和临时卷。

## 可迁移镜像包

位置：`dist/local-demo/`。包含四项服务镜像、Compose 文件、启动/导入/状态/停止脚本、无密钥 `.env.example`、三份合成政策、镜像 ID 清单和 SHA-256。导出前的包内路径 allowlist 检查通过；用包内 `import-local-demo.ps1` 校验所有文件哈希并导入后，四项 image ID 均匹配。

- 镜像归档：`aftersales-local-demo-images.tar`
- SHA-256：`ad428a1503a586025faade9f9150de61baddd77999d50bd3766b80a9edd49bb3`
- 不含 `.env`、数据库 dump、运行期用户上传内容或模型响应日志。
- 导入后仍需在目标机器配置本机 `.env` 和真实模型 Key；运行模型功能需联网。
- 另从镜像包目录实际运行 `scripts/start.ps1`，并在项目目录外调用包内 `status.ps1`，工作台 HTTP 200。该验证临时使用本机 `.env`，验证后删除；归档本身仍不含 `.env`。源码 Java/Node 工具未用于启动已导入镜像。

## 尚未测量

- 未执行固定 20 条或完整 160 条真模型质量评估。
- 未执行并发压测、吞吐量或 P95 性能基准。
- 未连接真实支付、物流承运商、WMS，也未上线公网或经过生产安全验收。
- 32 条 mock 案例失败，20 条审批评估未运行；不得将本项目描述为全量评测通过。
