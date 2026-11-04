# 数据模型、扫描与缓存

> 2026-11-02 最新实施口径：成员 SELF 身份统一供个人/团队使用；PAT 默认姓名及邮箱，支持增删；元数据返回后先按身份过滤，仅持久化命中提交；身份变化按指纹重读历史月。旧的全作者持久化、团队独立映射、邮箱优先回退及仅 DIFF_BACKFILL 补数说明由 [最新身份设计](identity-first-feasibility-20261102.md) 覆盖。实际结果见 [本轮验收](../../acceptance/zhaogang-code-stats-20261030/identity-first-20261102.md)。

上级文档：[README.md](README.md)。完整接口见 [api-contract.md](api-contract.md)。建表草案见 [sql/schema-draft.sql](sql/schema-draft.sql)。

## 1. 数据表

| 表 | 作用 | 关键约束 |
|---|---|---|
| `external_zhaogang_code_scan_job` | 每个 `scope + 月份` 的扫描任务、阶段、进度、冷却、错误 | `active_key` 唯一；完成/失败/取消后置空 |
| `external_zhaogang_code_scan_repo` | 任务内仓库级检查点、分支数、提交数、失败原因 | `(job_id, depot_id)` 唯一 |
| `external_zhaogang_code_commit` | 提交统计缓存：作者、时间、分支集合、新增/删除行、diff 状态 | `(coding_team_id, scope_type, scope_id, depot_id, sha)` 唯一 |
| `external_zhaogang_code_identity` | 作者身份到成员/忽略的映射 | `(coding_team_id, scope_type, scope_id, identity_type, identity_key)` 唯一 |

设计要点：

- 不存明文邮箱；只存 `author_email_hash`、`author_name_key` 和作者名展示快照。
- `branch_names` 使用 JSON 文本，最多保留 50 个分支名；超过时 `branch_truncated=1`，`branch_count` 保存实际数量。
- `additions/deletions/files_changed` 允许 `NULL`，表示未统计；不能用 0 代替失败或未归属。
- `is_merge=1` 的提交保留用于审计和 `excludedMergeCommitCount`，但统计查询排除。
- `last_seen_scan_id` 用于全量扫描成功后清理已从分支历史消失的提交；增量扫描不清理。
- `month` 由 `commit_date` 推导，用于按月索引和保留清理。
- 所有查询按 `coding_team_id + scope_type + scope_id` 隔离；`scope_id` 对 SELF 是 CODING 用户 ID，对 TEAM 是工作台团队 ID。

## 2. 扫描任务状态机

### 2.1 状态

```
QUEUED ──► RUNNING ──► COMPLETED
                  ├──► PARTIAL
                  ├──► FAILED
                  ├──► CANCELLED
                  └──► INTERRUPTED
QUEUED ──► CANCELLED
RUNNING ──► INTERRUPTED  (heartbeat timeout / process restart)
```

- `COMPLETED`：所有仓库、分支、提交、diff 处理完成，无失败、无截断。
- `PARTIAL`：有仓库失败、diff 失败/超大、分支/提交截断，但仍返回已成功数据。
- `FAILED`：任务级失败（例如全部成员凭证不可用、数据库写入失败）。
- `INTERRUPTED`：心跳超过 5 分钟未更新，或进程重启；可由恢复任务标记。
- `CANCELLED`：用户/管理员取消。
- 活动任务由 `active_key = <scopeType>:<scopeId>:<YYYY-MM>` 唯一约束保证；同一 scope+月份最多一个 `QUEUED/RUNNING` 任务。

### 2.2 阶段

| 阶段 | 工作 |
|---|---|
| `DISCOVERING` | 发现成员可读仓库；建立仓库到成员令牌候选的映射 |
| `IDENTITIES` | 拉取成员 CODING profile，生成自动邮箱/唯一姓名映射 |
| `BRANCHES` | 读取每个仓库全部分支 |
| `COMMITS` | 每个分支按日期分页拉取提交；去重；记录分支集合 |
| `DIFFS` | 对已归属成员提交逐条拉取 diff，汇总新增/删除/文件数 |
| `AGGREGATING` | 更新任务状态、清理过期提交（仅成功全量）、失效缓存 |
| `DONE` | 终态展示 |

进度字段在 `scan_job` 中按仓库/分支/提交/diff 分别记录；前端用 `GET /scan` 轮询。

## 3. 扫描算法

### 3.1 范围解析

