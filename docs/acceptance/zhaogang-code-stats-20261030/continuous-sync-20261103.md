# 持续同步实施与本轮验证：2026-11-03

> 以下为上午截至 11:07 的初验记录，保留当时真实状态。任务 29 后于 13:06 结束（部分完成）；新的稳定分支进度修正、运行版本及增量比较见 [下午续验](continuous-sync-followup-20261103.md)。当前新版本入口为 localhost:5175，上午的 5174 不再作为新版本验收入口。

结论：本地持续同步层已实施并加载运行，源码未提交。后端 309 项测试和 Web 四项检查通过；真实本人绑定态的按月查询、回补入队、取消、游标恢复及 MySQL 并发保护已验证。**从 2026-01-01 开始的完整历史回补仍在运行，真实新增提交增量耗时和 3 小时定时实际触发尚未完成验收。** 不用本次单测、旧扫描结果或截图宣称这些场景已经通过。

用户确认：本地持续同步；每 3 小时后台增量；历史起点 2026-01-01。原姓名/邮箱身份过滤、合并排除、特殊仓库只计提交规则保持。设计见 [本轮规则](../../plans/zhaogang-code-stats-20261030/continuous-sync-20261103.md)。

## 真实环境与运行版本

- 后端 HEAD：`0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3`；Web HEAD：`d821532cfc9209085e6436093f52df5c9764547f`。存在原有未提交/未跟踪功能改动，基于现状增量修改，未 reset/checkout/stage/commit/push。
- Java 17.0.9（JetBrains）、固定 Maven 3.8.6，显式仓库 `/usr/local/maven/repository`，独立最小 settings `/private/tmp/iw-acceptance-maven/settings.xml`。检查 Maven/Java 选项及项目 .mvn，没有冲突仓库覆盖；global settings 的 localRepository 也为上述路径。
- Node 18.17.0、npm 9.8.1、Vite 5.4.21。
- 运行 JAR：`/private/tmp/iw-continuous-build-complete/iw-packaging-parent/iw-external/target/iw-external-0.2.1.jar`；时间 `2026-11-03 11:02:22 +08:00`；SHA-256 `d289e45fae8296aa1835a2b17603f94cdad87ffc84c14a05861462f80a8778e4`。相关 28 个 Java 源文件逐字节比对与工作区一致。
- 用 `scripts/local-backend.sh` 启动，PID 23036、端口 18016，health HTTP 200。Web 实际地址 `http://localhost:5174/zhaogang/code/mine`，通过可选开发代理指向 18016，复用本人既有绑定；没有重新填写、导出 Cookie 或打印 PAT。
- 旧 18006（PID 5301）仍监听；执行器的 ps/跨上下文进程控制受系统限制，脚本不能安全识别/停止它。旧 localhost IPv6 5173 也仍占用。没有误杀或绕过脚本启动，改用隔离端口及独立打包目录，未覆盖任何仍在使用的 JAR。**本轮新能力请使用 5174，不能用旧 5173 的页面充当新版本验收。**

## 前置断言、迁移与清理

迁移前确认实际 JDBC 仅为 127.0.0.1/iw_mixes，活动任务为 0、新增字段为 0。四张功能表先备份到私有文件：

`/var/folders/wv/0ljm0h256y1_f_fgv2yw62rh0000gn/T/iw-continuous-before-fur6ckp7.sql`（0600）。

先增加 5 个同步字段及范围索引；后补日期查询索引、生成并发键前分别断言索引/字段不存在且无活动任务。最终新增 6 个字段（含生成列），索引为 idx_code_sync_scope、idx_code_scope_date、uk_code_active_scope。只修改既有代码统计表，未修改凭证表、团队表、其他业务数据或共享库。

中间任务 27/28 分别在修正并发读取、成功后旧范围清理前通过正常 UI 取消。两轮都只完成了前两个仓库的持久游标、写入提交 0 条。任务 29 已恢复游标并发布新副本，确认旧游标正文均已退休、无提交引用 27/28 后，将它们的任务/仓库行备份并精确删除：

`/var/folders/wv/0ljm0h256y1_f_fgv2yw62rh0000gn/T/iw-continuous-intermediate-jobs-4jhfecre.sql`（0600）。

清理后任务 27/28、对应仓库行、引用提交数均为 0；Redis 只 DEL 精确的 scan:cancel:27 和 scan:cancel:28，残留为 0。没有 FLUSH、没有删除范围外键或真实身份。未创建新的真实身份夹具、CODING 提交/分支/项目/构建。

保留功能数据：本人原有 10/11 月统计、任务 29、其范围订阅及有效仓库游标。回补会继续产生真实命中提交和 diff，这些是用户需要的功能数据，不作为测试夹具清除。原有其他任务与团队数据保持；本轮没有使用其他成员 PAT 发起扫描。

## 验证结论与证据

