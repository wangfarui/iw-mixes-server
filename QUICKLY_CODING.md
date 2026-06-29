# 快速上手

## 技术栈

- JDK 17
- Spring Boot 3.2.x
- Spring Cloud OpenFeign 4.x
- MySQL 8.x
- Redis 7.x
- MyBatis-Plus 3.5.x
- Hutool 5.8.x
- SpringDoc 2.5.x

生产环境不再使用 Nacos、RocketMQ Broker 和独立 Spring Cloud Gateway。

## 常用命令

本地联调 Web 管理端或旧 uni-app 小程序时，前端仍访问 `http://localhost:18000`。默认不需要本地 Nginx：

- IDEA 启动 `IwCoreApplication`，Active profiles 可留空，或显式填 `dev`。
- IDEA 启动 `IwExternalApplication`，Active profiles 可留空，或显式填 `dev`。
- `iw-core` 的 `dev` profile 会监听 `18000` 并兼容 `/auth-service/**`、`/bookkeeping-service/**`、`/eat-service/**`、`/points-service/**`。
- `/external-service/**` 普通 HTTP 请求会由 `iw-core` 代理到 `iw-external:18006`。

如果要验证 `/external-service/wb/**` WebSocket，改用本地 Nginx：Nginx 监听 `18000`，`iw-core` 监听 `18080`，`iw-external` 监听 `18006`。

编译生产链路：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests compile
```

打包生产 Jar：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests package
```

运行 external 单元测试：

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  mvn -pl iw-packaging-parent/iw-external -am -Dtest=BlogAccessServiceImplTest -DfailIfNoTests=false test
```

## 项目规则

- 配置使用 Spring Boot 外部化配置，生产密钥放在服务器 `/etc/iw-mixes/*.env` 或 GitHub Secrets，不提交真实值。
- CRUD 习惯：新增使用 `add`，编辑使用 `update`，删除使用 `delete`，详情使用 `detail`，分页使用 `page`。
- 字典值沿用 `<id:name>` 存储和查询习惯。
- 数据库脚本放在对应模块的 `scripts` 目录。
- 包路径以 `com.itwray.iw` 开头。
- Redis key 保持有效期，避免业务变更后长期残留无意义缓存。
