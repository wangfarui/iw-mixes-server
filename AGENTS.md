# AGENTS.md - iw-mixes-server 后端项目指南

## 项目定位

`iw-mixes-server` 是 IW 系统当前唯一默认后端迭代项目。用户说 `后端` 时，默认进入本项目。

当前目标是面向小规模生产环境的模块化单体：

- `iw-core` 聚合认证、基础资料、记账、餐食、积分等内部业务能力。
- `iw-external` 保留为公开外部能力边界。
- 生产环境不依赖 Nacos、RocketMQ Broker 和独立 Spring Cloud Gateway。
- 配置使用 Spring Boot 外部化配置，不提交真实密钥。
- 发布链路使用 GitHub Actions + SSH + systemd。

历史旧后端 `../iw-mixes` 是归档项目，只能作为迁移追溯或旧实现对照；新接口、新字段、新业务逻辑都应落在本项目。

发布文件见 `deploy/`，生产发布和运维说明优先读取 `README.md`、`deploy/README.md`、`deploy/OPERATIONS.md`。

## 技术栈

- Java 17。
- Maven 多模块。
- Spring Boot `3.2.12`。
- Spring Cloud OpenFeign `4.x`，仅用于显式 URL 的内部 HTTP 调用。
- MyBatis-Plus、Redis、Lombok、Hutool、springdoc-openapi、FastExcel。
- `iw-starter/iw-rocketmq-starter` 保留旧模块名，但实现已经改成本地 Spring 事件，不连接 RocketMQ Broker。

## 模块地图

- `iw-common`：通用响应、异常码、基础常量和工具。
- `iw-web`：Web 基础层，包含统一响应包装、异常处理、认证辅助、共享查询、通用 CRUD、MyBatis、Feign、Swagger 等。
- `iw-feign-client`：内部 Client，使用 `iw.remote.*.base-url` 显式地址，不再依赖服务发现。
- `iw-starter`：项目内 starter，包含 Redis starter 和本地事件 starter。
- `iw-packaging-parent/iw-core`：生产核心服务启动模块，端口默认 `18080`。
- `iw-packaging-parent/iw-auth`：认证、用户、家庭组、字典、文件、网站导航、应用账号、基础 AI 任务，作为 `iw-core` 库模块。
- `iw-packaging-parent/iw-bookkeeping`：记账记录、统计、预算、钱包、订阅、语音记账，作为 `iw-core` 库模块。
- `iw-packaging-parent/iw-eat`：餐食、菜品、冰箱、菜谱，作为 `iw-core` 库模块。
- `iw-packaging-parent/iw-points`：任务、积分记录、积分统计，作为 `iw-core` 库模块。
- `iw-packaging-parent/iw-external`：短信、邮件、AI、ASR、天气、热榜、钉钉、汇率、公开 API，端口默认 `18006`。
- `iw-code-generator`：代码生成辅助模块。
- `iw-oauth2-authorization-server`：OAuth2 授权服务，当前不属于生产发布链路。

## 接口路径

生产入口由 Nginx 兼容旧网关前缀：

- `/auth-service/**` -> `iw-core`，Nginx 去掉 `/auth-service` 前缀。
- `/bookkeeping-service/**` -> `iw-core`，Nginx 去掉 `/bookkeeping-service` 前缀。
- `/eat-service/**` -> `iw-core`，Nginx 去掉 `/eat-service` 前缀。
- `/points-service/**` -> `iw-core`，Nginx 去掉 `/points-service` 前缀。
- `/external-service/api/**` -> `iw-external`，保留完整路径。
- `/external-service/wb/**` -> `iw-external`，保留完整路径并启用 WebSocket upgrade。

Controller 上的路径仍不包含内部业务服务前缀。例如后端 `@RequestMapping("/bookkeeping/records")`，前端仍调用 `/bookkeeping-service/bookkeeping/records/...`。

例外：`iw-external` 的公开 API Controller 保留 `/external-service/api` 前缀。新增无需登录、供第三方系统或静态站点直接调用的公开接口时，默认使用 `/external-service/api/<domain>/...`。

## 配置与密钥

- 生产配置通过 `/etc/iw-mixes/iw-core.env` 和 `/etc/iw-mixes/iw-external.env` 注入。
- 示例文件在 `deploy/env/*.env.example`。
- 本地可使用环境变量或不提交的 `application-dev.yml`。
- 不要提交真实数据库密码、Redis 密码、JWT/内部调用密钥、AI Key、阿里云 Key、Nacos 密码等。

## AI 接口使用规范

