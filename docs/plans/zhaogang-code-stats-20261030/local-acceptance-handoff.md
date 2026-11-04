# 找钢工作台代码统计：本地自动化验收交接

日期：2026-11-02。主仓库：`iw-mixes-server`（端口 18006）。Web：`iw-mixes-web-platform`（Vite 5173）。

设计状态：已确认（2026-10-30）。设计依据为同目录 [README.md](README.md)、[api-contract.md](api-contract.md)、[data-and-scan.md](data-and-scan.md)、[web-pages.md](web-pages.md)、[sql/schema-draft.sql](sql/schema-draft.sql)，本文件不修改任何设计结论。
实施授权：用户在 2026-10-30 会话要求“按本包实施并自验”，代码已按该授权完成；同一会话明确未授权数据库写入、真实成员 PAT 扫描、提交、推送、发布。
代码状态：已实施（2026-10-30，两仓库均未提交）。
验收状态：部分完成——离线单测、Web 类型检查/构建、模拟接口 UI 检查通过；真实 MySQL、真实 CODING 只读扫描、真实权限/并发/冷却链路未执行。记录见 [实施与自验记录](../../acceptance/zhaogang-code-stats-20261030/README.md)。

> 本文件的目的是让新会话在本地把 §6 的 AC-01…AC-16 从“未执行”推进到真实结论。模板名称与本包路径不构成任何确认或执行授权：新会话开始前必须按 §7 取得用户对“建表、启动 `iw-external`、使用本人 PAT 扫描、浏览器验收”的四项本地授权，未获授权的动作保持“未执行/受阻”。

## 1. 确认依据与目标

- 业务目标：在找钢工作台新增“我的代码”和“团队代码”两个页面，用 CODING 代码仓库只读数据统计提交次数与代码行变更，替代人工估算，支持成员横向对比和个人贡献分析。
- 已确认事项（2026-10-30 用户回复，勿在新会话重新询问）：
  - 合并提交（`Parents > 1`）存库但不计数、不取 diff。
  - 按自然月扫描，跨月范围拆分为多个月任务。
  - 未归属身份的提交只计提交次数，不统计行数（`diff_status = SKIPPED_UNMAPPED`）。
  - 当前月 60 分钟增量自动刷新，历史月只在手动触发时全量重扫。
  - 所有团队成员都可手动重扫，但受扫描并发、月份冷却、单用户频率限制。
  - “我的代码”独立扫描本人 PAT 可读的全部仓库，不与团队范围共用任务。
  - 时间范围支持本周、本月、指定月、自定义，自定义最多 31 天且不可选未来。
  - 团队页只保留两张成员为 x 轴的图和“指定成员分析”，不再放仓库排行榜或成员明细表。
  - 回复依据与日期见 [README.md 第 1.2 节](README.md)。
- 待定项及是否阻断实施：无阻断项。唯一待定是 AC-15 保留清理的实际删除观察窗口（见 §5 步骤 S7），不阻断其余验收。
- 影响端与仓库责任：后端 `iw-mixes-server/iw-packaging-parent/iw-external`（扫描、身份、聚合、12 个接口）；Web `iw-mixes-web-platform`（两个页面 + 壳层 + 发布清单）。微信小程序未涉及，本次无需改动。
- 实施范围和明确不做的内容：只读统计，不触发任何构建，不写 CODING 资源，不做缺陷统计，不做跨团队聚合报表；细目见 [README.md 第 1.3 节](README.md)。

## 2. 定稿行为与资料

- 页面示意图（仅表达布局与信息层级，图中数据为模拟内容，不进入产品，也不充当验收证据）：
  - [我的代码页示意图](assets/mine-page.png)
  - [团队代码页示意图](assets/team-page.png)
- 定稿行为：交互细节、空/加载/失败/部分失败状态、时间范围解析、重扫触发与冷却文案见 [README.md 第 2 节](README.md) 与 [web-pages.md](web-pages.md)。
- 可调整的实现建议与已确认行为的区分：§1 列表和 `data-and-scan.md` 的状态机、去重键、限额语义为已确认行为；页面组件拆分、图表配色、SQL 写法为可调整实现，不影响验收结论。

## 3. 当前源码与环境事实