```text
SELF:
  scopeId = session.userId
  仓库范围 = 本人 PAT 的 DescribeMyDepots 全量分页
  token 候选 = [本人 token]

WORKBENCH_TEAM:
  scopeId = workbenchTeamId
  成员范围 = 工作台团队成员表当前成员
  仓库范围 = 每个有凭证成员 DescribeMyDepots 的并集（按 DepotId 去重）
  token 候选 = 能发现该仓库的成员 token 列表
```

- 团队范围不是 CODING 团队全员；是工作台团队成员。
- 某个仓库所有成员都无读权限时无法统计，进入 `repoIssues`。
- 成员没有 PAT 时，其独有仓库可能缺失，进入 `memberIssues`；其提交仍可能通过其他成员可见仓库被统计。
- `DescribeMyDepots` 实测覆盖不足时，回退到 `DescribeUserProjects(UserId)` + `DescribeProjectDepotInfoList(ProjectId)`；回退逻辑需在实施时用真实 PAT 确认后决定。

### 3.2 发现仓库

1. 对每个 token 调 `DescribeMyDepots(PageNumber, PageSize=100)`，完整分页。
2. 只保留 `VcsType=git`/`RepoType=git` 的 CODING 项目仓库；外部镜像仓库首版跳过并记录原因。
3. 按 `DepotId` 去重；保留 `ProjectId/ProjectName/DepotName/WebUrl`。
4. 写入 `scan_repo`，状态 `PENDING`；记录仓库总数。

### 3.3 发现分支

1. 对每个仓库选择一个可用 token，调 `DescribeProjectDepotBranches(ProjectId, Id=DepotId, DepotType=CODING)`。
2. 权限失败时换下一个候选 token；全部失败标记仓库 `FAILED`。
3. 分支名保存在任务内存；`scan_repo.branch_count` 更新。
4. 分支数超过 `code-max-branches-per-repo`（默认 2000）时截断并标记 `truncated`。

### 3.4 拉取提交

对每个仓库、每个分支：

1. 调 `DescribeGitCommitsInPage(DepotId, Ref=branch, StartDate, EndDate, PageNumber, PageSize=100)`。
2. `StartDate/EndDate` 使用所选自然月；前端自定义范围跨月时，按自然月任务分别拉取。
3. 完整分页；单分支页数超过 `code-max-pages-per-branch`（默认 100，约 10000 条）时截断并标记。
4. 解析字段：
   - `Sha`、`AuthorName`、`AuthorEmail`、`CommitDate/CreatedAt`、`ShortMessage/FullMessage`、`Parents`。
   - `isMerge = Parents.size() > 1`。
   - `commit_at` 使用 `CommitDate`，缺失回退 `CreatedAt`，转 `Asia/Shanghai`。
   - `message` 取 `ShortMessage` 第一行，截断到 1024 字符。
   - `author_email_hash = sha256(lower(trim(email)))`；`author_name_key = sha256(normalize(name))`。
5. 按 `(depotId, sha)` 去重；同一提交在多个分支出现时合并分支集合，最多 50 个，超过标记 `branch_truncated`。
6. 所有提交（含合并、未归属）都 upsert；新增行 `diff_status=PENDING`，合并行 `SKIPPED_MERGE`。
7. 已存在的行使用 `ON DUPLICATE KEY UPDATE` 更新作者/时间/消息/分支/`last_seen_scan_id`；已 `OK` 的 diff 结果保留，不重复拉取。
8. 批量 upsert，默认 200 行/批；每处理完若干页更新一次进度和心跳。

### 3.5 身份映射

自动映射在 `IDENTITIES` 阶段完成：

- 对每个有凭证成员调 `DescribeCodingCurrentUser`，取 `Email/Name/Id`。
- 计算邮箱哈希，upsert `identity_type=EMAIL`、`coding_user_id=member`、`source=AUTO`。
- 不覆盖 `source=MANUAL/SELF` 的行；已有 AUTO 行指向另一成员时跳过并计入身份冲突。
- TEAM 的姓名自动映射仅当归一化姓名在团队成员中唯一时创建；SELF 不自动按姓名匹配，避免同名误归属。
- 成员 profile 按 token 指纹缓存 12 小时，只缓存 emailHash/name/userId，不缓存明文邮箱。

手动映射：

- TEAM：管理员在 `GET /identities` 候选中选择身份，绑定成员或忽略。
- SELF：本人输入邮箱/姓名，服务端哈希后保存为 `source=SELF`，可预览匹配提交数。

解析优先级：

1. 邮箱映射存在：使用其成员；若 `ignored=1` 则视为忽略，不回退姓名。
2. 否则姓名映射存在：使用其成员；`ignored=1` 视为忽略。
3. 否则未归属。

