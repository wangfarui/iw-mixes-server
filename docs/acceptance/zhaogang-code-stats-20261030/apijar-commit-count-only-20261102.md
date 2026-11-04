# 特殊仓库仅统计提交次数：2026-11-02 本地验收

本次用户明确要求 `online.base.apijar.service` 仅统计提交次数，不统计行数。按完整仓库名精确匹配，其他仓库不受影响；合并提交继续按既有规则排除。

## 实现与口径

- 新增 `CodeLinePolicy`，特殊仓库非合并提交使用 `SKIPPED_REPOSITORY`；新增/删除行数、文件数均为 NULL，明细显示“—”，不请求 CODING diff，不进入行数覆盖率分母或 diff 失败数。
- 新扫描、历史查询、旧分支快照重放、已有待处理 diff 均应用规则，避免历史值或缓存重新引入行数。
- 仓库响应增加 `lineStatsExcluded`，真实个人页面显示“仅统计提交次数”；提交次数、日期、分支和身份归属保留。

## 真实本地数据与扫描

迁移前已停止旧 external，确认仅操作本机 127.0.0.1:3306/iw_mixes。受影响记录备份至 `/private/tmp/iw-apijar-policy-before-hdjz9hhv.sql`（0600），备份不包含凭证表。迁移脚本 `iw-external-migrate-20261102-apijar-commit-only.sql` 规范化 191 条特殊仓库非合并提交；再次执行影响 0 条。迁移前后功能提交表总数均为 4705。

真实绑定态通过浏览器触发十月全量任务 19：北京时间 15:23:08—15:24:20，72 秒；状态 COMPLETED/DONE，232 仓库、9573 分支、4534 个不同仓库/SHA 提交键，仓库失败 0、diff 失败 0。本轮待拉 diff 为 0，不代表重新调用并成功取得所有 diff。

特殊仓库十月保留 198 条记录，其中 14 条合并提交按原规则排除、184 条非合并为 SKIPPED_REPOSITORY；本人 9 次提交保留，浏览器仓库列表显示“online.base.apijar.service / 9 / 仅统计提交次数”。十月全部状态为：SKIPPED_UNMAPPED 2238、SKIPPED_MERGE 2023、SKIPPED_REPOSITORY 184、OK 89。此前 3 条失败属于特殊仓库，现按业务规则跳过，不解释为上游 diff 接口已修复。

本人十月整体仍为 98 次提交、16 个活跃日、6 个仓库；已统计新增 8208、删除 2302、净增 5906 行。特殊仓库历史成功 diff 的增删数原本均为 0，故此次排除未改变这些整体行数。

## 验证与版本

- 后端完整 Maven test：284 tests，0 failures/errors/skipped，BUILD SUCCESS。使用 `/usr/local/maven/apache-maven-3.8.6/bin/mvn`、显式 `/usr/local/maven/repository` 和独立 settings，未依赖用户级 settings。日志 `/private/tmp/iw-apijar-policy-green.log`。
- Web `npm run type-check`、`npm run build`、`npm run check:zhaogang-release-notes`、`npm run test:zhaogang-code-stats-range` 均退出 0。两个仓库 `git diff --check` 通过。
- 回归测试覆盖特殊仓库的旧 OK/PENDING/FAILED/TOO_LARGE/SKIPPED_UNMAPPED 状态、个人/团队/每日/明细与覆盖统计、新扫描及缓存重放不调用 diff；相似名称仓库仍正常统计。
- 后端 HEAD 0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3；Web HEAD d821532cfc9209085e6436093f52df5c9764547f；均含已有与本次未提交修改。
- Java 17.0.9、Maven 3.8.6、Node 18.17.0、npm 9.8.1、Vite 5.4.21；实际 JAR iw-external-0.2.1.jar 时间 2026-11-02 15:19:52 +0800。
- 通过 scripts/local-backend.sh 启动 core PID 84370（18000）、external PID 84577（18006）；Vite 5173 保持运行。必要重启中断了此前仍活动的十一月任务 17，成功数据未删除；十月任务 19 已重新验证完成。

## 本轮文件与数据残留

后端：codestats/CodeLinePolicy.java、CodeScanWorker.java、CodeStatsQueryService.java、DefaultCodeStatsModule.java、CodeStatsModels.java；mapper/ZhaogangCodeCommitMapper.java；对应 CodeStatsQueryServiceTest、CodeScanWorkerBranchCacheTest；初始化/原迁移状态注释及新增专项迁移 SQL。

Web：src/types/zhaogangCodeStats.ts、src/views/zhaogang/code/codeStatsPresentation.ts、ZhaogangCodeMineView.vue。文档：本报告、交接 README、data-and-scan、api-contract。

保留真实用户提交、身份、扫描任务和可复用快照供查看；单测未创建数据库测试行，无合成业务数据待清理。仅修改本功能提交表中目标仓库的统计字段；未删除提交，未操作其他业务表、生产/共享测试库或 CODING 写接口；未提交、推送或发布。全量团队权限等原交接 AC 未因此宣告通过。
