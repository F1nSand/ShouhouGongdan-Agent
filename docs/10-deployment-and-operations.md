# 部署、运行与运维规范

本文件说明 Docker Desktop 本地演示栈和源码开发栈。推荐从项目根目录使用 `scripts/start.ps1`、`scripts/status.ps1`、`scripts/stop.ps1`。演示栈只把工作台映射到 `127.0.0.1:38080`；数据库、Redis、后端只在 Docker 网络内通信。2026-10-01 的部署、真实模型和退款验收见[本机验收报告](reports/2026-10-01-acceptance.md)。

## Docker Desktop 本地演示栈

启动 Docker Desktop 并切换到 Linux 容器后，在项目根目录的 PowerShell 执行：

~~~powershell
.\scripts\start.ps1
~~~

访问 <http://127.0.0.1:38080>。日常管理命令：

~~~powershell
.\scripts\status.ps1
.\scripts\stop.ps1
~~~

首次从源码运行时，如果本地镜像缺失，`start.ps1` 会用当前源码构建；已有版本直接启动。源码变更后显式运行 `.\scripts\start.ps1 -Build`。可搬运镜像包使用 `.\scripts\import-local-demo.ps1` 导入后再运行 `start.ps1`。需要初始化三份样例政策时单独运行 `.\scripts\init-demo.ps1`；embedding 请求计入每日额度，日常启动不重复向量化。

健康检查、业务数据、政策文件、退款账本和每日模型请求额度均保存在专用容器/命名卷中。`stop.ps1` 只停止服务，不删除卷。不要把 `docker compose down -v` 当作停止命令。

## 部署组成

| 服务 | 容器内端口 | 本地映射 | 说明 |
|---|---|---|---|
| web | 80 | 8080 | Nginx 工作台，代理 /api |
| backend | 8081 | 127.0.0.1:8081 | Spring Boot |
| postgres | 5432 | 127.0.0.1:5432 | PostgreSQL 16 + pgvector |
| redis | 6379 | 127.0.0.1:6379 | 查询缓存和限流 |

演示栈端口绑定仅为 `127.0.0.1:38080:80`；其他服务不发布宿主机端口。源码开发栈 `infra/compose.yaml` 使用不同端口和用途，切勿将它与演示栈的数据卷混用。前端开发服务器为 5173，代理到开发后端。

数据库、Redis 和上传政策文件分别使用命名卷。镜像使用受版本标签约束的 pgvector/pgvector:pg16 与 redis:7.4-alpine；当前未锁定镜像 digest。Compose 端口仅绑定 127.0.0.1，适用于本机演示，不是生产暴露配置。

## 环境变量

| 变量 | 用途与规则 |
|---|---|
| SPRING_PROFILES_ACTIVE | Docker Desktop 演示栈显式使用 `live,deepseek`；开发/自动测试可使用 mock |
| DASHSCOPE_API_KEY | 真实模型模式必填，mock 不需要 |
| DASHSCOPE_BASE_URL | DashScope 服务基础地址，聊天与向量模型共用；默认 `https://dashscope.aliyuncs.com` |
| DEEPSEEK_API_KEY | DeepSeek 聊天模式所需 Key |
| DEEPSEEK_BASE_URL | DeepSeek OpenAI 兼容 API 地址；默认 `https://api.deepseek.com` |
| DEEPSEEK_CHAT_MODEL | DeepSeek 聊天模型；默认 `deepseek-flash` |
| SILICONFLOW_API_KEY | `live,deepseek` 模式所需的硅基流动 embedding Key |
| SILICONFLOW_BASE_URL | embedding API 主机；默认 `https://api.siliconflow.cn`，Spring AI 会请求 `/v1/embeddings` |
| SILICONFLOW_EMBEDDING_MODEL | embedding 模型；默认 `Qwen/Qwen3-Embedding-0.6B` |
| EMBEDDING_MODEL | live 默认 text-embedding-v3；聊天模型由 Spring AI Alibaba DashScope 自动配置决定 |
| EMBEDDING_DIMENSIONS | 向量维度固定 1024，启动时校验；Qwen3-Embedding-0.6B 支持此维度 |
| DB_URL | PostgreSQL JDBC URL |
| DB_APP_USERNAME / DB_APP_PASSWORD | 业务运行账号 |
| DB_MIGRATION_USERNAME / DB_MIGRATION_PASSWORD | Flyway 迁移账号；本地生成器使其与 PostgreSQL 初始化账号一致 |
| SPRING_DATA_REDIS_HOST | Compose 内部 Redis 主机名 redis |
| JWT_SECRET | 至少 256 位随机密钥，使用约定的 Base64 编码 |
| POLICY_STORAGE_DIR | 上传根目录，Compose 容器内固定为 /data/policies |
| DEMO_SEED_ENABLED | 是否幂等创建演示商家、账号、订单、SKU、仓库和库存 |
| DEMO_CSR_PASSWORD | 演示客服密码 |
| DEMO_SUPERVISOR_PASSWORD | 演示主管密码 |
| DEMO_WAREHOUSE_PASSWORD | 演示仓库人员密码 |
| DEMO_ADMIN_PASSWORD | 演示管理员密码 |
| LOCAL_DEMO_KNOWLEDGE_MIN_SIMILARITY | 仅 Docker Desktop 演示栈的向量检索门槛，默认 0.35；调整后需复验政策命中 |