映射在查询时关联，不需要重扫。

### 3.6 拉取行数

只处理满足以下条件的提交：

- `is_merge=0`
- `diff_status IN (PENDING, FAILED)`（`OK` 不重复拉取）
- 通过身份映射可归属到当前 scope 的成员
- 不属于已忽略身份

进入 `DIFFS` 前先执行一次归属刷新：把本 `scope + month` 中 `diff_status=SKIPPED_UNMAPPED` 且当前已能解析到成员的提交改为 `PENDING`。这样自动/手动映射新增后，同一任务或后续 `DIFF_BACKFILL` 能补上历史行数，而不需要重新拉取提交。

对每条提交调 `DescribeGitCommitDiff(DepotId, Sha)`：

- 解析 `Diffs[]`，汇总 `Insertions`、`Deletions`，`filesChanged = Diffs.size()`。
- 成功：`diff_status=OK`。
- 未归属：`diff_status=SKIPPED_UNMAPPED`，不调用 diff。
- 合并：`diff_status=SKIPPED_MERGE`，不调用 diff。
- 特殊仓库 `online.base.apijar.service`：非合并提交使用 `SKIPPED_REPOSITORY`，仅统计提交次数，不调用 diff；增删行数及文件数为 NULL，不计入行数覆盖分母或 diff 失败。查询、写入和旧快照重放均应用该规则，优先于未归属跳过状态。
- 失败：`diff_status=FAILED`，行数为 `NULL`。
- 响应超过 `code-diff-max-bytes`（默认 5 MB）：`diff_status=TOO_LARGE`，行数为 `NULL`。
- 权限失败：换候选 token；全部失败则 `FAILED`。
- 批量更新，默认 100 条/批；每批更新进度和心跳。

### 3.7 全量清理

全量扫描（`mode=FULL`）成功且无仓库失败、无截断时：

```sql
delete from external_zhaogang_code_commit
 where coding_team_id = ?
   and scope_type = ?
   and scope_id = ?
   and month = ?
   and last_seen_scan_id <> ?   -- 当前 jobId
```

用途：移除 force-push/rebase 后已不在任何分支历史中的提交。存在仓库失败或截断时跳过清理，避免误删。

### 3.8 增量刷新

当前月自动刷新使用 `INCREMENTAL`：

1. 重新发现仓库和分支（捕捉新仓库/新分支）。
2. 对已知分支从 `lastSuccessfulAt - 2 天` 开始拉取到当前；新分支从月初开始。
3. upsert 提交和分支集合；已 `OK` 的 diff 不重复拉取。
4. 只对新增/失败 diff 进入 `DIFFS`。
5. 不执行全量清理；force-push 造成的旧提交需手动全量重扫修正。

### 3.9 恢复、取消、清理

- 心跳：工作线程每 30 秒更新 `heartbeat_at`。
- 恢复任务：`@Scheduled(fixedDelay = 60_000)`，将 `RUNNING/QUEUED` 且心跳超过 5 分钟的任务标记 `INTERRUPTED`，清空 `active_key`。
- 取消：`POST /scan/{jobId}/cancel` 写 Redis 取消标记；工作线程在分页/仓库边界检查。
- 重启：未完成任务保持 `RUNNING`，由恢复任务标记 `INTERRUPTED`；重试幂等。
- 清理：每天一次，按 `code-stats-retention-months` 删除超期提交，按 `code-scan-job-retention-months` 删除旧任务/检查点；批量删除，避免长事务。

### 3.10 映射变更后的行数补齐

身份映射在查询时生效，所以新增/修改映射后提交次数立即变化；但历史上 `SKIPPED_UNMAPPED` 的提交没有行数。需要补齐：

1. 新增/修改映射（把身份归属到成员）成功后，按 `identity_type + identity_key` 找出该 scope 下受影响的提交月份。
2. 将这些提交的 `diff_status` 从 `SKIPPED_UNMAPPED` 改回 `PENDING`；忽略/删除映射不需要补行数。
3. 入队 `DIFF_BACKFILL` 任务（`scan_mode=DIFF_BACKFILL`），按 `scope + 月份` 创建；工作线程直接进入 `DIFFS` 阶段，只处理现在已归属且 `diff_status=PENDING/FAILED` 的提交。
4. 若同 scope+月份已有任何活动任务（`FULL/INCREMENTAL/DIFF_BACKFILL`），不重复创建；标记 `PENDING` 并写入 Redis 待补齐标记 `zhaogang:code:diff-backfill-pending:<scope>:<scopeId>:<month>`；活动任务结束后检查该标记并入队 `DIFF_BACKFILL`，避免历史月没有后续增量任务时行数一直缺失。
5. `DIFF_BACKFILL` 使用同一 active_key 和队列；完成/失败后清空 `active_key`。它不重新发现仓库/分支，不重扫历史提交。
6. 前端在映射保存后显示“行数统计补齐中”；轮询 `GET /scan`，完成后刷新统计。