核查时间：2026-11-02。工作区：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai`。

| 仓库/文件 | 当前事实及证据 | 本会话计划动作 |
|---|---|---|
| `iw-mixes-server` | `main`，HEAD `0d6d301`，工作区 20 条未提交变更（`git status --porcelain`） | 只增量修改，不 reset/checkout |
| `iw-mixes-web-platform` | `dev`，HEAD `d821532`，工作区 11 条未提交变更 | 同上 |
| `zhaogang/codestats/`（15 个类 + `entity/CodeStatsEntities.java`） | 已存在，含 `CodeScanService`、`CodeScanWorker`、`CodeStatsQueryService`、`CodeIdentityService`、`CodeStatsViewCache` | 按真实运行结果修正缺陷 |
| `zhaogang/ZhaogangCodeStatsController.java` | 已存在，`@RequestMapping("/external-service/api/zhaogang/code-stats")`，12 个端点 | 联调时核对参数与响应 |
| `iw-external/scripts/iw-external-migrate-20261030-code-stats.sql` | 已存在，4 张 `create table`（无 `if not exists`），文件头声明“本文件未在当前会话执行” | 本地建表（需授权） |
| `iw-external/scripts/iw-external-init.sql` | 已追加同 4 张表，作为新环境基线 | 不重复执行 |
| `Web src/views/zhaogang/code/` | `ZhaogangCodeMineView.vue`、`ZhaogangCodeTeamView.vue`、`codeStatsPresentation.ts`、`useCodeStatsScanPolling.ts` + `components/` 17 个组件 | 浏览器真实数据验收 |
| `Web public/zhaogang-release-notes.json` | 含 `zg-2026-10-30-01 / 1.11.0`，`currentReleaseId` 已指向该记录 | 校验，不新增版本 |

本机运行事实（2026-11-02 核查）：

- `iw-core`：running，PID 69793，端口 18000，script-managed。
- `iw-external`：stopped，端口 18006 未监听（`scripts/local-backend.sh status`）。
- 本地 MySQL `127.0.0.1:3306` 可连接，开发库为 `iw_mixes`；Redis `127.0.0.1:6379`（`database: 0`）可连接。数据源用户名/密码只存在于 gitignore 的 `iw-external/src/main/resources/application-dev.yml`，不得复制到命令回显、文档或日志。
- Vite 5173 未运行。
- 工具版本：Java OpenJDK 17.0.9、Maven 3.8.6（`/usr/local/maven/repository`）、Node v18.17.0 / npm 9.8.1、Vite 5.4.21、`/usr/local/mysql/bin/mysql`、`/opt/homebrew/bin/redis-cli`。新会话应重新记录实际值，不直接引用本表。

`iw_mixes` 是本地共享开发库，不是专用隔离库。执行迁移前必须确认这一点，并只允许新增本功能的 4 张表；任何 `drop`、`truncate` 或对其他表的写操作都要单独获得授权。

## 4. 接口、数据与状态约束

真实路径前缀：`/external-service/api/zhaogang/code-stats`，经 Vite `/external-service/**` → `iw-core:18000` 兼容入口 → `iw-external:18006`。完整请求/响应见 [api-contract.md](api-contract.md)。

| 端点 | 方法 | 关键参数 |
|---|---|---|
| `/options` | GET | 无；返回可选范围与团队 |
| `/statistics` | GET | `from`、`to`、`scope`、`workbenchTeamId?`、`refresh?` |
| `/member-statistics` | GET | `from`、`to`、`workbenchTeamId`、`memberUserId` |
| `/entries` | GET | `from`、`to`、`scope`、`workbenchTeamId?`、`depotId?`、`keyword?`、`pageNumber`、`pageSize`、`refresh?` |
| `/scan` | GET | `from`、`to`、`scope`、`workbenchTeamId?`；返回逐月进度 |
| `/scan` | POST | body `{scope, workbenchTeamId, from, to, mode}`；返回 `accepted/reason/skippedMonths/retryAfterSeconds/scan` |
| `/scan/{jobId}/cancel` | POST | 路径 `jobId` |
| `/scan/{jobId}/failures` | GET | `pageNumber`、`pageSize` |
| `/identities` | GET | `scope`、`workbenchTeamId?`、`keyword?`、分页 |
| `/identities` | POST | body `{scope, workbenchTeamId, identityType, identityKey, codingUserId, ignored, identityValue, authorName}` |
| `/identities/{id}` | PUT | body `{codingUserId, ignored}` |
| `/identities/{id}` | DELETE | 仅手动映射可删 |

未绑定会话时统一返回 `GeneralResponse` 业务码 `401`（`msg=请先绑定 CODING 个人令牌`），HTTP 状态记录实际值；非成员/非管理员越权返回业务码 `403`（`CodeStatsForbiddenException`）。

数据与状态事实（断言依据，表名与列名以迁移脚本为准）：

- 4 张表：`external_zhaogang_code_scan_job`、`external_zhaogang_code_scan_repo`、`external_zhaogang_code_commit`、`external_zhaogang_code_identity`。
- 提交去重键：`uk_scope_depot_sha (coding_team_id, scope_type, scope_id, depot_id, sha)`；多分支同一 SHA 只落一行，分支集合进 `branch_names`/`branch_count`，超上限置 `branch_truncated=1`。
- `job.status ∈ QUEUED/RUNNING/COMPLETED/PARTIAL/FAILED/CANCELLED/INTERRUPTED`，`job.stage ∈ DISCOVERING/IDENTITIES/BRANCHES/COMMITS/DIFFS/AGGREGATING/DONE`，`scan_mode ∈ FULL/INCREMENTAL/DIFF_BACKFILL`；活动任务唯一键 `uk_active_key (active_key)`，终态置空。
- `commit.diff_status ∈ PENDING/OK/SKIPPED_UNMAPPED/SKIPPED_MERGE/FAILED/TOO_LARGE`；`additions/deletions/files_changed` 为 `null` 表示未统计，不得伪造 0。
- `identity.identity_type ∈ EMAIL/NAME`，`source ∈ AUTO/MANUAL/SELF`，`ignored=1` 时 `coding_user_id` 为空；库内只有 `author_email_hash`/`author_name_key`（SHA-256），不存在明文邮箱。
- Redis 键前缀 `zhaogang:code:v1:`，含 `identity-version:`、`scan-version:`、`scan:user:`、`scan:cancel:`、`profile:`、`diff-backfill-pending:`；值为版本号或标记，不含 PAT。
- 限额默认值：扫描并发 2、队列 8、心跳超时 300s、提交/ diff 全局 QPS 25、单 PAT 并发 4、diff 上限 5MB / 30s、单分支最多 100 页、单仓库最多 2000 分支、查询缓存 60s、当前月刷新 60min、当前月冷却 600s、历史月冷却 86400s、失败重试 600s、单用户冷却 300s、数据保留 24 个月、任务保留 6 个月。键名形如 `iw.external.zhaogang.code-historical-cooldown-seconds`。
- 异步与恢复：任务进度写库带 `status in ('QUEUED','RUNNING')` 条件，失去所有权的线程抛 `ScanTaskLostException` 不再覆盖；`@Scheduled(fixedDelay=60_000)` 把心跳超时任务标为 `INTERRUPTED` 并 bump 版本；取消经 Redis 标记、工作线程 10s 内轮询生效；`aggregate` 只在全量、覆盖完整、无失败仓库、无失败 diff、未截断时执行 `cleanupFullScan`。
- 保留清理：`@Scheduled(cron = "0 20 3 * * *")` 依次 `repoMapper.deleteByJobBefore` → `jobMapper.deleteBefore`（任务保留月数）→ `commitMapper.deleteBeforeMonth`（统计保留月数），无手动触发端点。

## 5. 文件级验收执行计划

按顺序执行，每步先看前置断言，失败即停止并按 §7 处理；命令的可见输出（脱敏后）就是证据。

| 步骤 | 仓库及文件/模块 | 动作 | 本步验证（可观察结果） |
|---|---|---|---|
| S0 | 两仓库 + 本机服务 | 只读环境与授权核对 | HEAD/脏文件数、服务状态、端口、工具版本与 §3 一致或差异已记录 |
| S1 | `iw-external/scripts/iw-external-migrate-20261030-code-stats.sql` | 备份后本地建表 | 4 张表存在且 `uk_scope_depot_sha`、`uk_active_key` 存在 |
| S2 | `iw-external` | 启动并确认真实运行版本 | 18006 监听，日志无启动失败，18000 兼容入口可达本功能端点 |
| S3 | Web + 后端 | 绑定本人只读 PAT | `/zhaogang` 已绑定；`/code-stats/options` 返回业务码 200 |
| S4 | `CodeScanService`/`CodeScanWorker` | 小范围真实扫描（单月，`SELF`） | 任务 COMPLETED/PARTIAL，提交行数与 CODING 仓库实际一致 |
| S5 | `CodeStatsQueryService` + 两个页面 | 真实数据页面与聚合对账 | KPI、图表、明细、覆盖提示与 DB/接口一致 |
| S6 | 身份映射、冷却、取消、恢复、权限 | 状态机与安全链路 | AC-07…AC-13 逐条有真实证据 |
| S7 | 保留清理与性能 | 谓词 dry-run + 大范围扫描 | AC-14/AC-15 结论或明确的“受阻”原因 |
| S8 | 全量回归 + 清理 | 后端测试、Web 检查、测试数据清理 | 回归全绿；残留按 §6 记录 |

### S0 环境核对（只读，无需授权）

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server
git log -1 --format='%h %d' && git status --porcelain | wc -l
scripts/local-backend.sh status
nc -z -w1 127.0.0.1 3306 && echo mysql-open
nc -z -w1 127.0.0.1 6379 && echo redis-open
```

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-web-platform
git log -1 --format='%h %d' && git status --porcelain | wc -l
node -v && npm -v
```

只读确认目标表是否已存在（用户名取自 `application-dev.yml`，密码交互输入）：

```bash
/usr/local/mysql/bin/mysql -h127.0.0.1 -P3306 -u '<本地用户名>' -p \
  -e "select table_name from information_schema.tables where table_schema='iw_mixes' and table_name like 'external\_zhaogang\_code\_%' order by table_name;"
```

停止条件：`iw-core` 未运行、3306/6379 不通、已有部分目标表且列结构与 [sql/schema-draft.sql](sql/schema-draft.sql) 不一致。此时不自行 `alter`/`drop`，向用户报告差异。

### S1 本地建表（需 §7 授权）

先备份（只备份本功能可能涉及的表；本步是新增表，不做全库覆盖式恢复）：

```bash
/usr/local/mysql/bin/mysqldump -h127.0.0.1 -P3306 -u '<本地用户名>' -p \
  --routines=false --triggers=false iw_mixes \
  external_zhaogang_code_scan_job external_zhaogang_code_scan_repo \
  external_zhaogang_code_commit external_zhaogang_code_identity \
  > /tmp/iw-mixes-code-stats-backup-$(date +%Y%m%d-%H%M).sql 2>/dev/null || echo "表尚不存在，无需备份"
```

执行迁移：

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server
/usr/local/mysql/bin/mysql -h127.0.0.1 -P3306 -u '<本地用户名>' -p iw_mixes \
  < iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261030-code-stats.sql
```

后置断言：

```bash
/usr/local/mysql/bin/mysql -h127.0.0.1 -P3306 -u '<本地用户名>' -p iw_mixes \
  -e "select table_name, table_rows from information_schema.tables where table_schema='iw_mixes' and table_name like 'external\_zhaogang\_code\_%' order by table_name;
      show index from external_zhaogang_code_commit where Key_name in ('uk_scope_depot_sha');
      show index from external_zhaogang_code_scan_job where Key_name in ('uk_active_key');"
```

期望：4 行表名齐全、两个唯一索引各 5/1 列。注意脚本用 `create table` 而非 `create table if not exists`，重复执行会报 `1050 Table ... already exists`——这是预期保护，不要为了“跑通”改成 `drop table`（会清空已积累的扫描缓存）。执行成功后，把迁移脚本头部的“未在当前会话执行”说明按实际会话更新日期，或在验收记录中注明已执行。

### S2 启动后端（需 §7 授权）

后端启动只能通过统一脚本（脚本内含 `mvn -pl iw-packaging-parent/iw-external -am -DskipTests package`）：

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server
scripts/local-backend.sh start external
scripts/local-backend.sh status
```

确认真实运行版本与端点可达（未登录时应为业务码 401，而不是 404）：

```bash
curl -s -o /dev/null -w 'http=%{http_code}\n' http://127.0.0.1:18006/external-service/api/zhaogang/code-stats/options
curl -s http://127.0.0.1:18000/external-service/api/zhaogang/code-stats/options
```

后置断言：日志无 `APPLICATION FAILED TO START`；第二条 curl 返回 `code=401`，说明 18000 → 18006 兼容入口与 Controller 均注册成功。记录 jar 时间戳和实际启动进程号，作为“本次运行版本”证据。

停止条件：端口被占用、脚本构建失败或日志报 Mapper/DDL 不匹配（如 `Unknown column`）。DDL 不匹配时回到 S1，不修改实体来绕过。

若 `iw-external` 之前由用户手动运行而非脚本托管，`scripts/local-backend.sh status` 会显示非 script-managed；重启前先征得用户同意，不抢占用户进程。

### S3 绑定只读 PAT 并跑通选项接口（需 §7 授权）

在浏览器 `http://127.0.0.1:5173/zhaogang/settings` 用**本人**CODING 个人令牌完成绑定，权限范围按 [README.md](README.md) 与 `context/zhaogang-workbench-technical-reference.md` 的建议（含“代码仓库（只读）”）。

前置硬性要求：令牌仅通过浏览器输入，不出现在 shell 命令、curl、URL、日志、截图、文档或 AC 记录中；不使用他人 PAT、不使用生产管理员 PAT 做实验。

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-web-platform && npm run dev
```

### S4 小范围真实扫描（`SELF`，单个历史月）

先在页面上选择“指定月 → 上一个完整自然月”（2026-11-02 时为 `2026-10`），用“扫描范围”抽屉触发全量重扫；随后在 SQL 侧核对。

前置断言：

```sql
select count(*) as jobs_before from external_zhaogang_code_scan_job;
select count(*) as commits_before from external_zhaogang_code_commit;
```

进度观察（接口或 SQL 均可，记录真实阶段序列）：

```sql
select id, month, scan_mode, status, stage, total_repos, done_repos, total_branches, done_branches,
       total_commits, done_commits, total_diffs, done_diffs, failed_repos, failed_diffs, truncated,
       error_code, active_key, started_at, finished_at, heartbeat_at
  from external_zhaogang_code_scan_job order by id desc limit 5;
```

后置断言（合并提交、未归属、多分支去重、行数状态）：

```sql
select diff_status, is_merge, count(*) as cnt,
       sum(additions is null) as null_additions
  from external_zhaogang_code_commit group by diff_status, is_merge order by cnt desc;

select count(*) as dup_rows, count(distinct concat(depot_id,':',sha)) as distinct_keys
  from external_zhaogang_code_commit;

select count(*) as multi_branch from external_zhaogang_code_commit where branch_count > 1;
select count(*) as truncated_branches from external_zhaogang_code_commit where branch_truncated = 1;
select count(*) as merge_rows from external_zhaogang_code_commit where is_merge = 1;
```

期望（不一致即缺陷，记录后修复并复扫）：
- `is_merge = 1` 的行 `diff_status` 恒为 `SKIPPED_MERGE`，且不计入接口 KPI；接口 `excludedMergeCommitCount` 等于该月合并行数。
- `diff_status='OK'` 的行三项行数均非 `null`；`SKIPPED_UNMAPPED/FAILED/TOO_LARGE/SKIPPED_MERGE` 的行 `additions` 为 `null`，接口不得把它们当 0 参与覆盖率分子。
- `dup_rows = distinct_keys`（去重键无重复）。
- `scan.total_commits` 与 `select count(*) from external_zhaogang_code_commit where month='2026-10'` 口径一致（`SELF` 范围与 `scope_id` 过滤按实际填写）。
- 未绑定身份的行 `author_email_hash` 有值但 `external_zhaogang_code_identity` 无对应映射；响应体与库中均无明文邮箱。

### S5 真实数据页面与聚合对账

`npm run dev` 后在浏览器依次检查（每项都要截图或记录 DOM 断言，不能引用 §4 模拟结论）：

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-web-platform
npm run dev
```

- `http://127.0.0.1:5173/zhaogang/code/mine`：KPI 五个数值、每日提交频率、代码行变更趋势、日历热力图、提交时段分布、仓库提交频率、分支活跃度 Top 10、提交明细分页；点击热力图某天与某仓库后筛选标签生效。
- `http://127.0.0.1:5173/zhaogang/code/team`：团队选择、两张成员 x 轴图（提交次数与代码行变更）、0 提交成员出现在末位、点击成员柱联动“指定成员分析”。
- 交叉对账：接口 `statistics.total.commitCount` 与 S4 SQL 的当月已归属行数、页面 KPI 三者一致；`coverage.diffCoverage` 与 `diff_status='OK' / (OK + FAILED + TOO_LARGE)` 一致。
- 状态检查：加载态、空态（切到无提交月份）、部分失败态（`coverage.memberIssues` 或仓库失败）、扫描中横幅与进度、失败明细抽屉。
- 布局检查：桌面和 390×844 窄屏无横向溢出。

### S6 冷却、取消、重启恢复、身份与权限

```bash
redis-cli --scan --pattern 'zhaogang:code:v1:*' | head -20
```

只允许查看和按精确键名删除本功能键；**禁止** `FLUSHDB`/`FLUSHALL`（`database 0` 与其他业务共享）。

- 冷却：同一历史月立即再次触发应返回 `accepted=false` 与 `skippedMonths`，页面显示冷却文案；Redis `zhaogang:code:v1:scan:user:...` 存在；不同月份不受影响。
- 当前月增量：进入当前月应自动产生 `scan_mode='INCREMENTAL'` 任务，`refresh=true` 才绕过查询缓存。
- 取消：触发大范围扫描后调用 `POST /scan/{jobId}/cancel`（或页面取消按钮），10 秒内 `status` 变为 `CANCELLED`、`active_key` 置空、页面横幅消失；已写入的成功提交保留。
- 中断恢复：扫描运行中 `scripts/local-backend.sh restart external`，重启后应看到 `zhaogang code scan interrupted, jobId=` 日志与 `status='INTERRUPTED'`，随后可重新发起；不得出现同 `active_key` 的两个活动任务。
- 心跳：可临时把 `iw.external.zhaogang.code-scan-heartbeat-timeout-seconds` 调小（走本地配置或环境变量 `IW_EXTERNAL_ZHAOGANG_CODESCANHEARTBEATTIMOUTSECONDS`）验证判定，验证后必须恢复默认 300 并记录两次值。
- 身份：`GET /identities?scope=SELF` 与 `scope=WORKBENCH_TEAM`；用未归属候选做手动映射（`POST /identities`），确认 `source='MANUAL'` 优先于 `AUTO`；映射后应产生 `scan_mode='DIFF_BACKFILL'` 任务，历史 `SKIPPED_UNMAPPED` 行的行数被补齐；删除手动映射仅对手动行生效，`DELETE` 非手动行返回明确错误。
- 权限：普通成员可触发团队重扫；非成员访问该团队范围返回业务码 403；只有团队管理员可写团队映射；个人身份仅本人可改；请求他人 `workbenchTeamId` 不得返回其数据。

### S7 保留清理与性能

保留清理只在 `03:20` cron 触发，无手动端点。默认做法是只读 dry-run，确认谓词与配置口径一致即可，把 AC-15 记为“部分完成（谓词核对）”，并写明未观察到实际删除的原因：

```sql
select month, count(*) from external_zhaogang_code_commit
 where month < date_format(subdate(current_date, interval 24 month), '%Y-%m') group by month;
select date_format(create_time, '%Y-%m') as m, count(*) from external_zhaogang_code_scan_job
 where create_time < subdate(current_date, interval 6 month) group by m;
```

如需实际删除证据，必须另行获得授权：临时把 `code-stats-retention-months` / `code-scan-job-retention-months` 设为 `1` 重启，等待或安排跨过 03:20 后核对行数下降，验证完立即恢复默认值。

性能（AC-14）：用可读仓库较多的大范围（例如覆盖 §3 中提到的 128 仓库量级团队范围）触发扫描，记录 `total_repos`、`total_branches`、`total_commits`、耗时、`failed_diffs`、`truncated`，并在日志或 CODING 侧确认单 PAT 并发 4、全局 QPS 25、diff 5MB 上限生效；不得出现同一 `active_key` 重复任务。

### S8 全量回归与清理

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  /usr/local/maven/apache-maven-3.8.6/bin/mvn \
  -Dmaven.repo.local=/usr/local/maven/repository \
  -pl iw-packaging-parent/iw-external -am test
```

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-web-platform
npm run type-check && npm run build \
  && npm run check:zhaogang-release-notes && npm run test:zhaogang-code-stats-range
```

回归基线：2026-10-30 会话为 `iw-external` 255 tests / 0 failures。若新会话改动了后端，测试数可能增加；结果必须按实际输出填写，不沿用 255。

清理（保留或删库由用户决定，二选一并在 §6 “数据残留”写明）：

```sql
-- 只清测试范围数据，保留表结构
delete from external_zhaogang_code_commit where coding_team_id = <测试CODING团队ID>;
delete from external_zhaogang_code_scan_repo where job_id in
  (select id from external_zhaogang_code_scan_job where coding_team_id = <测试CODING团队ID>);
delete from external_zhaogang_code_scan_job where coding_team_id = <测试CODING团队ID>;
delete from external_zhaogang_code_identity where coding_team_id = <测试CODING团队ID>;
```

```bash
redis-cli --scan --pattern 'zhaogang:code:v1:*' | xargs -r -n 50 redis-cli del
```

## 6. 自验与测试数据

环境、登录态、工具前提：本地 MySQL `iw_mixes` + 本地 Redis `127.0.0.1:6379/0`；`iw-core:18000` 已由脚本托管并保持运行；`iw-external:18006` 由 `scripts/local-backend.sh` 启动；浏览器已用**本人只读 PAT** 绑定 `/zhaogang`；Maven 用 Java 17 + `/usr/local/maven/repository`；Web 用 `npm run dev` + 浏览器原生检查。

允许的测试数据创建/修改/清理范围及标识：只在 4 张 `external_zhaogang_code_*` 表内产生扫描/身份数据；如需用合成行验证保留清理，`identity.author_name` 与提交说明使用 `AT-CODE-20261102-` 前缀，便于识别与删除；CODING 侧只读，不创建/修改/删除任何仓库、分支、提交或项目；不写入 `external_zhaogang_coding_credential` 之外的凭证表。

原有数据恢复、异常停止和残留记录：迁移前按 S1 备份到 `/tmp`；中途停止时保留已生成任务行并在记录里标注 `status`；结束时按 S8 清理并逐表 `select count(*)` 证明为 0 或列出保留原因。

| 编号 | 前置条件 | 操作 | 预期结果 | 验证方式 | 结果与证据 |
|---|---|---|---|---|---|
| AC-01 | 已绑定会话 | 选本周/本月/指定月/自定义 31 天；试 32 天、未来日期、`from>to` | 合法范围解析正确；非法范围被拒绝且提示明确 | 接口 + 浏览器 | 部分完成：真实选择本月/历史月；非法范围未验（见最新仓库修复记录） |
| AC-02 | 范围跨两个月 | 选 2026-09-25~2026-10-10 | 拆成 `2026-09`/`2026-10` 两个任务；结果按真实日期裁剪；跨月提交不重复 | 接口 + 数据库 | 未执行：尚未验证跨月拆分（见最新仓库修复记录） |
| AC-03 | 团队内仓库可见性不同、有成员未绑定 PAT | 团队扫描 | 仓库取可读并集；未绑定成员进 `coverage.memberIssues`；其独有仓库缺失有明确提示 | 真实 PAT + 数据库 | 受阻：未使用第二成员 PAT（见最新仓库修复记录） |
| AC-04 | 存在 0 提交成员、仅 feature 分支的提交 | 团队/个人扫描 | 0 提交成员在图末位；feature 分支提交被统计；同 SHA 多分支只计一次（`branch_count>1` 行数 >0 且无重复键） | 数据库 + 接口 | 部分完成：发现 232 仓库/9565 分支并读取提交；去重及零成员未验（见最新仓库修复记录） |
| AC-05 | 存在合并提交 | 扫描含合并提交的仓库月份 | 合并提交不计数、不取 diff；`excludedMergeCommitCount` 与 `is_merge=1` 行数一致 | 数据库 + 接口 | 未执行：未完成合并提交对账（见最新仓库修复记录） |
| AC-06 | 已归属成员含普通/失败/超大 diff | 扫描后与 CODING 页面样本对账 | `OK` 行新增/删除/文件数与样本一致；`FAILED/TOO_LARGE` 有状态且 `additions` 为 `null`，不伪造 0 | 真实 API + 样本对账 | 未执行：扫描未完成至 diff 对账（见最新仓库修复记录） |
| AC-07 | 历史月已扫描完 | 重复进入历史月；进入当前月；再次手动重扫 | 历史月不自动重扫；当前月自动 `INCREMENTAL`；手动全量受冷却并返回 `skippedMonths`；无同 `active_key` 重复任务 | 接口 + Redis + 数据库 | 部分完成：观察到自动 FULL 与历史月缓存；完整冷却/增量未验（见最新仓库修复记录） |
| AC-08 | 扫描运行中 | 离开再进入、取消、`restart external` | 进度恢复；取消后 `status=CANCELLED` 且 `active_key` 为空；重启后旧任务 `INTERRUPTED` 可重发；部分失败保留成功数据 | 后端 + 浏览器 | 未执行：未验证取消接口及恢复断言（见最新仓库修复记录） |
| AC-09 | 我的代码有数据/无数据 | 打开 `/zhaogang/code/mine` | KPI、六类图表、明细、我的 Git 身份、扫描横幅与空/加载/失败状态全部与真实接口一致 | 浏览器 + 接口 | 部分完成：真实页面进度显示仓库/分支；完整 KPI 图表未验（见最新仓库修复记录） |
| AC-10 | 团队有 0 提交成员 | 打开 `/zhaogang/code/team` | 两张成员 x 轴图含 0 提交成员；点击柱更新指定成员分析；已移除的仓库排行榜/成员明细表不存在 | 浏览器 + 接口 | 未执行：团队图表未验（见最新仓库修复记录） |
| AC-11 | 成员有不同 Git 邮箱/姓名 | 自动匹配、手动映射、忽略、个人绑定、映射后补行数 | 邮箱哈希优先、姓名唯一才自动、手动优先；映射即时生效；`SKIPPED_UNMAPPED` 经 `DIFF_BACKFILL` 补齐行数；响应无明文邮箱 | 接口 + 数据库 | 部分完成：产生自动身份数据；手动映射及补齐未验（见最新仓库修复记录） |
| AC-12 | 普通成员/管理员/非成员 | 触发重扫、打开团队页、修改映射 | 所有成员可重扫；非成员业务码 403；仅管理员可改团队映射；个人身份仅本人可改 | 接口 + 浏览器 | 未执行：多角色权限未验（见最新仓库修复记录） |
| AC-13 | 提交说明含 HTML、请求他人团队 ID | 浏览器渲染 + 直接越权请求 | HTML 按纯文本渲染、无 XSS、外链 `rel="noopener noreferrer"`；跨团队/跨用户数据不可读 | 安全回归 | 未执行：越权/XSS 未验（见最新仓库修复记录） |
| AC-14 | 大范围（百级仓库/分支）月份 | 全量扫描并观察 | 异步完成不超时；单 PAT 并发 4 与全局 QPS 25 生效；`truncated`/`failed_diffs` 语义正确；无重复扫描 | 性能 + 日志 | 部分完成：232 仓库/9565 分支真实扫描；未完整完成及测量限流（见最新仓库修复记录） |
| AC-15 | 超过保留期的数据 | dry-run 谓词；或授权临时调低保留月数 | 超期提交/任务按配置删除、未超期保留、清理可重入；未授权实际删除时记为“部分完成（谓词核对）” | 隔离数据 / 数据库 | 未执行：未验证保留谓词（见最新仓库修复记录） |
| AC-16 | 代码完成 | S8 全量命令 | 后端模块测试通过；Web 类型/构建/清单/范围测试通过 | 命令 | 通过：最终后端 278 项、Web 四项检查通过（见 SHA 缓存实测） |

本次 2026-11-02 执行在 S3 响应码断言不一致处停止，详细证据与数据残留见 [本次验收记录](../../acceptance/zhaogang-code-stats-20261030/local-acceptance-20261102-s0.md)。

只执行与当前授权相关用例；未获得某项授权时保持“未执行/受阻”并写原因。UI 用例必须用真实接口执行实际输入和布局检查。mock、示意图、`/tmp` 临时脚本结果和 2026-10-30 会话结论不得充当本次验收。

## 7. 授权边界与完成条件

新会话开始时一次性向用户确认以下四项，得到肯定后再执行；已默认允许的只读动作无需反复询问：

1. 在本地 `iw_mixes` 执行 S1 建表迁移（新增 4 张表，不改历史表）。
2. 用 `scripts/local-backend.sh` 启动/重启 `iw-external`（可停止该服务，但不得抢占非脚本托管的用户进程）。
3. 使用**用户本人**在浏览器绑定的只读 PAT 触发真实 CODING 扫描。
4. 启动 Vite 并用浏览器做真实绑定态验收。

- 已授权动作及环境：以上四项（若用户确认）+ 读取源码/文档、执行后端测试与 Web 类型/构建、在本功能 4 张表内产生并清理测试数据、按需修正代码缺陷并复验。
- 尚未授权的外部操作：提交、推送、创建 PR、发布生产；UAT/PRD 构建触发；生产或测试环境共享数据库写入；使用他人 PAT 或生产 PAT；`FLUSHDB`/`FLUSHALL`、`drop` 非本功能表、全库恢复式操作；创建或向其他会话发消息。上述动作即使用户为验收授权了本地扫描也不自动放开。
- 必须通过项与允许的限制：S1/S2/S3/S4/S5 与 AC-01、AC-04~AC-13、AC-16 为本次验收必须通过项；AC-03（需第二名成员 PAT）、AC-14（需百级仓库范围）、AC-15（需保留期数据或配置调整）在条件不具备时允许标注“受阻 + 原因 + 复验方式”。
- 验收记录位置与测试数据清理要求：把真实运行版本、脱敏输出、AC 结论和截图写入 [../../acceptance/zhaogang-code-stats-20261030/](../../acceptance/zhaogang-code-stats-20261030/)（新目录或追加小节，不覆盖 2026-10-30 结论），并回写本文件 §6 结果列与 [README.md 第 8 节](README.md) 的状态行；结束前按 S8 清理并在记录中给出逐表计数与 Redis 键计数。

## 8. 新会话启动提示词

> 使用 `$iw-feature-handoff`，读取 `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/plans/zhaogang-code-stats-20261030/local-acceptance-handoff.md`，在本地执行找钢工作台代码统计的自动化验收。先读联合工作区 `AGENTS.md`、`iw-mixes-server/AGENTS.md`、`iw-mixes-web-platform/AGENTS.md` 和交接包 `README.md`，再按本文件 §3 核对当前源码与环境事实；代码已实施且未提交，基于现状增量修改，不 reset/checkout/覆盖已有工作，不重新询问 §1 已确认设计。
> 本条消息即为授权：允许（1）在本地 `iw_mixes` 执行 §5 S1 建表迁移；（2）用 `scripts/local-backend.sh` 启动/重启 `iw-external`；（3）使用我在浏览器绑定的本人只读 CODING PAT 触发真实扫描；（4）启动 Vite 并用浏览器完成真实绑定态验收。仅限本地环境：不得提交、推送、创建 PR、发布生产，不得触发 UAT/PRD 构建，不得写入生产或测试共享库，不得使用他人或生产 PAT，不得 `FLUSHDB`/`FLUSHALL` 或删改本功能 4 张表以外的数据。授权若与上文实际表述冲突，以我当次实际表述为准。
> 按 §5 步骤顺序执行，每步先做前置断言、失败即停止并报告；按 §6 表格逐条填写 AC 真实结论与证据，未执行或受阻的写明原因。PAT、Cookie、token 等凭证不得出现在命令回显、日志、截图或文档中，只记录脱敏结果。改动后端后重跑 `mvn -pl iw-packaging-parent/iw-external -am test`、`npm run type-check`、`npm run build`、`npm run check:zhaogang-release-notes`、`npm run test:zhaogang-code-stats-range`，并记录真实运行版本（HEAD、jar 时间戳、Java/Maven/Node/Vite 实际版本）。mock、示意图和 2026-10-30 旧会话结果不充当本次验收。完成后按 §5 S8 清理测试数据，并给出改动文件、验证结果清单和数据残留说明。

最新状态：用户已取消文档停止条款，仓库解析已修复并真实读取 232 个仓库；整体 AC 部分完成。详情与清理证据见 [仓库发现修复记录](../../acceptance/zhaogang-code-stats-20261030/repository-discovery-fix-20261102.md)。

十月实际扫描修复与性能续验见 [2026-11-02 记录](../../acceptance/zhaogang-code-stats-20261030/scan-resilience-20261102.md)：业务扫描保留运行，完整月度验收尚未完成。

2026-11-02 部分结果续验：局部分支失败继续其余范围，未完成或中断也展示已保存数据；后端 268 项、Web 四项检查通过，真实 10 月页面已显示 98 次本人提交。见 [部分结果验证](../../acceptance/zhaogang-code-stats-20261030/partial-results-20261102.md)。

最新性能与运行验证见 [十月 SHA 缓存实测](../../acceptance/zhaogang-code-stats-20261030/branch-sha-cache-20261102.md)。本次以用户最新要求保留十月真实统计和缓存，测试冷却配置已恢复默认；不是全套 AC 通过声明。