- AI 供应商调用统一收敛在 `iw-packaging-parent/iw-external`，业务模块不要直接请求 OpenAI、DeepSeek 或其他第三方 AI 地址。
- 业务模块调用 AI 时通过 `iw-feign-client/iw-external-client` 的内部接口传递消息、温度、token 等业务参数；请求 DTO、Controller 入参、前端 payload 不要暴露或透传 `model` 字段。
- 默认 AI 统一由 `iw-external` 配置 `iw.ai.default.api-url`、`iw.ai.default.api-key`、`iw.ai.default.model` 及对应环境变量 `IW_AI_DEFAULT_*` 控制，默认模型 `gpt-5.5`。不要在业务模块新增 `iw.<domain>.ai.*model` 或写死模型名。
- 图片生成 AI 统一由 `iw.ai.image.provider`、`iw.ai.image.api-url`、`iw.ai.image.api-key`、`iw.ai.image.model` 及对应环境变量 `IW_AI_IMAGE_*` 控制。当前只有“衣物图片 AI 优化生成”使用图片生成 AI，其它 AI 调用方都使用默认 AI。
- 旧配置 `iw.ai.api-url`、`iw.ai.api-key`、`iw.ai.model` / `IW_AI_API_URL`、`IW_AI_API_KEY`、`IW_AI_MODEL` 不再支持；AI 配置必须使用 `iw.ai.default.*` 和 `iw.ai.image.*`。
- 如某个 AI 能力确实需要独立模型，先确认需求，再在 `iw-external` 内新增明确命名的服务端配置项；不要让 `iw-core`、微信小程序或 Web 管理端通过请求参数选择模型。
- 默认 AI 的 OpenAI-compatible HTTP 头使用 `Authorization`，需把配置值写成完整格式，例如 `Bearer sk-xxx`。Gemini 图片生成 AI 使用 `x-goog-api-key`，配置值可以是裸 Gemini API Key；如误写成 `Bearer xxx`，服务端会自动去掉前缀。
- 图片/多模态能力也通过 `iw-external` 的内部 AI 接口提供，业务模块只传提示词、业务上下文和图片 URL；模型兼容性由 `iw-external` 配置负责。图片生成能力使用 external 侧 AI 配置推导供应商接口地址，不在业务模块新增模型参数。
- 图片生成供应商由 `iw.ai.image.provider` / `IW_AI_IMAGE_PROVIDER` 控制，当前支持 `gemini` 和 `openai`。`gemini` 走 Gemini 原生 `/v1beta/models/{model}:generateContent` 文本+图片生成图片接口，默认图片模型为 `gemini-3.1-flash-image`；`openai` 走 OpenAI Images `/v1/images/edits` 参考图编辑接口，默认图片模型为 `gpt-image-2`；如需旧 OpenAI `/responses + image_generation` 后台任务实现，可显式配置供应商为 `openai-responses`，模型未配置时回退到默认 AI 模型。
- 图片生成、图片编辑等长耗时 AI 能力不要做前端同步长等待接口。业务端应返回任务 ID，用 Redis 保存短期任务状态并提供轮询接口；如供应商支持后台任务，`iw-external` 也应使用供应商后台/状态查询能力，避免 504 或 Feign/小程序请求超时。
- 凡是调用 `startReferenceGenerateImage` 的图片生成业务，都必须提供 `businessType`、`businessCustomCategory`、`businessId` 上下文，并在业务调用侧落通用图片生成记录。去重键统一为 `sha256(sourceImageUrl + "|" + (businessType + ":" + businessCustomCategory) + "|" + businessId)`；成功记录直接复用结果，处理中记录返回原任务，失败记录允许用户再次主动触发同 key 重试。供应商返回 429 时当前尝试立即失败，衣物图片优化对用户提示“图片过大，优化失败”，不做自动重试。

## 发布入口

- 生产发布规则见 [README.md](README.md) 的“发布”章节和 [.github/workflows/deploy-prod.yml](.github/workflows/deploy-prod.yml)。
- GitHub Actions 支持手动发布 `all`、`core`、`external`；手动选择 `core` 时只重启 `iw-core`，手动选择 `external` 时只重启 `iw-external`。
- 推送符合 `server-v*` 的 tag 会自动触发 Actions，并等价于发布 `all`，同时发布 `iw-core` 和 `iw-external`。
- 当用户要求“打 tag 发布”“发版”“发布生产”且明确希望通过 tag 触发时，先确认当前变更已提交并推送，再按 `server-v<version>` 命名创建并推送 tag，例如：

```bash
git tag server-v0.2.1
git push origin server-v0.2.1
```

- 如果用户只想发布单个服务，不要打 tag；应提示用户在 GitHub Actions 页面手动选择 `core` 或 `external`，或按需协助修改 workflow。
- 发布前至少运行核心链路编译：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests compile
```

## 常用命令

在本项目根目录执行。若本机 Maven 默认 Java 不是 17，显式设置 `JAVA_HOME`：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests compile
```

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests package
```

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-external -am -Dtest=BlogAccessServiceImplTest -DfailIfNoTests=false test
```

## 开发约定

- 先读本文件，再读根目录 `../AGENTS.md` 了解跨项目关系。
- 不要把 `../iw-mixes` 旧后端归档项目当作开发入口。
- 不要修改 `target/`、`*.iml`、IDE 缓存、构建产物和本地敏感配置。
- 修改公共模块 `iw-common`、`iw-web`、`iw-feign-client`、`iw-starter` 时，要评估 `iw-core` 和 `iw-external` 影响。
- 业务模块作为库模块时，不要让模块内 `application*.yml` 污染 `iw-core` 运行时配置。
- 新增接口时，给出前端可调用的完整 Nginx 入口路径。
- 新增公开外部接口时，默认使用 `iw-external` 的 `/external-service/api/**` 路径规范。
- 如果需求提到 `wx项目`、`微信小程序`，联动检查 `../iw-mixes-app-wx`；如果提到 `web项目`、`前端`，联动检查 `../iw-mixes-web-platform`。

## Agent skills

### Issue tracker

Issues are tracked in this repository's GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the five canonical engineering-skill labels. See `docs/agents/triage-labels.md`.

### Domain docs

Domain documentation uses the single-context layout. See `docs/agents/domain.md`.
