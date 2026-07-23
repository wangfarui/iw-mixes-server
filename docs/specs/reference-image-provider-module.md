# AI 参考图 Provider Module 重构研发规格

## Problem Statement

当前 `iw-external` 将文本对话、流式会话和参考图生成集中在 `AIServiceImpl`。OpenAI Images、旧 OpenAI Responses 与 Gemini 的选择、配置、HTTP 请求、响应解析、图片下载和错误处理都表现为 provider 字符串分支与大量私有方法，现有测试因此依赖反射私有 implementation，而不是通过真实 interface 验证行为。

第一阶段衣物图片优化已经建立 MySQL 持久业务任务，但远程图片调用仍泄漏 `AiImageReferenceGenerateDto/Vo`、任意字符串状态、Base64 和旧后台任务 ID。衣橱 worker 还需要理解 provider 是否可轮询，导致业务任务与外部供应商生命周期重叠。

旧 OpenAI Responses 后台 adapter 已决定移除。当前真实 provider 只有同步返回图片的 OpenAI Images 和 Gemini，因此继续保留 `start/status`、恢复令牌和轮询字段属于 hypothetical interface。

## Solution

在 `iw-external` 内建立包级的参考图生成 deep module。它通过一个同步 `generate` interface 隐藏 provider 选择、配置校验、可信源图校验、HTTP、图片下载、请求响应解析、错误归一化和结果载荷归一化。OpenAI Images 与 Gemini 是该 seam 上的两个真实 adapters；文本 `AIService` 不再拥有参考图方法。

衣橱任务仍是面向微信小程序的持久异步任务。worker 通过独立长超时 `ReferenceImageClient` 同步调用 `iw-external`，成功后负责 OSS 上传和衣物结果落位，失败后按公共错误码与安全消息完成当前尝试。provider module 不持久化业务任务、不上传 OSS、不自动重试，也不在 providers 之间自动降级。

## Impact Scope

- 修改后端仓库 `iw-mixes-server`。
- 主要模块：
  - `iw-packaging-parent/iw-external`
  - `iw-feign-client/iw-external-client`
  - `iw-packaging-parent/iw-wardrobe`
  - `iw-packaging-parent/iw-core` 配置
  - `deploy/env` 示例配置
- 微信小程序不修改业务代码，只用于 OpenAI、Gemini 两轮真实端到端验收。
- Web 管理端不修改。
- 不新增 Maven 子模块、服务进程、Redis 状态或通用图片任务模型。

## Module Interface

### In-process interface

参考图 module 对 `iw-external` Controller 暴露一个方法：

```text
generate(sourceImageUrl, prompt) -> GenerationOutcome
```

`GenerationOutcome` 是明确的成功/失败结果：

- `Success`
  - `GeneratedImage`
    - `byte[] content`
    - `String mimeType`
    - `String revisedPrompt`，可空
  - `provider`
  - `model`
- `Failure`
  - `errorCode`
  - `message`
  - `provider`
  - `model`

可预期 provider 失败以 `Failure` 返回，不依赖异常穿过 HTTP。只有调用方违反非空等方法前置条件时才允许抛出异常；未预期 adapter 异常在 module seam 记录后转换为 `INTEGRATION_ERROR`。

### Internal HTTP interface

原子替换为：

```text
POST /internal-api/ai/reference-image/generate
```

请求只包含：

- `sourceImageUrl`
- `prompt`

响应为可判别的成功/失败 wire model，图片字段使用 `byte[]`，由 JSON 传输层自动序列化为 Base64。删除：

- `/ai/image/referenceGenerate/start`
- `/ai/image/referenceGenerate/status`
- `businessType`
- `businessCustomCategory`
- `businessId`
- provider 原生任务 ID
- 任意字符串 provider 状态

## Provider Adapters

### OpenAI Images

- provider ID：`openai`
- 默认 API 基础地址：`https://api.openai.com/v1`
- 默认模型：`gpt-image-2`
- adapter 在 `api-base-url` 后追加 `/images/edits`。
- 使用 `Authorization` 认证。
- 同步返回生成结果。

### Gemini

- provider ID：`gemini`
- 默认 API 基础地址：`https://generativelanguage.googleapis.com/v1beta`
- 默认模型：`gemini-3.1-flash-image`
- adapter 在 `api-base-url` 后追加 `/models/{model}:generateContent`。
- 使用 `x-goog-api-key` 认证；配置值若误带 `Bearer `，可继续做兼容剥离。
- adapter 通过 module 安全下载器读取参考图并发送 inline image。
- 同步返回生成结果。

### Removed adapter

- 删除 `openai-responses` provider、后台启动、状态查询、Responses URL 推导及解析。
- 不预留 `resume`、恢复令牌或后台 provider interface。
- 将来出现真实后台 provider 时，先新增 ADR 再扩展 interface。

## Provider Selection and Configuration

一次运行只配置一个当前 provider，不保存两套并行凭证，也不自动降级：