.env.example 中密码和 JWT_SECRET 是生成占位符，不是可登录凭证。使用 scripts/New-LocalEnv.ps1 生成本机 .env；脚本拒绝覆盖现有文件，也不会在终端打印密码。DASHSCOPE_API_KEY 默认留空；DASHSCOPE_BASE_URL 默认指向 DashScope 中国站点，可按所用服务区域或兼容服务修改。不要提交 .env、密钥或文件原文。

本地演示账号的 `DEMO_*_PASSWORD` 在 `.env` 中填写明文，数据库保存 BCrypt 哈希。启用 `DEMO_SEED_ENABLED` 时，每次后端启动都会同步演示账号密码，保留账号角色、状态和已有业务数据。修改这些密码后，在项目根目录依次运行 `./scripts/stop.ps1` 和 `./scripts/start.ps1`，不会热重载。数据库连接密码有独立的更新流程，不适用此演示账号同步机制。

Flyway 账号拥有迁移权限，业务账号没有建表、删除审计或修改审计历史的权限。迁移结束后应用使用业务账号访问数据库。

## 源码开发栈（可选）

从项目根目录执行：

~~~powershell
.\scripts\New-LocalEnv.ps1
docker compose --env-file .env -f infra/compose.yaml up -d --build
docker compose --env-file .env -f infra/compose.yaml ps
~~~

此源码开发栈工作台地址为 http://127.0.0.1:8080。它与推荐的 Docker Desktop 演示栈是不同 Compose 项目；不要用此处 `down -v` 清理其他项目或用户数据。种子账号密码在本机 `.env`。

mock 模式不需要模型 Key，使用确定性 Chat 与 embedding provider。DashScope 模式使用 `SPRING_PROFILES_ACTIVE=live`、`DASHSCOPE_API_KEY` 和 `DASHSCOPE_BASE_URL`。

DeepSeek 低成本模式使用 `SPRING_PROFILES_ACTIVE=live,deepseek`。聊天通过 Spring AI OpenAI-compatible ChatModel 调用 DeepSeek，默认模型为 `deepseek-flash`；思考模式关闭，最大输出 512 tokens。Embedding 独立调用硅基流动 `https://api.siliconflow.cn/v1/embeddings`，默认模型为 `Qwen/Qwen3-Embedding-0.6B`、1024 维，使用独立的 `SILICONFLOW_API_KEY`。聊天和 embedding 的 URL 与 Key 分开配置。硅基流动的 VL 示例模型 `Qwen/Qwen3-VL-Embedding-8B` 与本项目配置的 0.6B 文本模型不同。切换 embedding 模型后应重新处理知识文件，不能混用不同模型生成的向量。已在真实服务完成聊天结构化计划、Qwen 1024 维向量、政策入库/检索和一次只读 SSE 流程验证；真模型质量抽样尚未完成。部署前应使用独立 Compose 项目名隔离数据库与知识文件卷。

## 分开开发

~~~powershell
./mvnw.cmd -pl backend -am test
./mvnw.cmd -pl backend spring-boot:run
~~~

前端目录执行：

~~~powershell
npm ci
npm run dev
~~~

根 Maven Wrapper 的脚本、properties 和校验配置须在基础工程阶段一起交付。前端 npm ci 使用提交的锁文件。依赖下载联网与 mock 模型离线运行是两个不同条件。

## 健康检查与观测

- http://127.0.0.1:8081/actuator/health/liveness：进程是否存活。
- http://127.0.0.1:8081/actuator/health/readiness：数据库与必要依赖是否可用。
- 对话依赖模型不可用时，普通业务页面保持可用；页面提示 AI 功能暂不可用。
- 指标：HTTP 时延、活跃 Agent 运行、队列长度、工具失败、Embedding 失败、outbox 待执行数与租约、库存冲突。
- 日志：结构化 JSON，含 traceId、tenantId、runId 或 actionId；不打印密码、API Key、完整地址或模型内部推理。

Compose 仅将 backend 端口绑定至本机回环接口；Nginx 对 /actuator/ 返回 404。部署到其他环境前还应通过网络策略保护 Actuator。审计页面读取业务审计表，不能直接公开容器日志。

## 数据库与任务恢复

Flyway 按版本执行迁移，生产环境关闭自动建表。每次结构变更先备份，在相同版本测试库验证升级；回滚业务代码前检查新字段与旧代码兼容。

