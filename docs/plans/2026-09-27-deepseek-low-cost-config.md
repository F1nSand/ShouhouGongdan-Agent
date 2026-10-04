# DeepSeek 低成本模型配置计划

## 目标与约束

- 将用户提供的 DeepSeek Key 保存在根目录被 Git 忽略的 `.env`，不得写入源码、示例文件或日志。
- 选择 DeepSeek 官方当前可用且价格最低的 `deepseek-flash`，通过 OpenAI 兼容接口接入 Spring AI。
- 聊天与 embedding 独立配置服务商、API Key、URL 和模型；embedding 使用硅基流动 Qwen3-Embedding-0.6B，1024 维以匹配数据库向量列。
- 保留现有 DashScope `live` profile 的可选能力；通过额外的 `deepseek` profile 覆盖聊天模型配置。

## 文件与执行顺序

1. 在 backend 加入 Spring AI OpenAI starter，新增 `application-deepseek.yml`，启用 OpenAI ChatModel、DeepSeek endpoint、`deepseek-flash`，关闭思考模式以控制输出 token 成本。
2. 将通用 live ChatProvider 绑定到 Spring AI `ChatClient.Builder`；启用 OpenAI-compatible embedding provider，并为 DeepSeek 聊天和硅基流动 embedding 分别配置 endpoint、Key 与模型。
3. 在 `.env.example`、Compose 和部署文档中加入两组服务配置和 `live,deepseek` profile 说明。
4. 更新本机 `.env` 的 profile、DeepSeek Key 与 SiliconFlow embedding Key，保留默认 endpoint/model。

## 验收标准

- `.env` 包含 DeepSeek 与 SiliconFlow 配置，Key 不出现在 Git 跟踪文件中；聊天模型为 `deepseek-flash`，embedding 模型为 `Qwen/Qwen3-Embedding-0.6B`，向量维度为 1024。
- `live,deepseek` 下仅有一个 ChatProvider 与一个 EmbeddingProvider；两者使用分别配置的 API Key 和 endpoint。
- Maven backend 编译成功；不发送真实模型请求，不执行测试。

## 风险/说明

- SiliconFlow 的示例请求中出现 `Qwen/Qwen3-VL-Embedding-8B`；本配置按 0.6B 文本 embedding 的明确需求使用 `Qwen/Qwen3-Embedding-0.6B`。
- API Key 已在对话中发送；配置后建议在对应控制台轮换 DeepSeek 和 SiliconFlow Key。