`scan_mode` 取值：`FULL`、`INCREMENTAL`、`DIFF_BACKFILL`。`POST /scan` 手动触发只使用 `FULL`；另外两种由服务端内部触发。

## 4. 缓存与版本

### 4.1 MySQL 持久缓存

- 扫描完成的提交和行数落 `code_commit`；重复进入页面不再请求 CODING。
- `statistics`/`member-statistics`/`entries` 直接读 MySQL。
- 历史月完成后不自动重扫；手动全量重扫幂等 upsert。

### 4.2 Redis 查询缓存

| key | TTL | 说明 |
|---|---|---|
| `zhaogang:code:v1:statistics:<scope>:<scopeId>:<from>:<to>:<identityVersion>:<scanVersion>` | 60s | 团队/个人统计 |
| `zhaogang:code:v1:member:<scope>:<scopeId>:<memberId>:<from>:<to>:<version>` | 60s | 指定成员时间序列 |
| `zhaogang:code:v1:entries:<scope>:<scopeId>:<from>:<to>:<depotId>:<keyword>:<page>:<size>:<version>` | 60s | 提交明细 |
| `zhaogang:code:v1:identities:<scope>:<scopeId>` | 30s | 身份映射列表 |
| `zhaogang:code:profile:<tokenFingerprint>` | 12h | 成员 profile 的 emailHash/name/userId |
| `zhaogang:code:scan:cancel:<jobId>` | 1h | 取消标记 |
| `zhaogang:code:scan:user:<codingTeamId>:<scope>:<scopeId>:<month>` | 300s | 单用户手动重扫频率 |
| `zhaogang:code:identity-version:<scope>:<scopeId>` | 无 | 映射变更时递增 |
| `zhaogang:code:scan-version:<scope>:<scopeId>:<month>` | 无 | 扫描完成/取消时递增 |

- 不把 PAT 写入 key；profile key 使用 SHA-256 指纹。
- 扫描完成、取消、映射变更时递增版本，不依赖 Redis pattern delete。
- Redis 不可用时降级为直接查 MySQL，不影响主链路；缓存写入失败只记录轻量日志，不含 token/邮箱。

## 5. 时间范围与跨月

- 后端只接收 `from/to`；前端负责本周/本月快捷计算。
- 校验：`from <= to`、`to <= today`、`days <= 31`。
- `months = from..to` 覆盖的每个 `YearMonth`。
- 查询时逐月检查：
  - 无扫描记录：触发 `FULL` 任务并返回扫描状态；若最近一次任务 `FAILED/INTERRUPTED/CANCELLED` 未满 10 分钟，不重复触发，返回 `retryAfterSeconds`。
  - 有 `COMPLETED/PARTIAL`：读取数据。
  - 当前月且上次成功超过 60 分钟：服务端自动入队 `INCREMENTAL`，返回旧数据 + `refreshing=true`。
- 所有月份都有至少一次完成扫描后，SQL 按 `commit_date between from and to` 聚合；提交去重键跨月仍唯一。
- 范围跨月时，`repoCount/branchCount` 按仓库/分支去重后合并。

## 6. 扫描触发与冷却

### 6.1 自动增量

`GET /statistics` 中执行，不阻塞请求：

1. 当前月有完成数据且 `now - lastSuccessfulAt >= 60min`。
2. 没有活动任务。
3. 入队 `INCREMENTAL`，`requested_mode=AUTO`，`requested_by=当前查看者`。
4. 返回旧数据 + `refreshing=true`；前端轮询 `GET /scan`，完成后重新取统计。

### 6.2 手动全量

`POST /scan`：

1. 校验成员身份和范围。
2. 按月检查 `active_key`、团队月冷却、单用户冷却。
3. 创建 `FULL` 任务并提交到有界队列。
4. 队列满返回 `QUEUE_FULL`；已创建但未能入队的任务标记 `FAILED` 并清空 `active_key`，避免留下永远排队的记录。
5. 所有成员可触发；个人仅本人。

冷却：

- 成功：当前月 10 分钟；历史月 24 小时。
- 失败/部分/中断/取消：10 分钟后可重试。
- 单用户 5 分钟一次。
- 冷却按 `scope + month` 共享，不按用户拆分。