```yaml
iw:
  ai:
    image:
      provider: ${IW_AI_IMAGE_PROVIDER:gemini}
      api-base-url: ${IW_AI_IMAGE_API_BASE_URL:}
      api-key: ${IW_AI_IMAGE_API_KEY:}
      model: ${IW_AI_IMAGE_MODEL:}
      source-base-url: ${IW_AI_IMAGE_SOURCE_BASE_URL:}
      request-timeout-ms: ${IW_AI_IMAGE_REQUEST_TIMEOUT_MS:720000}
      source-download-timeout-ms: ${IW_AI_IMAGE_SOURCE_DOWNLOAD_TIMEOUT_MS:30000}
      max-image-bytes: ${IW_AI_IMAGE_MAX_BYTES:10485760}
```

规则：

- provider 只允许 `openai`、`gemini`，不接受 `google` 等别名。
- 不再读取 `iw.ai.image.api-url` / `IW_AI_IMAGE_API_URL`。
- 不从聊天、Responses、Interactions endpoint 猜测图片地址。
- 基础地址和模型为空时使用所选 provider 官方默认值。
- API Key 或可信源图基础 URL 缺失时返回 `CONFIGURATION_ERROR`。
- 图片配置错误只记录脱敏启动警告，不阻止整个 `iw-external` 启动。
- 不回退到 `iw.ai.default.*` 文本 AI 配置。

## Trusted Source Image Policy

系统只面向本人和家人，因此使用轻量卡控：

- 配置单一可信基础 URL，例如 `https://itwray.oss-cn-heyuan.aliyuncs.com/iw-core/`。
- 使用标准 URI 解析；协议必须为 HTTPS、主机完全一致，规范化路径必须位于配置前缀下。
- 不支持通配域名，不跟随 HTTP 重定向。
- 不增加 DNS、CIDR、内网网段或 DNS rebinding 检测。
- 下载超时默认 30 秒。
- 输入和输出解码后都不得超过 10 MB。
- 只接受 JPEG、PNG、WebP。
- 不可信 URL、源图超限或格式不支持返回 `INVALID_INPUT`。
- provider 返回超限或无效图片返回 `INTEGRATION_ERROR`。

## Error Contract

公共错误码仅包含：

- `INVALID_INPUT`
- `RATE_LIMITED`
- `PROVIDER_REJECTED`
- `PROVIDER_UNAVAILABLE`
- `CONFIGURATION_ERROR`
- `INTEGRATION_ERROR`

规则：

- 不提供 `retryable` 标志；是否重试归衣橱任务所有。
- 429 映射为 `RATE_LIMITED`，不再误报“图片过大”。
- 网络、超时和 provider 5xx 映射为 `PROVIDER_UNAVAILABLE`。
- 内容安全或能力限制映射为 `PROVIDER_REJECTED`。
- 凭证、provider 或 endpoint 配置问题映射为 `CONFIGURATION_ERROR`。
- 响应结构、图片解码或未知 adapter 错误映射为 `INTEGRATION_ERROR`。
- adapter 日志可记录 provider、HTTP 状态、request ID 与截断脱敏诊断，不得向调用方返回原始响应或敏感配置。

## Prompt Ownership

- 衣橱 `WardrobeImageOptimizationPromptFactory` 继续拥有最终业务提示词与规则版本。
- provider module 原样转发提示词，不追加或改写衣橱业务语义。
- adapters 只转换请求字段、认证、图片格式和响应协议。
- `revisedPrompt` 只作为审计信息，不改变任务指纹。

## Feign and Timeout Contract

新增独立 `ReferenceImageClient`，不提高其他 `iw-external` 调用的 read timeout：

- connect timeout：5 秒。
- provider request timeout：默认 12 分钟。
- reference-image Feign read timeout：默认 13 分钟。
- 衣橱任务 deadline：15 分钟。

必须保持：

```text
provider timeout < Feign read timeout < wardrobe task deadline
```

建议环境变量：

- `IW_REFERENCE_IMAGE_FEIGN_CONNECT_TIMEOUT_MS=5000`
- `IW_REFERENCE_IMAGE_FEIGN_READ_TIMEOUT_MS=780000`

## Wardrobe Task Integration

- worker 删除外部任务 ID 查询和 `defer` 分支，每次尝试只调用一次同步 `generate`。
- 调用前将 claim 有效期延长到该尝试的 15 分钟 deadline，不增加心跳线程。
- 进程在调用中断时不自动重发；deadline 到达后失败，等待用户主动重试。
- provider 结果返回后再次通过 claim token fence，迟到 worker 不得上传或落位。
- `Success`：上传图片并在现有事务中完成衣物关联，持久化 provider、model、MIME、revised prompt。
- `Failure`：持久化 error code、安全消息、provider、model，并结束当前任务尝试。
- provider/model 只用于审计，不参与任务指纹、去重、重试或页面分支。

## Phase 8 Database Migration

