# 2026-09-27 实施验证记录

## 范围与基线

- 工作区 HEAD：1dc160d90157b1f6b8f4dde083d682fc9a293d5b；验证时工作区有未提交改动。
- 环境：Windows；Java 21.0.11；Maven 3.9.16；Node.js 24.18.0；npm 11.16.0。
- Node 报告 18 个逻辑 CPU、33,786,552,320 字节内存；Testcontainers 连接 Docker Desktop Engine 29.7.2，Docker daemon 报告 15,721 MB 内存。
- 未配置 DASHSCOPE_API_KEY；Docker 和 Docker Compose CLI 均不可用。CI 文件尚未由远端 GitHub Actions 执行。

## 已执行并通过

| 检查 | 命令或方法 | 结果 |
|---|---|---|
| 后端完整测试 | mvn -Dmaven.repo.local=.m2/repository -pl backend -am test | 52 tests；0 failures、0 errors、0 skipped。Testcontainers 使用 PostgreSQL 16 + pgvector，并执行 Flyway v1–v4。 |
| 前端单元测试 | frontend 目录执行 npm run test:unit | 3 tests 通过。 |
| 前端生产构建 | frontend 目录执行 npm run build | vue-tsc 与 Vite 7.3.6 构建通过。 |
| 浏览器流程 | frontend 目录执行 npm run test:e2e | 3 tests 通过：工单/退款审批、库存建议/调拨审批、客服权限拒绝。HTTP/SSE 由确定性 stub 响应。 |
| 依赖审计 | frontend 目录执行 npm audit --audit-level=high | 0 vulnerabilities。Vite 已从 7.1.12 升级到 7.3.6。 |
| 评测语料 | node scripts/Generate-EvaluationCases.mjs 后逐行解析 JSONL | 业务 120 条：订单物流 30、政策 30、工单 20、库存 20、审批 20；对抗 40 条；160 个 caseId 唯一且字段完整。 |
| 本地凭据生成器 | 临时运行 scripts/New-LocalEnv.ps1 并检查格式 | 所需密码均生成；JWT_SECRET 解码为 32 字节标准 Base64；脚本不输出密码。临时 .env 已删除。 |
| 仓库密钥检查 | 对仓库文本和代码检索凭据特征 | 发现一处硬编码 Authorization Bearer 值，已改为可选读取 MCP_AUTH_TOKEN；凭据值未复制到报告或终端。复查未发现常见模型/API 密钥格式。 |
| 差异格式 | git diff --check | 无空白错误；Git 对既有 CRLF/LF 规范化给出提示。 |

## 尚未执行

- **Compose 首启、重启恢复、上传卷持久性及 Nginx SSE 反向代理**：当前没有 Docker/Compose CLI，无法运行完整容器栈。后端 Testcontainers 的迁移、幂等、租约恢复和 SSE 回放集成测试已通过，但不覆盖进程级 Compose 重启或 Nginx 代理行为。
- **4 核/8 GB、10 并发性能基线**：当前机器规格不匹配目标配置，未执行 100 次预热和正式压测；没有请求分母、P95 或吞吐实测值。
- **真实模型评测**：没有 DASHSCOPE_API_KEY，未发送 live 请求；120 条业务语料和 40 条对抗语料尚未通过模型评分器运行。不能据此报告检索 Recall、引用覆盖率、工具成功率或回答质量。
- **完整前端到真实后端的浏览器端到端**：Playwright 只使用 API stub；真实数据库/API 集成由后端测试独立验证。
- **在线 Demo 和演示视频**：未发布。

## 结论与复现限制

当前自动化验证证明了后端数据库/权限/事务集成测试、前端单元与构建、mock 浏览器流程和评测输入文件的基本完整性。它没有证明全栈 Compose 可以启动，也没有证明实时模型质量或目标机器上的性能。

硬编码 Bearer 值所在文件此前已在当前 Git 历史中跟踪。本次移除了工作树中的固定值，但没有改写提交历史；若该值曾对应有效凭据，应在相应服务端撤销并轮换。

本地 Compose 前需安装 Docker Compose，并先在项目根目录执行 scripts/New-LocalEnv.ps1。管理员还需上传并发布 docs/demo/policies/ 中的合成演示政策；这些文件不是生产政策。真实模型评测与性能测试应分别使用有 Key 的独立环境和符合 4 核/8 GB 限制的基准环境，保存实际请求数、分母、P95 和失败 caseId 后再更新简历指标。
