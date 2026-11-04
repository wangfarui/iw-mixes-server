# 2026-11-02 本地真实验收记录：S0 受阻

本次按交接包顺序执行，未采用 2026-10-30 结果作为本次证据。用户已授权本地迁移、脚本启动 external、本人只读 PAT 扫描及浏览器验收，并要求前置断言失败即停止。

## S0 结果

- 后端 HEAD `0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3`，20 条工作区状态记录；Web HEAD `d821532cfc9209085e6436093f52df5c9764547f`，11 条工作区状态记录。保留原有修改。
- `scripts/local-backend.sh status` 报 `iw-core: stopped`、`iw-external: stopped`。本地 18000 有 Java PID 69793 监听，根路径 HTTP 404；18006 未监听。沙箱内 `ps` 返回 `operation not permitted`，无法确认 18000 服务身份或托管状态。脚本报告的 core 状态与监听事实矛盾，S0 的 core 前置条件未能确认，按停止条件终止。
- 127.0.0.1 的 3306 和 6379 TCP 可连接；未验证数据库认证、目标库身份及目标表情况。
- Node v18.17.0、npm 9.8.1、Vite 5.4.21；Java/Maven、运行 jar 时间戳未核查。基线检查报告源码指纹变化，与已实施未提交代码相符；未重置或覆盖基线。

## AC 及数据残留

AC-01 至 AC-16 本次均未执行：S0 阻断，未进入对应步骤。未执行 Maven 或 Web 回归，未进行真实 PAT、接口、数据库、Redis 或浏览器业务验证。

本次未执行迁移、启动或重启服务、扫描、身份映射或清理，未创建本功能测试数据；既有四表与 Redis 键是否存在及其数据量未核查，不能声称为空。S8 清理未执行，因为未进入该步骤且本次未创建数据。

恢复验收需先在允许检查进程的执行环境中核实 `iw-core`、脚本状态及 18000 监听进程的关系，满足 S0 后从 S0 继续；不得据此抢占或重启未知进程。

## 续验：S1 通过，S2 失败

用户授权通过脚本启动 core。以现有 JAR 和 `IW_LOCAL_SKIP_BUILD=1` 启动后，脚本曾报告 PID 99742、18000 监听、script-managed。后续 S2 结束时 core 已停止且 18000 不监听，原因未确认。

S0 数据库只读确认配置指向本地 `iw_mixes`，四张目标表均不存在。S1 执行授权迁移前再次按表名精确查询确认均不存在，无需备份；迁移后四表齐全，`uk_scope_depot_sha` 和 `uk_active_key` 分别包含 5 列和 1 列，均为唯一索引。

S2 使用项目脚本启动 external，脚本构建采用 Maven 3.8.6、显式 `/usr/local/maven/repository` 和临时最小 settings；脚本返回 exit 1，external 启动后退出、18006 未监听。脱敏错误为 `DefaultCodeStatsModule` 实例化失败：`No default constructor found`，根因异常类型 `java.lang.NoSuchMethodException`。自动审批首次超时，重试后命令获得执行。遵照失败即停止，未进行修复或后续步骤。JAR `iw-external-0.2.1.jar` 时间 2026-11-02 10:11:42 +0800；未成功运行，不能作为运行版本。

迁移后四表各 0 行；本次未触发扫描或身份操作，未执行 S8 清理。保留新增四张空表；Redis 键未检查。AC-01 至 AC-16 本次均未执行，阻断点更新为 S2。未运行 S8 回归。

## 续验：启动修复、S2 通过、S3 等待绑定

`DefaultCodeStatsModule`、`CodeScanService`、`CodeScanWorker` 因均有两个构造器且未指定 Spring 注入构造器，分别暴露无默认构造器异常；对生产构造器标注 `@Autowired`。新增 `CodeStatsModuleContextTest`，修复前针对模块及扫描工作线程均复现原异常，修复后三个实际组件的 Spring 容器测试通过。

服务通过 `local-backend.sh` 启动，需保持执行会话以免后台进程被回收；本次观测 core PID 6479、external PID 6660，端口 18000 和 18006 监听，18000 兼容入口 options 返回业务码 401。实际运行 JAR 为 `iw-external-0.2.1.jar`，时间 2026-11-02 10:21:59 +0800。后端 HEAD `0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3`，Web HEAD `d821532cfc9209085e6436093f52df5c9764547f`；Java 17.0.9、Maven 3.8.6、Node 18.17.0、npm 9.8.1、Vite 5.4.21。

S3 启动 Vite 并访问 127.0.0.1:5173 的本地工作台，页面显示首次使用及令牌绑定表单，本地未绑定。已请求用户本人在页面安全输入只读 PAT；未读取或复制凭证。S3 后续扫描及 AC-01 至 AC-15 未执行。

后端修复后的全量 Maven 测试通过：iw-external 256 项，失败 0、错误 0、跳过 0。Web `type-check`、`build`、`check:zhaogang-release-notes`、`test:zhaogang-code-stats-range` 均退出 0；AC-16 的命令检查通过。四张新表此前核对各 0 行，本次未触发扫描，Redis 未核查，S8 数据清理未执行。

## 续验：本地已绑定，S3 断言不一致

用户确认本地完成绑定；浏览器设置页显示连接正常。绑定态经 Vite 访问 `code-stats/options`，响应 `success=true`、业务码 `200`，有团队选项；交接 S3 要求业务码 `0`，但当前源码 `iw-common/.../GeneralApiCode.java` 定义成功码为 `200`。此差异不能记为交接原断言通过，也未为迎合文档更改公共响应规范。

打开我的代码页面时自动产生一条扫描任务。S4 历史月扫描前查询：任务 1 行、提交 0 行；该任务为当前月 `2026-11`、`FULL`、`COMPLETED`、`DONE`、仓库/分支/提交/失败数均为 0，任务 ID 1。页面展示扫描完成 0 仓库。当前月预期增量以及 0 仓库是否与真实可读仓库一致仍需核实；未经核实不认定通过。按交接中途停止要求保留任务，不执行历史月重扫和 S8 清理。四表新产生任务 1 行，提交 0 行；其他表及 Redis 本次未复查。

AC-01 至 AC-15 真实验收仍未完成；AC-07 已观察当前月自动任务模式为 FULL，与交接中的 INCREMENTAL 预期不一致。AC-16 回归通过见上节。