outbox 任务保存租约、重试与错误；应用重启后回收已过期租约。库存执行通过 actionId 唯一约束和事务判断是否已完成，禁止通过手工修改 status 触发第二次预留。

服务启动时将遗留 QUEUED/RUNNING 会话转 FAILED/SERVER_RESTARTED，并通知界面重新发起；已确认的申请继续恢复执行。预留释放作业对 ACTIVE 状态做条件更新，重复运行不重复扣减 reserved。

## 备份与保留

正式备份必须把 PostgreSQL 与 `policies-data` 文件卷作为同一时间点的一组保存。下面示例在项目根目录的 PowerShell 运行；直接执行 Docker 命令时，若 Docker CLI 未加入 PATH，先将 Docker Desktop 的 `resources/bin` 加入当前 PowerShell 的 PATH。

~~~powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = "backups/local-demo/$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
$compose = @('--env-file', '.env', '-f', 'infra/compose.local-demo.yaml', '-p', 'aftersales-local-demo')
docker compose @compose exec -T postgres sh -lc 'pg_dump -Fc -U "$POSTGRES_USER" "$POSTGRES_DB" -f /tmp/local-demo.dump'
docker compose @compose exec -T backend tar -czf /tmp/local-demo-policies.tar.gz -C /data/policies .
docker compose @compose cp postgres:/tmp/local-demo.dump "$backupDir/database.dump"
docker compose @compose cp backend:/tmp/local-demo-policies.tar.gz "$backupDir/policies.tar.gz"
~~~

恢复时必须同时恢复数据库和政策文件，并使用不同的卷名。当前演示 Compose 给卷设置了固定名称，仅更换 `-p` 项目名无法隔离数据。示例在备份目录生成带时间戳的 override，确保三个数据卷与正式栈分开：

~~~powershell
$restoreId = Get-Date -Format 'yyyyMMddHHmmss'
$override = Join-Path $backupDir 'compose.restore.yaml'
@"
volumes:
  postgres-data:
    name: aftersales-restore-$restoreId-postgres
  redis-data:
    name: aftersales-restore-$restoreId-redis
  policy-data:
    name: aftersales-restore-$restoreId-policies
"@ | Set-Content -LiteralPath $override -Encoding utf8
$env:DEMO_WEB_PORT = '38082' # 先确认本机端口可用
$restoreCompose = @('--env-file', '.env', '-f', 'infra/compose.local-demo.yaml', '-f', $override, '-p', "aftersales-restore-$restoreId")
docker compose @restoreCompose config --quiet
docker compose @restoreCompose up -d --no-build
docker compose @restoreCompose stop backend
docker compose @restoreCompose cp "$backupDir/database.dump" postgres:/tmp/local-demo.dump
docker compose @restoreCompose cp "$backupDir/policies.tar.gz" backend:/tmp/local-demo-policies.tar.gz
docker compose @restoreCompose exec -T postgres sh -lc 'pg_restore --clean --if-exists --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB" /tmp/local-demo.dump'
docker compose @restoreCompose start backend
docker compose @restoreCompose exec -T backend tar -xzf /tmp/local-demo-policies.tar.gz -C /data/policies
~~~

确认 override 中三只卷名均带本次 `restoreId`，然后核对 readiness、政策版本与向量、引用快照、退款账本和订单金额、库存预留与调拨单及额度记录。验证完成后才对这个临时项目运行 `docker compose @restoreCompose down -v`。不要把备份恢复到现有数据卷，也不要只恢复数据库或只恢复政策文件。删除数据卷属于清空业务数据，不是日常重启步骤。

## 故障排查

| 现象 | 排查顺序 |
|---|---|
| AI 不可用 | 模式、Key 是否配置、模型服务响应、超时与预算 |
| 检索无结果 | 商家、发布状态、订单购买日期、维度、类别、阈值 |
| SSE 中断 | 反向代理缓冲/超时、令牌、游标、运行状态 |
| 审批不能执行 | 角色、自批、版本、期限、payloadHash |
| 库存预留失败 | 当前可调配数量、版本竞争、已有 actionId 效果 |
| 任务长期执行中 | 租约续约、进程状态、依赖失败与重试记录 |
| 草稿确认返回冲突 | 草稿状态、幂等 Key、输入摘要是否变化 |

Nginx 为 /api/v1/agent-runs/{runId}/events 单独关闭响应缓冲和缓存，设置 75 秒读超时并转发 X-Accel-Buffering: no。该路由已在本地验收栈通过 mock 和 DeepSeek 请求验证，并覆盖游标回放与客户端重连。排障优先根据 traceId 定位业务和工具记录。

## 发布检查

按 [测试与评估](09-testing-and-evaluation.md) 完成门槛，核对密钥未进入产物，准备独立客服/审批人员账号，确认界面所有模拟行为有明确标识，保存 commit 与实际环境版本。线上模型设置预算并监控调用量。
