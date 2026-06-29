# iw-mixes-server

`iw-mixes-server` 是基于原 `iw-mixes` 拆出的后端重构项目。旧 `../iw-mixes` 保持不动，本项目用于落地更适合小规模生产环境的后端结构。

## 运行结构

生产只保留两个 Java 进程：

| 服务 | 端口 | 部署位置 | 说明 |
| --- | --- | --- | --- |
| `iw-core` | `18080` | 内网 2C/8G 机器 | 聚合认证、记账、餐食、积分等内部业务能力 |
| `iw-external` | `18006` | 外网 2C/2G 机器 | 外部公开接口、AI、ASR、天气、热榜、钉钉等能力 |

MySQL、Redis、Nginx 继续放在外网机器。生产环境不再依赖 Nacos、RocketMQ Broker 和独立 Spring Cloud Gateway。原网关路径由 Nginx 转发兼容：

- `/auth-service/**`
- `/bookkeeping-service/**`
- `/eat-service/**`
- `/points-service/**`
- `/external-service/api/**`
- `/external-service/wb/**`

## 本地前端联调

Web 管理端和旧 uni-app 小程序仍默认访问 `http://localhost:18000`，并保留旧网关前缀。为了本地调试不用启动 Nginx，`iw-core` 在 `dev` profile 下会监听 `18000` 并启用本地兼容入口：

- `/auth-service/**` -> `iw-core` 内部去掉 `/auth-service` 前缀。
- `/bookkeeping-service/**` -> `iw-core` 内部去掉 `/bookkeeping-service` 前缀。
- `/eat-service/**` -> `iw-core` 内部去掉 `/eat-service` 前缀。
- `/points-service/**` -> `iw-core` 内部去掉 `/points-service` 前缀。
- `/external-service/**` 普通 HTTP 请求 -> 代理到 `iw-external`，默认 `http://127.0.0.1:18006`。

IDEA 调试建议启动两个 Application。项目默认 profile 已是 `dev`，Run Configuration 可以不填 Active profiles；如果手动填写，就填 `dev`：

- `com.itwray.iw.core.IwCoreApplication`，Active profiles 使用 `dev`。
- `com.itwray.iw.external.IwExternalApplication`，Active profiles 使用 `dev`。

真实数据库、Redis、AI Key、阿里云 Key 等本地密钥放到各模块不提交的 `application-dev.yml`。本地联调的非敏感默认值由 `application.yml` 内的 `dev` profile 段提供。

本地配置模板：

- [iw-core application-dev.example.yml](iw-packaging-parent/iw-core/src/main/resources/application-dev.example.yml)
- [iw-external application-dev.example.yml](iw-packaging-parent/iw-external/src/main/resources/application-dev.example.yml)

本地兼容入口不代理 WebSocket。需要验证 `/external-service/wb/**` 时，使用 [deploy/nginx/iw-mixes-server.local.conf](deploy/nginx/iw-mixes-server.local.conf)，并让 `iw-core` 改回 `18080` 运行，由 Nginx 监听 `18000`。

## 模块说明

| 模块 | 说明 |
| --- | --- |
| `iw-common` | 通用响应、异常、常量和工具 |
| `iw-web` | Web 基础层、统一响应、异常处理、认证辅助、MyBatis、Feign、Swagger 等 |
| `iw-feign-client` | 内部 HTTP Client，使用显式 URL，不再依赖服务发现 |
| `iw-starter/iw-redis-starter` | Redis 辅助 starter |
| `iw-starter/iw-rocketmq-starter` | 保留旧名称的本地事件 starter，不连接 RocketMQ Broker |
| `iw-packaging-parent/iw-core` | 生产核心服务启动模块 |
| `iw-packaging-parent/iw-auth` | 认证和基础资料库模块 |
| `iw-packaging-parent/iw-bookkeeping` | 记账库模块，并聚合餐食、积分依赖 |
| `iw-packaging-parent/iw-eat` | 餐食库模块 |
| `iw-packaging-parent/iw-points` | 积分库模块 |
| `iw-packaging-parent/iw-external` | 生产外部能力服务 |

## 构建

本机 Maven 默认可能是 Java 8，构建时必须使用 JDK 17：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests package
```

产物：

- `iw-packaging-parent/iw-core/target/iw-core-*.jar`
- `iw-packaging-parent/iw-external/target/iw-external-*.jar`

## 发布

发布配置见 [deploy/README.md](deploy/README.md)。默认方案是 GitHub Actions 构建 Jar，通过 SSH 发布到外网机器和内网机器，并由 systemd 托管服务。

架构改造方案见根目录 [iw-mixes-server-refactor-plan.md](../iw-mixes-server-refactor-plan.md)。
