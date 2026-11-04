# 2026-11-02 分支 SHA 缓存优化与十月实测

结论：优化已实施，本地完成十月一次缓存建立与多次热复跑。冷扫描 13 分 29 秒，最终版本热复跑分别 42 秒、37 秒；所有仓库读取成功，提交集合与代码行结果保持一致，3 条既有 diff 失败如实保留为 PARTIAL。未提交、推送或发布。

## 行为与文件

- CodingOpenApiPort / CodingOpenApiClient：保留已有分支接口的 SHA，并提供可选最后提交时间。使用当前接口已提供的 SHA，避免为取得时间字段额外增加分页目录请求；没有用最后提交时间硬排除历史月份。提交响应未知时明确失败，Data.Page 的总数用于准确终止分页。
- CodeScanWorker：按 CODING 团队、范围及成员/团队 ID、仓库、分支头 SHA、自然月和查询截止日隔离缓存；同仓库相同 head 的多个分支只读取一次。命中完整缓存的未变 head 跳过提交查询；新 head、无 SHA、缺失/损坏缓存均安全回退读取。变化 head 重新读取该月完整窗口，代替之前仅回溯两天的弱增量。
- CodeStatsViewCache：Redis zhaogang:code:v1:branch-month:v1: 命名空间保存规范化提交快照，邮箱只有哈希，不保存 PAT 或原始作者邮箱字段；TTL 7 天，单条 JSON 上限 1 MiB，超过上限或 Redis 不可用时不缓存，仍可正常扫描。没有新增或修改数据库表结构。
- 新读取结果在再次核对分支头稳定后才发布缓存；读取失败、分页截断或分支变化不发布不完整快照。分页中途失败保留此前成功页，其他分支继续。
- CodeScanReadAhead 改为每批最多四个不同 head 的完整分页读取，保持现有单 PAT 并发及 QPS 门控；结果在主扫描线程聚合和写库。
- 重放缓存时按当前分支目录重建归属，并更新本轮 last_seen_scan_id，避免全量清理误删复用结果。截断分支样本按名称排序，避免缓存命中顺序影响展示。

## 实际任务与性能

| 任务 | 类型 | 开始/结束（北京时间） | 用时 | 仓库/分支 | 提交分页调用* | 复用分支 | 新读取 head | 结果 |
|---|---|---|---|---|---|---|---|---|
| 12 | 首次缓存建立 | 14:24:21—14:37:50 | 809 秒 | 232 / 9571 | 9029 | 783 | 8788 | PARTIAL，3 个 diff 失败 |
| 15 | 最终版本热复跑 | 14:48:26—14:49:08 | 42 秒 | 232 / 9571 | 2 | 9570 | 1 | PARTIAL，原有 3 个 diff 失败 |
| 16 | 最终版本再次热复跑 | 14:50:47—14:51:24 | 37 秒 | 232 / 9572 | 1 | 9571 | 1 | PARTIAL，原有 3 个 diff 失败 |

*计数是提交分页适配器调用，不包含适配器内部重试、仓库/分支目录和 diff 请求，不能解释为全部 HTTP 请求数。任务 14 是排序修复前的中间热验证，62 秒、28 次分页；任务 13 是此前待处理的 diff 补齐，未计入完整扫描对比。

快照缓存不会消除分支目录检查，因此不是零请求扫描。首次扫描或缓存过期仍需建立缓存；复跑实测不能承诺每次相同耗时。

## 数据对账

三轮均保存 4534 个不同的仓库/SHA 提交键；无重复去重键。2023 个合并提交被排除；2511 条非合并提交中，2413 条未归属、95 条 diff 成功、3 条 diff 失败。本人已归属共 98 次，16 个活跃日、6 个仓库；已取得新增 8208 行、删除 2302 行、净增 5906 行。3 条失败的行数不伪造补零，整体结果不是完整 diff 成功。

初始完整指纹对比暴露了截断分支样本在冷/热合并顺序变化时不稳定，已改为确定性排序并增加回归测试。最终任务 15/16 的提交键、合并状态、逐提交 diff 状态、增删行数和文件数指纹完全一致：

`7795584b602490ee885f096dc5205e209580aaa5b4e38554013ea96c131e6d8e`

最终两个全字段指纹不完全相同，原因已逐行确认：扫描期间新增 1 个真实分支，只改变 4 条已有提交的 branch_names / branch_count；提交增加 0、删除 0，其他字段无差异。该差异是正确反映当前分支归属，不伪报全字段一致。

## 验证与运行版本

后端完整 Maven 测试 278 项，失败/错误/跳过均为 0；Web type-check、build、check:zhaogang-release-notes、test:zhaogang-code-stats-range 全部退出 0。两个仓库 git diff --check 通过。新增缓存回归覆盖冷/热请求抑制、变化 head、不同月份/仓库、新分支归属、缺失 SHA、损坏缓存、读取中 head 移动、分页失败保留成功数据和截断样本稳定性。

- 后端 HEAD：0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3；Web HEAD：d821532cfc9209085e6436093f52df5c9764547f；均含原有与本次未提交改动。
- Java 17.0.9，Maven 3.8.6，显式 /usr/local/maven/repository，独立最小 settings；Node 18.17.0，npm 9.8.1，Vite 5.4.21。
- 最终 JAR iw-external-0.2.1.jar 时间 2026-11-02 14:47:26 +0800；运行前已停止旧进程，通过 local-backend.sh 构建/启动，没有覆盖运行中的 JAR。
- 测试时仅在本地启动进程临时把历史月/失败/单用户冷却设为 0。结束后通过脚本重新启动恢复默认：currentMonthCooldownSeconds=600、historicalMonthCooldownSeconds=86400、failedRetrySeconds=600、perUserCooldownSeconds=300。恢复后真实绑定态 options 已核对上述四值。
- 恢复后 core PID 75486、external PID 75609，均 script-managed；当前活动扫描任务 0。浏览器已打开十月结果页。

## 数据保留与限制

按本次用户要求保留十月真实扫描结果、历史任务与分支缓存用于查看和后续增量复用；不把实际业务扫描当作合成测试数据删除。新增单测只用隔离 HTTP fixture 和内存替身，没有数据库测试行。四张功能表以外没有业务数据写入，没有修改凭证、没有 CODING 写操作，也没有生产/共享测试库操作。

本次重点为本人 SELF 范围十月统计；团队多 PAT、全部 AC、3 条持续失败 diff 的进一步原因不在本次性能通过结论中。

## 本轮文件清单

后端主源码（均位于 iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/）：CodingOpenApiPort.java、CodingOpenApiClient.java、codestats/CodeScanWorker.java、codestats/CodeScanReadAhead.java、codestats/CodeStatsViewCache.java。

测试（均位于同模块 src/test/java/com/itwray/iw/external/zhaogang/）：CodingOpenApiClientTest.java、codestats/CodeScanWorkerBranchCacheTest.java（新增）、codestats/CodeScanReadAheadTest.java、codestats/CodeScanWorkerPartialTest.java。

文档：本记录、交接包 README.md、local-acceptance-handoff.md、data-and-scan.md。Web 本轮仅执行验证，无业务源码修改；此前会话已有的 Web 未提交修改均保留。
