# 工时已删除事项异步过滤验收

日期：2026-11-13。任务续接自 `01a1cfba-c540-7541-ab37-0540ff7af0f8`。后端位于 `main`、Web 位于 `dev`，均基于已有未提交改动增量完成。本任务未提交或推送。

## 实施结果

- `iw-external` 新增 `WorklogDeletedIssues`、`WorklogIssueChecks` 和对应数据库迁移。月报、缺勤、明细在聚合前使用内部事项 ID 批量过滤本地永久删除记录；后台仅对本次工时关联的候选查验，保留原有成员 PAT 隔离和 CODING 限流。
- `WorklogViewCache` 使用团队删除版本隔离旧结果。新增删除记录与版本递增在同一数据库事务中；同一账号不同 PAT 会话也分别缓存，避免读取不属于当前会话的后台状态引用。
- `WorklogModels`、`WorklogModule`、`ZhaogangController` 新增 `issueCheck` 和 `/worklogs/issue-check`。状态接口只读本地状态，继续校验会话及团队访问权限。
- Web 的 `src/api/zhaogang.ts`、`src/types/zhaogang.ts`、`WorklogDashboard.vue`、`WorklogAbsenceDialog.vue` 接入有界轮询、报表自动重读和明细信息补齐，刷新时保留已展示的缺勤结果。

## 自动验证

后端定向测试 **39 项通过，0 失败、0 错误、0 跳过**，包括月报、缺勤、明细、范围解析、缓存、日历、事项查验和 Controller。新增回归覆盖后台阻塞时首屏仍返回、只记录唯一可信内部 ID、权限拒绝/超时/限流/空响应保留工时、跨成员相互矛盾的证据不记录删除，以及同账号不同查看者 PAT 的缓存隔离。

使用 Java 17、Maven 3.8.6、显式临时空 settings 和 `/usr/local/maven/repository`；已排除环境变量和 `.mvn` 的仓库路径冲突，不读取用户级 settings。隔离源副本的 `-DskipTests=true package` 成功，运行包中的相关源码与最终工作区源码一致。Web `npm run type-check`、`npm run build` 均通过；构建仍提示已有大 chunk 警告。

## 隔离运行结果

CODING 使用仅监听本机的模拟接口，后端、MySQL 8.0.31、Redis 均为真实进程。与真实 CODING 或线上性能不能等同。

夹具包含四笔登记：已删事项两笔共 11h，另一项目相同事项编号的 8h，以及无详情读取权限事项的 2h。对已删事项的 `DescribeIssue` 固定延迟 6 秒。

| 场景 | 结果 |
| --- | --- |
| 两个 PAT 会话并发加载同一团队月报 | 分别 150.96ms / 150.46ms 返回，均为 21h，均早于详情查验完成 |
| 同范围缺勤与明细 | 分别 34.56ms / 26.26ms 返回，明细初始 21h |
| 后台确认删除 | 约 6.07 秒完成；月报和明细变为 10h，11 月 6 日缺勤变为 2h |
| 跨项目相同编号和权限受限事项 | 保留 8h、2h；最终只剩工时记录 ID 3、4 |
| 并发重复发现 | CODING 团队 861102 只保存内部事项 34996761 一条删除记录，删除版本为 1 |
| 状态引用归属 | 本 PAT 的状态为 pending，另一个 PAT 查询该引用为非 pending，自己的引用可继续等待 |
| 独立 JVM 读取已持久化事实 | 新建数据访问、查验和工时服务实例后立即过滤 11h，结果仍为 10h；不再查验已删事项，重复记录后版本仍为 1 |
| 初始化与增量迁移 | 前一会话已在该隔离实例初始化后连续执行迁移两次，两张表均存在 |

浏览器使用 Chrome 的实际 Web 页面验证：个人月报先显示 21h，之后自动显示 10h；打开缺勤后可见 11 月 6 日 2h；个人/团队本月明细均仅显示 8h 与 2h，正常事项标题已补齐，团队月报总计 10h。最后的查看者 PAT 缓存键修正通过双 PAT HTTP 场景和回归测试核验。浏览器通道后续超时，未补做最终缓存键版本的双会话 UI 验收、窄屏和快速切换范围验收。

独立 JVM 验证的是新进程读取持久化结果和重新计算月报；未把原有常用后端进程重启验证表述为已完成。未再次访问真实 CODING 样本，未执行真实 CODING 写入。

## 证据与现场

- [冷加载指标](cold-result.json)、[独立 JVM 持久化结果](persistence-result.txt)。原始临时日志：`/tmp/iw-worklog-resumed-tests.log`、`/tmp/iw-worklog-resumed-web-build.log`；隔离目录：`/tmp/iw-deleted-accept.r1dFzM`。
- 原有 `18006` 后端未重启。隔离 Web 为 `5180`，前一会话后端为 `18016`，复现用旧进程为 `18017`，最终修正验证后端为 `18018`；MySQL `13316`、Redis `16379`、CODING 夹具 `18117/18118/18119`。
- 隔离数据库和临时源副本保留，未向共享数据库写入测试数据。尝试使用项目停止脚本及提升权限后的定向 SIGTERM 清理旧隔离 Java 进程，操作系统均返回 `Operation not permitted`，因此未停止上述临时服务，也未删除数据目录；没有扩大为终止原有服务。

## 现有环境接入

部署新版后端前先执行 `iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261113-worklog-deleted-issue.sql`，新环境初始化 SQL 已含两张表。当前只验证了隔离环境；现有本地共享库、测试库及生产库均未执行该迁移，未发布服务或 Web。

正常事项的 5 分钟缓存表示再次查验的资格间隔，不是无条件发现删除的承诺；新删除发现依赖后续工时取数、队列和 CODING 可用性。首次确认前，新删除事项仍可能短暂计入结果。
