# iw-mixes-server

`iw-mixes-server` 是 IW 系统当前后端主项目，用于落地更适合小规模生产环境的后端结构。旧后端项目已废弃，不再作为开发入口。

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

本地快捷启动脚本：

```bash
# 同时启动 iw-external 和 iw-core，用于前端访问 http://localhost:18000
scripts/local-backend.sh all

# 也可以分别启动
scripts/local-backend.sh start external
scripts/local-backend.sh start core

# 查看状态、跟日志、停止服务
scripts/local-backend.sh status
scripts/local-backend.sh logs core
scripts/local-backend.sh stop all
```

脚本会使用 JDK 17 执行 Maven package，然后以 `dev` profile 后台运行 Jar。默认日志和 PID 位于 `logs/local/`；如需跳过构建，可设置 `IW_LOCAL_SKIP_BUILD=1`。本地数据库、Redis、AI Key、阿里云 Key 等仍通过环境变量或不提交的 `application-dev.yml` 提供。

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

发布配置见 [deploy/README.md](deploy/README.md)。生产日志和故障排查命令见 [deploy/OPERATIONS.md](deploy/OPERATIONS.md)。默认方案是 GitHub Actions 构建 Jar，通过 SSH 发布到外网机器和内网机器，并由 systemd 托管服务。

### 发布链路

GitHub Actions 工作流在 [.github/workflows/deploy-prod.yml](.github/workflows/deploy-prod.yml)。

发布链路：

1. `build`：在 GitHub runner 上使用 JDK 17 执行 Maven package，构建 `iw-core.jar` 和 `iw-external.jar`。
2. `deploy-external`：通过 SSH 直连外网机器，上传 `iw-external.jar`，执行 `deploy/scripts/deploy-jar.sh iw-external ...`。
3. `deploy-core`：通过外网机器作为 SSH 跳板连接内网机器，上传 `iw-core.jar`，执行 `deploy/scripts/deploy-jar.sh iw-core ...`。

`deploy/scripts/deploy-jar.sh` 会把 Jar 安装到 `/opt/iw-mixes-server/releases/<service>/`，更新 `/opt/iw-mixes-server/<service>/<service>.jar` 软链接，然后执行：

```bash
systemctl restart <service>
```

因此发布某个服务时会短暂重启该服务。未选择发布的服务不会被停止或重启。

### GitHub Secrets

仓库需要配置这些 Actions Secrets：

```text
IW_DEPLOY_SSH_KEY
IW_PUBLIC_HOST
IW_PUBLIC_USER
IW_PUBLIC_PORT
IW_CORE_HOST
IW_CORE_USER
IW_CORE_PORT
```

当前生产拓扑建议：

```text
IW_PUBLIC_HOST = 外网机器公网 IP 或域名
IW_PUBLIC_USER = root 或部署用户
IW_PUBLIC_PORT = 22

IW_CORE_HOST = 172.22.61.87
IW_CORE_USER = root 或部署用户
IW_CORE_PORT = 22
```

`IW_DEPLOY_SSH_KEY` 对应的公钥必须同时存在于外网机器和内网机器发布用户的 `~/.ssh/authorized_keys` 中。内网机器不需要公网 IP，Actions 通过外网机器 `ProxyJump` 进入。

### 手动触发发布

在 GitHub 页面进入：

```text
Actions -> Deploy iw-mixes-server -> Run workflow
```

`Service to deploy` 可选：

- `all`：构建后发布 `iw-core` 和 `iw-external`。
- `core`：只发布并重启 `iw-core`，不会停止或重启 `iw-external`。
- `external`：只发布并重启 `iw-external`，不会停止或重启 `iw-core`。

无论选择哪个目标，`build` 都会构建两个 Jar；区别只在后续部署 job 是否执行。

### Tag 自动发布

推送符合 `server-v*` 的 tag 会自动触发 Actions，并等价于发布 `all`，即同时发布 `iw-core` 和 `iw-external`：

```bash
git tag server-v0.2.1
git push origin server-v0.2.1
```

如果只想发布单个服务，不要用 tag 触发；请在 GitHub Actions 页面手动选择 `core` 或 `external`。

### 发布前检查

发布前建议至少执行：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests compile
```

确认本地变更已提交并推送到 GitHub 后，再手动运行 workflow 或推送 `server-v*` tag。