## 7. 配置建议

| 配置 | 默认值 | 说明 |
|---|---|---|
| `code-scan-concurrency` | 2 | 进程内扫描线程数 |
| `code-scan-queue-capacity` | 8 | 待执行任务上限 |
| `code-scan-heartbeat-timeout-seconds` | 300 | 心跳超时 |
| `code-commit-page-size` | 100 | 提交分页，实施时以真实上限为准 |
| `code-max-pages-per-branch` | 100 | 单分支单月最大页数 |
| `code-max-branches-per-repo` | 2000 | 单仓库分支保护上限 |
| `code-diff-max-bytes` | 5 MB | 单次 diff 响应上限 |
| `code-diff-timeout-ms` | 30000 | diff 请求超时 |
| `code-commit-qps` | 25 | 提交 Action 全局 QPS |
| `code-diff-qps` | 25 | diff Action 全局 QPS |
| `code-scan-cache-seconds` | 60 | 查询缓存 |
| `code-profile-cache-hours` | 12 | 成员 profile 缓存 |
| `code-stats-retention-months` | 24 | 提交缓存保留月数 |
| `code-scan-job-retention-months` | 6 | 任务/检查点保留月数 |
| `code-current-month-refresh-minutes` | 60 | 当前月自动增量间隔 |
| `code-current-month-cooldown-seconds` | 600 | 当前月手动重扫冷却 |
| `code-historical-cooldown-seconds` | 86400 | 历史月手动重扫冷却 |
| `code-failed-retry-seconds` | 600 | 失败重试冷却 |
| `code-user-cooldown-seconds` | 300 | 单用户触发频率 |

配置前缀统一 `iw.external.zhaogang`。

## 8. 权限与安全

- 成员 PAT 备份用途从“仅团队工时”扩展为“团队工时 + 团队代码统计”，已获用户确认；不增加入队提示。
- PAT 只在服务端内存/凭证表；不返回前端、不写日志、不写入 Redis、不作为缓存 key。
- 团队接口先校验当前用户是团队成员；身份写操作校验管理员；个人范围 scopeId 由会话推导。
- 不返回明文邮箱；身份 key 使用 SHA-256；响应只给作者名和哈希 key。
- 提交说明纯文本渲染；禁止 `v-html`。
- `commitUrl` 只接受服务端返回的 `http/https` WebUrl；前端新窗口打开加 `noopener noreferrer`。
- 只调用 CODING 读接口；不写仓库、分支、提交、事项或构建。
- 日志只记录任务 ID、范围、计数、错误码；不记录提交说明、邮箱、PAT、原始响应。
- 失败原因统一脱敏：`TOKEN_MISSING`、`PERMISSION_DENIED`、`REQUEST_FAILED`、`RESPONSE_TOO_LARGE`、`TRUNCATED`、`CODING_ERROR`。

## 9. 已知限制

- 只统计 CODING 项目 Git 仓库；外部镜像/非 Git 仓库首版跳过。
- 同一提交出现在多个分支只计一次提交和一次行数；分支活跃度按提交出现的分支分别计数。
- Cherry-pick 产生新 SHA，会重复计提交和行数；revert 也会计入。
- 代码行数受格式化、生成代码、锁文件影响；页面必须提示“代码量不等于工作量”。
- 全量扫描成功才清理 force-push 后消失的提交；增量刷新不清理。
- 未归属提交不计行数；如需统计，需扩展 diff 范围和成本预算。
- 成员退出团队后，其历史提交按未归属处理；身份映射保留供管理员重新绑定。
- 成员没有 PAT 时，其独有仓库可能缺失；coverage 必须明确提示。

## 2026-11-02 实施补充：分支/月提交缓存

本人/团队扫描按范围隔离仓库 head SHA 的完整自然月结果；同仓库同 head 分支合并读取，头未变且存在有效窗口快照时复用。变化 head 重读该月完整窗口，无 SHA、缓存缺失或损坏均回退读取。新快照经分支头复核后发布，失败/截断不缓存；重放后重建当前分支归属并更新 last_seen_scan_id。Redis branch-month:v1 缓存 TTL 7 天，单条 JSON 上限 1 MiB，仅含规范化提交与邮箱哈希，不含 PAT/原始邮箱字段。当前月还按实际查询截止日隔离，避免复用不同日期窗口。无需第五张表或 DDL。详见 [实际验证](../../acceptance/zhaogang-code-stats-20261030/branch-sha-cache-20261102.md)。