新增 `iw-wardrobe-phase8-reference-image-provider.sql`：

- `wardrobe_image_optimization_task`
  - 新增 `error_code varchar(64)`，保存当前失败摘要码。
- `wardrobe_image_optimization_attempt`
  - 保留 `provider`。
  - 新增 `model varchar(128)`。
  - 新增 `error_code varchar(64)`。
  - 删除 `external_task_id`。
  - 删除 `next_poll_time`。
  - 重建不依赖轮询时间的 runnable 索引。
- 迁移时：
  - 保留 `queued` 任务。
  - 将旧 `running` 任务与尝试标记为 `failed`，清空 claim，写入升级后需主动重试的错误摘要。
  - 不修改已经发布的 Phase 7 脚本。

## Implementation Sequence

1. 在 `iw-external-client` 增加新请求、结果、错误码与 `ReferenceImageClient`，配置独立 Feign timeout。
2. 在 `iw-external` 建立 `referenceimage` 包级 module、provider interface、selector、配置、可信源图策略与安全下载器。
3. 实现 OpenAI Images adapter。
4. 实现 Gemini adapter。
5. Controller 切换到同步 `generate`，从 `AIService` 删除参考图方法和旧 Responses implementation。
6. 衣橱 remote seam 与 worker 切换到新 client/result；删除轮询、外部任务 ID 与任意状态字符串解释。
7. 调整 claim 为同步调用长租约，完成结果与失败结果持久化 provider/model/error code。
8. 新增 Phase 8 SQL，更新配置示例、`AGENTS.md` 与相关研发文档。
9. 删除 `AIServiceImplTest` 中 provider 私有方法反射测试。

## Automated Verification

不为 OpenAI/Gemini adapters 建立 stub HTTP 自动化测试。

保留并补充：

- provider module 使用 test-source fake 验证：
  - 选中 provider 被调用。
  - 成功/失败 outcome 映射。
  - 不支持 provider 和缺失配置返回 `CONFIGURATION_ERROR`。
  - 未预期 adapter 异常转换为 `INTEGRATION_ERROR`。
- 衣橱任务公开行为测试验证：
  - 同步成功上传并落位。
  - 六类失败结束尝试并保留错误码。
  - claim 延长至 deadline。
  - Feign/adapter 异常不会自动重发。
  - 迟到 claim token 无法落位。
- 删除原有 provider URL、parser 私有方法反射测试。
- 后端执行：
  - `iw-external`、`iw-core`、`iw-wardrobe` 相关 Maven 测试。
  - `iw-core + iw-external` 依赖链编译。
  - `git diff --check`。
- 微信小程序执行路由检查和目标脚本语法检查；本需求无小程序代码变更。

## Real End-to-End Acceptance

真实 adapter 不以自动化 stub 代替，按以下顺序验收：

1. 使用 `scripts/local-backend.sh` 启动或重启本地后端。
2. 当前 provider 配置为 OpenAI。
3. 在微信开发者工具中进入衣物编辑页，保存源图并点击“一键优化”。
4. 验证任务排队、运行、成功、生成图片上传及衣物优化图落位。
5. 删除或重置当前优化图，确保可以再次发起。
6. 人工把当前 provider 配置切换为 Gemini，并使用标准脚本重启后端。
7. 在微信小程序重复完整“一键优化”流程，确认 Gemini 结果落位。
8. Gemini 是人工切换的次选 provider，不验证或实现自动降级。

## Out of Scope

- OpenAI Responses 后台 provider。
- provider 恢复令牌、状态查询或外部任务持久化。
- provider 自动降级、自动重试或并行竞速。
- 生产可配置 mock provider。
- provider adapter stub HTTP 自动化测试。
- 新 Maven module、独立进程或通用图片工作流引擎。
- `iw-external` 上传 OSS、持久化业务文件或理解衣物任务。
- Web 管理端改动。
- 新增 DNS rebinding、CIDR 或复杂 SSRF 防护框架。

## Definition of Done

- `AIServiceImpl` 不再包含参考图 provider 分支与旧 Responses 代码。
- 参考图生成只有一个小 interface，OpenAI 与 Gemini 差异均位于各自 adapter。
- 衣橱和 Feign client 不再出现外部任务 ID、后台状态查询、业务类型字段或 Base64 业务解码。
- Phase 8 脚本与实体、Mapper、查询索引一致。
- AGENTS、CONTEXT、ADR、两份 Spec 对当前同步 provider 设计无矛盾。
- 自动化验证通过。
- OpenAI 与 Gemini 两次微信小程序真实端到端验收均通过。

## References

- [领域 glossary](../../CONTEXT.md)
- [ADR 0001：持久衣物图片优化任务](../adr/0001-durable-wardrobe-image-optimization-tasks.md)
- [ADR 0002：参考图 provider module](../adr/0002-reference-image-generation-provider-module.md)
- [衣物图片优化任务规格](./wardrobe-image-optimization-task.md)