| 编号 | 真实操作/前提 | 结论 | 证据与限制 |
| --- | --- | --- | --- |
| CS-01 | 未运行任务时，在真实绑定态切换本月→十月→本月 | 通过 | 切换前后最大任务 ID 26、任务数 21、活动数 0；十月直接显示 98 次、8208/2302 行。GET 不再启动整月任务。 |
| CS-02 | 正常 UI 立即同步 | 通过入队；全年回补进行中 | 最终任务 29 为 SYNC，范围 2026-01-01～2026-11-03；11:07:59 为 RUNNING/COMMITS，232 仓库、9584 分支，本轮完成 84 分支、已写入 0 条命中提交，不能把前两个仓库完成当作全年完成。 |
| CS-03 | 最终任务运行时再切到十月 | 通过 | 本次 CUA 点击及读取结果总耗时约 2.17 秒（不是 HTTP 单独计时）；已有 98 次、8208/2302 行、16 天、6 仓库正常展示，不等待回补。仍只有任务 29。 |
| CS-04 | 旧实例停止后用最终 JAR 续跑 | 通过仓库游标恢复 | 两个已完成仓库游标由 27→28→29 恢复并保存，29 有 2 个有效压缩副本共 384 字节，旧任务不再有游标正文。真实变更分支的完整增量耗时尚未观测；不能把零命中仓库的恢复推广为全部仓库耗时。 |
| CS-05 | 行为测试：同头合并、跨日未变头、跨日正常快进、合并侧链、强推、损坏游标、分页失败、分页截断 | 通过自动化 | CodeBranchSyncTest、CodeContinuousWorkerTest；未变头第二轮不再调用提交 API；新头只追加新记录且保留旧记录；所有父链闭合才停止；后续分页失败保留已成功元数据，不发布完整游标。不是实网新增提交窗口证明。 |
| CS-06 | 3 小时调度、仅持久订阅、使用当前凭证、无活动重复入队 | 声明与受控行为通过；实际时钟触发未执行 | cron `0 0 */3 * * *`，Asia/Shanghai；CodeContinuousScheduleTest。下一时点 12:00，本次未等待该时点，不宣称已实测触发。 |
| CS-07 | 任务 29 活动时，以旧 active_key 格式尝试同范围另一个月份任务 | 通过真实 MySQL | 事务内 INSERT 被 uk_code_active_scope 拒绝，回滚；未落库额外任务。保护旧本地版本与新 SYNC 不会并发写同一范围。 |
| CS-08 | 特殊仓库与身份口径对账 | 通过现存真实数据核对；全年新数据未完成 | 十月 apijar 非合并 9 条全部 SKIPPED_REPOSITORY、行数字段非空数 0；十月总数 98、行数 8208/2302 保持一致。不冒充本轮已经重新读完所有真实 diff。 |
| CS-09 | 无 Cookie 调用新后端统计接口 | 通过 | HTTP 200/业务 code 401，未返回本人统计数据。跨团队/其他用户真实角色组合本轮未执行；原有权限回归仍在 309 项检查中。 |
| CS-10 | 后端实际命令 | 通过 | 固定 Maven `-pl iw-packaging-parent/iw-external -am test`，最终 309 项、失败/错误/跳过均 0；独立副本 package 成功。曾有一次新测试 mock 重设触发空参数，已改用 doThrow 并重跑通过。 |
| CS-11 | Web 实际命令 | 通过 | npm run type-check、npm run build、npm run check:zhaogang-release-notes、npm run test:zhaogang-code-stats-range。build 保留已有 NODE_ENV/pdf.js eval/chunk 大小提示，无新增构建失败。 |
| CS-12 | 团队真实持续同步、真实新增 head 的全链路性能、全年完成对账 | 未执行/未完成 | 本轮授权实网只使用本人 PAT；全年回补仍在运行，未制造真实 CODING 提交以测速。后续需在正常新增提交窗口记录新增条数、提交分页数、diff 数与耗时。 |
| CS-13 | 本轮夹具与中间数据清理 | 通过 | 中间 27/28 任务/仓库/提交引用 0，精确取消键 0；真实功能任务 29 及有效数据保留。 |

证据：[脱敏版本与数据库快照](continuous-sync-20261103-evidence.json)、[实际十月页面](continuous-sync-20261103.png)。截图已打开检查，没有 PAT、Cookie、token、认证头。

## 本轮改动文件

后端主要实现：

- `codestats/CodeBranchSync.java`、`CodeSyncCheckpointCodec.java`、`CodeContinuousSyncScheduler.java`（新增）。
- `codestats/CodeScanWorker.java`：持续范围、分支分组/并发、父链增量、持久进度、部分失败、成功后范围清理。
- `codestats/CodeScanService.java`：查询不调度、SYNC 触发、范围独占、冷却、状态及保留清理。
- `codestats/DefaultCodeStatsModule.java`、`CodeStatsModels.java`、`CodeStatsQueryService.java`、`CodeIdentityService.java`、`entity/CodeStatsEntities.java`。
- `ZhaogangProperties.java`、`mapper/ZhaogangCodeScanJobMapper.java`、`ZhaogangCodeScanRepoMapper.java`、`ZhaogangCodeCommitMapper.java`。
- `scripts/iw-external-migrate-20261103-continuous-sync.sql`、`iw-external-init.sql`、`iw-external-migrate-20261030-code-stats.sql`。
- `CodeBranchSyncTest.java`、`CodeContinuousWorkerTest.java`、`CodeContinuousScheduleTest.java`（新增）；`CodeScanIdentityRefreshTest.java` 改为确认查询不调度。

以上 codestats 位于 iw-external 的 zhaogang 子包。其余原有未提交功能文件保留，没有混入提交或回滚。

Web：

- `src/views/zhaogang/code/components/CodeStatsHeader.vue`、`CodeStatsScanBanner.vue`。
- `src/views/zhaogang/code/ZhaogangCodeMineView.vue`、`ZhaogangCodeTeamView.vue`、`codeStatsPresentation.ts`。
- `src/types/zhaogangCodeStats.ts`、`vite.config.ts`（仅本地可选找钢代理，默认不变）。

文档：本记录、版本快照、截图、持续同步方案及主 README 最新口径。小程序未改。没有提交、推送、PR、生产/测试共享库写入或任何 UAT/PRD 构建。
