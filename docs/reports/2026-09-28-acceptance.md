# 2026-09-28 本地验收报告

## 范围与结论

本报告汇总已审阅计划 T1–T10 的本地执行证据。T1–T8 的构建、配置、隔离全栈、模型连通、知识检索、浏览器业务流程、权限、恢复和性能证据已取得。T9 的评测器与缺陷修正已部分完成；最近的 mock planner 和语料变更尚未重新打包并运行全量回归，因此不能把本报告视为当前工作树的最终验收。T10 文档已按现有证据更新。

本次没有提交、推送或发布公网内容，没有操作其他 Docker 项目。业务数据均为隔离验收栈中的合成数据。真实模型累计请求计数为 **20/50**；本轮 mock 冒烟调用计数为 0。

## 已验证范围

| 项目 | 实际证据 |
|---|---|
| 构建 | 后端 clean package 生成 `backend/target/aftersales-agent.jar`；前端 `npm ci` 与 Vite 生产构建通过。具体原始命令和基线见计划账本。 |
| 配置 | DeepSeek Chat 与 SiliconFlow Qwen embedding 分别绑定独立 URL、Key 和模型；embedding 为 1024 维；stub 请求与解析断言通过。 |
| 隔离全栈 | Compose 项目 `aftersales-acceptance-20260928` 运行 web、backend、PostgreSQL、Redis；健康检查、Flyway v1–v4、登录、受保护身份接口、重复启动种子计数和 Nginx 路由通过。验收端口为 web 28080、backend 18081、PostgreSQL 15432、Redis 16379。 |
| SSE | Nginx 代理下收到持久化进度及最终事件；断线后从游标回放。DeepSeek 只读浏览器场景和 mock 首次重连场景通过。 |
| DeepSeek | 应用经 Nginx 完成结构化计划、订单/物流只读查询。一次端到端测量：请求受理 60.79 ms、首 SSE 事件 122.64 ms、首进度 1.516 s、`answer.final` 1.525 s。样本数为 1，不代表模型延迟分布。 |
| Qwen embedding 与政策 | 通过硅基流动实际生成 1024 维向量；三份合成政策上传、发布并向量化；退款、物流、换修金标准问题命中对应版本和片段；无关问题及次商家问题未命中。累计真实模型请求计数在后续政策及业务验收后达到 20/50。 |
| 业务与权限 | 真实后端 headed 浏览器覆盖订单物流、政策引用、工单草稿与确认、退款申请/非申请人审批、自批 403、库存建议、调拨申请/审批与本地预留。审批结果未修改退款金额；调拨没有更改实存数量。 |
| 故障恢复 | 合成 RUNNING 任务在 backend 重启后成为 `FAILED/SERVER_RESTARTED`，错误事件可经 Nginx SSE 重放。数据卷保留。stub 401、429、超时、无效结构、错误向量维度和缺引用断言通过。 |
| 性能 | 总容器限制为 4 CPU / 8 GiB。订单查询预热 100 次后，以 10 并发执行 200 次，全部 HTTP 200；P50 19.93 ms、P95 72.41 ms、最大值 82.08 ms。它只代表本机验收栈的普通订单 GET，不代表生产容量。 |

更多逐项命令、测试名和浏览器场景见 [已批准的完成与验收计划](../plans/2026-09-28-completion-and-acceptance-plan.md)、[性能数据](2026-09-28-performance-baseline.json) 与 [模型时延数据](2026-09-28-model-timing.json)。

## Mock 全量评测基线

2026-09-28 运行了当时的 160 条 JSONL 数据：120 条业务、40 条安全对抗。140 条实际执行，**58 条通过、82 条失败**；另有 20 条审批工作流因缺少可重建的待审批动作 fixture 未执行。该次为 mock-only，不消耗真实模型请求，也不测量大模型回答质量。

| 分类 | 通过 / 执行 | 说明 |
|---|---:|---|
| 订单物流 | 24 / 30 | mock 对部分签收措辞识别不完整 |
| 政策问答 | 0 / 30 | 部分查询未触发工具；当时 mock 向量检索没有稳定命中全部语义问题，未产生所需引用 |
| 工单草稿 | 8 / 20 | 部分意图及附加查询与用例预期不一致 |
| 库存 | 6 / 20 | 语料缺少工具所需的目标仓和内部 UUID，且 mock 规划器未总能识别缺参数的建议请求 |
| 审批流程 | 0 / 0 | 20 条均为 fixture missing，不能算作通过或失败 |
| 安全对抗 | 20 / 40 | 固定关键词规划器会对部分文本触发安全范围内的读取或草稿工具；这不替代真实模型红队评估 |

测试运行器要求每个 case 的期望工具集合精确匹配，因此合法的附加读取调用也可能导致失败。该基线用于定位问题，不是经过校准的产品成功率。`INV-006` 与 `INV-016` 输入相同但 SKU fixture 不同，重复输入数为 1。

## T9 最近改动与当前验证状态

基线运行后，当前工作树已更新 mock planner 和评测输入：签收/错投等物流措辞被识别；“售后工单”不再单独触发政策检索；补货措辞可解析数量；调拨规划自动先查库存并从库存结果取得可信 SKU UUID，缺少目标仓或数量时返回具体补参提示；语料断言目标仓及必要的数量提示。评测器增加答复短语断言、suite 参数校验和断言失败分类。

这些修改已完成 JSONL 重建、Node 语法检查和 evaluator API/SSE 冒烟（ORD-001，1/1 通过、0 次模型请求）。隔离 Java smoke harness 的 5 项 planner 行为断言也通过。

修改后尚未完成 Maven focused/full tests、后端重新打包、验收容器更新或 160 条 mock 重跑。沙箱 Maven 编译在读取本地缓存 `mcp-0.14.0.jar` 时收到 `AccessDeniedException`；通过工具申请执行同一 Maven 回归时，自动权限审批两次超时，未能启动提升权限构建。这是当前 T9 的验证阻塞，不代表测试通过。最新 planner 源码尚未部署到验收容器。

## 未完成项

- 重新完成后端全量测试、clean package、前端 unit/build 和受影响的 headed 浏览器回归，并部署此次 planner 修正后重跑全部 160 条 mock 评测。
- 为审批评测准备隔离、可重复的待审批动作 fixture，或继续保留 20 条 `NOT_RUN_FIXTURE_MISSING`。没有 fixture 时不得把审批用例计入成功分母。
- 执行固定的 20 条真实模型样本评测。现有真实调用预算还剩 30 次；样本运行前按 runner 的 chat/embedding 估算值预留调用，失败请求同样计数。
- 视频尚未录制；文档提供可复现演示流程。未发布在线 Demo。

## 恢复验证步骤

在本地 Maven 依赖访问权限恢复后，从项目根目录按顺序运行：

~~~powershell
mvn -s .m2/codex-settings.xml -pl backend '-Dtest=MockTransferDraftPlanTest,PromptInjectionTest' test
mvn -s .m2/codex-settings.xml -pl backend clean package -DskipTests
cd frontend
npm run test:unit
npm run build
~~~

通过后，用新 JAR 更新**仅限** `aftersales-acceptance-20260928` 的 backend 容器，保留卷，再运行真实后端浏览器回归和 160 条 mock evaluator。之后按已批准清单执行固定 20 条 live 样本，并将累计请求数控制在 50 以内。
