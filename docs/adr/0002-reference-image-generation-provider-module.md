---
status: accepted
---

# Isolate reference image generation behind provider adapters

参考图生成为独立于文本对话和调用方业务任务的能力 module。它提供 provider-neutral 的同步生成契约，并由 OpenAI Images 和 Gemini adapters 分别封装配置、HTTP、请求响应解析及各供应商差异；调用方不接触 provider 原生 DTO、VO 或状态。

该能力作为 `iw-external` 内的包级 deep module 实现，不新增 Maven 子模块或部署进程。文本 `AIService` 不再包含参考图方法；跨进程 DTO/VO 留在 `iw-external-client`，provider 原生模型、配置和 adapters 只存在于 `iw-external` 的参考图包内。

内部 HTTP 契约原子替换为单一的 `POST /internal-api/ai/reference-image/generate`；请求只包含源图 URL 和提示词，响应使用 module 成功/失败语义。旧 `/ai/image/referenceGenerate/start`、`/status` 及 business type/category/id 字段直接删除，不保留兼容转发；微信小程序仍只调用衣橱业务任务接口。

`iw-core` 通过独立的 `ReferenceImageClient` 调用该长耗时接口，不提高其他 `iw-external` 调用的超时。默认时长满足 provider 请求超时 12 分钟、专用 Feign 读取超时 13 分钟、衣橱任务截止时间 15 分钟；连接超时保持 5 秒，三层时长均可配置但必须保持严格递增。

module 只提供一次调用、一次返回的 `generate` 能力，不提供 provider 后台任务、恢复令牌或状态查询。持久异步生命周期属于调用方业务任务；进程在同步供应商调用期间终止时，由业务任务把本次尝试置为失败并等待用户主动重试。

调用方在同步 `generate` 前将任务 claim 延长至本次业务截止时间，provider 请求超时必须早于该截止时间并为结果上传和提交预留余量。同步调用期间不使用短租约重新认领，也不增加心跳或自动恢复；进程中途终止后，任务到截止时间失败并等待用户主动重试。

每次生成只使用服务端配置选中的 provider，调用方不能指定 provider 或模型。module 不在超时、限流或供应商失败后自动切换 provider；失败后的显式业务重试才使用重试发生时的配置。

由于运行时只启用一个 provider 且不存在自动降级，OpenAI Images 与 Gemini 共用一组“当前 provider”配置，包括 provider、API 地址、API Key、模型和可信图片基础 URL；不保存两套并行凭证。各 adapter 只读取选中配置，并在内部处理各自的认证和协议细节。

图片 API 地址使用语义明确的 `api-base-url`，不再从聊天、Responses 或 Interactions endpoint 猜测图片接口。OpenAI adapter 在基础地址后追加 `/images/edits`，Gemini adapter 追加 `/models/{model}:generateContent`；未配置时可使用各 provider 的官方默认基础地址，自定义代理必须提供兼容的基础路径。

图片配置在启动时校验并输出脱敏警告，但配置错误不阻止承载其他外部能力的 `iw-external` 启动。provider 仅接受 OpenAI Images 或 Gemini；基础地址和模型可使用官方默认值，API Key 或可信源图基础 URL 缺失时，`generate` 返回 `CONFIGURATION_ERROR`，且不回退到默认文本 AI 配置。

provider 失败通过稳定的 module 错误码和可安全展示的消息返回。adapter 在内部日志中保留 provider、HTTP 状态、供应商 request ID 及经过截断和脱敏的诊断信息，不向调用方泄漏原始响应、异常类型或敏感配置；是否重试仍由调用方业务任务决定。

同步 `generate` 返回明确的成功或失败结果：成功结果的业务主体是生成图片，失败结果的业务主体是公共错误码和安全消息，两者均可附带下述非敏感执行元数据。限流、拒绝、超时、配置和响应异常等可预期失败不依赖抛出异常穿过内部 HTTP；module 边界将未预期的 adapter 异常记录后转换为 `INTEGRATION_ERROR`。只有违反方法前置条件的调用方编程错误才抛出异常。

成功和失败结果都可携带实际 provider 与模型作为非敏感执行元数据，调用方将其保存在任务尝试中用于审计和排障。该元数据不参与任务身份、去重、重试或业务分支，也不向微信小程序展示。

衣橱任务表通过新的 Phase 8 迁移保留 `provider` 并新增 `model`，删除只服务于后台 provider 的 `external_task_id`、`next_poll_time` 及相关轮询索引。迁移保留 `queued` 任务供新 worker 执行，将无法安全恢复的旧 `running` 任务和尝试标记为失败并允许用户主动重试；已经发布的 Phase 7 脚本不回写修改。

公共失败码仅包含 `INVALID_INPUT`、`RATE_LIMITED`、`PROVIDER_REJECTED`、`PROVIDER_UNAVAILABLE`、`CONFIGURATION_ERROR` 和 `INTEGRATION_ERROR`。错误码不携带 `retryable` 标志；源图无效属于输入错误，网络、超时和供应商 5xx 属于不可用，供应商响应结构异常属于集成错误。

完成结果以包含图片字节、MIME 类型和可选修订提示词的 module 值对象返回。adapter 负责把 provider 的 Base64、Data URL 或临时 URL 归一化为受大小上限约束的图片字节；内部 HTTP 使用 Base64 序列化只是传输细节。`iw-external` 不上传 OSS 或创建临时业务文件，图片上传和衣物关联仍属于衣物图片优化任务的完成事务。

调用方提供的最终提示词由衣橱任务 module 构造并参与任务指纹，参考图 module 和 adapters 不追加或改写业务语义，只转换供应商协议。provider 返回的修订提示词可作为审计信息保存，但不反向改变任务身份；业务提示词变化必须通过衣橱 Prompt Factory 和优化规则版本表达。

参考图输入仍使用源图 URL，并通过一个服务端配置的可信图片基础 URL 做轻量校验：协议必须为 HTTPS，主机完全一致，规范化后的路径必须位于配置前缀下，并且下载不跟随重定向。需要下载图片的 adapter 共用受超时、响应大小和图片 MIME 类型约束的安全下载器；不引入 DNS、网段或 DNS rebinding 检测。不可信来源返回 `INVALID_INPUT`。

参考图和生成结果共用默认 10 MB 的可配置大小上限，只接受 JPEG、PNG 和 WebP；源图下载超时默认 30 秒。源图超限或格式不支持属于 `INVALID_INPUT`，provider 返回超限或无效图片属于 `INTEGRATION_ERROR`。微信小程序继续依赖现有后端 10 MB 上传限制，不新增客户端配额逻辑。

自动化测试可通过仅存在于测试源码的 fake provider 验证 module 编排契约，生产和开发运行时均不注册可配置的 mock provider；不为 OpenAI 和 Gemini adapters 建立 stub HTTP 自动化测试。真实 adapter 验收统一走微信小程序“一键优化”端到端流程：先配置 OpenAI 完成一次生成、上传和衣物落位，再人工切换当前 provider 为 Gemini、通过标准脚本重启后端并重复完整流程。Gemini 是可人工切换的次选 provider，不承担自动降级。

拒绝让参考图生成继续作为 `AIService` 的 provider 字符串分支，也拒绝把同步调用包装成需要额外存储的伪后台任务或在 adapters 之间隐式降级。旧 `openai-responses` 后台 adapter 不再保留；在没有真实后台 provider 的情况下预留恢复契约只会形成 hypothetical interface。其余方案则会引入没有业务价值的任务状态，并可能造成重复生成、双重费用和不可预测的输出差异。
