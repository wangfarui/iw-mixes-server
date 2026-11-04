# 代码统计接口契约

> 2026-11-02 最新实施口径：成员 SELF 身份统一供个人/团队使用；PAT 默认姓名及邮箱，支持增删；元数据返回后先按身份过滤，仅持久化命中提交；身份变化按指纹重读历史月。旧的全作者持久化、团队独立映射、邮箱优先回退及仅 DIFF_BACKFILL 补数说明由 [最新身份设计](identity-first-feasibility-20261102.md) 覆盖。实际结果见 [本轮验收](../../acceptance/zhaogang-code-stats-20261030/identity-first-20261102.md)。

上级文档：[README.md](README.md)。所有路径均为完整外部入口路径。

统一前缀：`/external-service/api/zhaogang/code-stats`。

## 1. 通用约定

- 认证：复用工作台加密 HttpOnly Cookie；Web 使用 `fetch` + `credentials: 'include'`，不新增 Authorization 头。
- 响应：`{ "code": 200, "message": "success", "data": ... }`。
- 日期：`YYYY-MM-DD`；时间：ISO-8601 带 `+08:00`；统计时区固定 `Asia/Shanghai`。
- `scope`：`SELF` / `WORKBENCH_TEAM`。
- `workbenchTeamId`：`WORKBENCH_TEAM` 必填；后端校验当前用户是团队成员。
- 身份：SELF 的 `scopeId` 始终由会话 `userId` 推导，浏览器不得提交他人 ID；TEAM 的 `scopeId` 是工作台团队 ID。
- `from/to`：含首尾；`from <= to`；最多 31 个自然日；`to` 不得晚于今天；不得早于最近 24 个自然月。
- 所有金额/行数使用整数；`additions/deletions` 未知时为 `null`，不得用 0 伪装。
- 权限错误沿用 `CODING_PERMISSION_DENIED`、`missingPermissions`、`action`、`codingErrorCode`。

## 2. 端点总览

| 方法 | 路径 | 用途 | 权限 |
|---|---|---|---|
| GET | `/options` | 团队列表、管理员标记、重扫策略、个人身份数 | 已登录会话 |
| GET | `/statistics` | 个人/团队统计、图表数据、coverage、scan | 本人/团队成员 |
| GET | `/member-statistics` | 指定团队成员的时间序列 | 团队成员 |
| GET | `/entries` | 我的提交明细分页 | 本人 |
| GET | `/scan` | 查询范围内容月的扫描状态和进度 | 本人/团队成员 |
| POST | `/scan` | 手动全量重扫（所有成员可触发） | 本人/团队成员 |
| POST | `/scan/{jobId}/cancel` | 取消扫描 | 发起人/团队管理员 |
| GET | `/scan/{jobId}/failures` | 扫描失败仓库/diff 列表 | 本人/团队成员 |
| GET | `/identities` | 身份映射与未归属候选 | 本人/团队成员只读 |
| POST | `/identities` | 新增身份映射/忽略 | 本人/团队管理员 |
| PUT | `/identities/{id}` | 修改映射/忽略 | 本人/团队管理员 |
| DELETE | `/identities/{id}` | 删除手动身份映射 | 本人/团队管理员 |

## 3. GET /options

响应：

```json
{
  "teams": [
    { "id": 1, "name": "找钢后端组", "memberCount": 9, "administrator": true }
  ],
  "rescanPolicy": {
    "currentMonthCooldownSeconds": 600,
    "historicalMonthCooldownSeconds": 86400,
    "failedRetrySeconds": 600,
    "perUserCooldownSeconds": 300
  },
  "myIdentityCount": 2,
  "serverTime": "2026-10-30T18:00:00+08:00"
}
```

- `administrator`：当前用户是否为该团队管理员。
- `myIdentityCount`：SELF 范围已绑定的本人 Git 身份数量。
- `options` 不触发扫描。

## 4. GET /statistics

查询参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `from` | 是 | 开始日期 |
| `to` | 是 | 结束日期 |
| `scope` | 是 | `SELF` / `WORKBENCH_TEAM` |
| `workbenchTeamId` | TEAM 必填 | 工作台团队 ID |
| `refresh` | 否 | 只绕过 Redis 查询缓存，不触发重扫 |

### 4.1 团队响应

```json
{
  "dataReady": true,
  "refreshing": false,
  "range": {
    "from": "2026-10-01",
    "to": "2026-10-30",
    "days": 30,
    "months": ["2026-10"]
  },
  "scan": {
    "scope": "WORKBENCH_TEAM",
    "workbenchTeamId": 1,
    "aggregateStatus": "COMPLETED",
    "months": [
      {
        "month": "2026-10",
        "jobId": 123,
        "mode": "FULL",
        "status": "COMPLETED",
        "stage": "DONE",
        "progress": {
          "reposDone": 128, "reposTotal": 128,
          "branchesDone": 642, "branchesTotal": 642,
          "commitsDone": 1286, "commitsTotal": 1286,
          "diffsDone": 1240, "diffsTotal": 1240
        },
        "startedAt": "2026-10-30T10:25:00+08:00",
        "finishedAt": "2026-10-30T10:32:00+08:00",
        "lastSuccessfulAt": "2026-10-30T10:32:00+08:00",
        "cooldownUntil": "2026-10-30T10:42:00+08:00",
        "retryAfterSeconds": 0,
        "error": null
      }
    ],
    "retryAfterSeconds": 0
  },
  "coverage": {
    "scope": "WORKBENCH_TEAM",
    "workbenchTeamId": 1,
    "memberCount": 9,
    "patMemberCount": 8,
    "repoCount": 128,
    "branchCount": 642,
    "partial": false,
    "truncated": false,
    "identityConflictCount": 0,
    "failedDiffCount": 18,
    "diffCoverage": 0.982,
    "excludedMergeCommitCount": 312,
    "memberIssues": [
      {
        "user": { "id": 9, "name": "吴语晨", "avatar": "" },
        "reasonCode": "TOKEN_MISSING",
        "reason": "尚未绑定 CODING 令牌，其独有仓库可能未覆盖"
      }
    ],
    "repoIssues": []
  },
  "summary": {
    "commitCount": 1286,
    "additions": 48920,
    "deletions": 17340,
    "netLines": 31580,
    "activeMembers": 8,
    "memberCount": 9,
    "unmappedCommitCount": 45
  },
  "members": [
    {
      "user": { "id": 1, "name": "张明远", "avatar": "https://..." },
      "commitCount": 245,
      "additions": 12450,
      "deletions": 4102,
      "netLines": 8348,
      "activeDays": 26,
      "repoCount": 12,
      "branchCount": 38,
      "lastCommitAt": "2026-10-30T15:42:31+08:00"
    }
  ]
}
```

团队口径：

- `members` 固定包含当前工作台团队全部成员，按 `commitCount` 降序、姓名升序；0 提交成员保留在末尾，字段为 0。
- `members[].additions/deletions/netLines` 只汇总 `diff_status=OK` 的提交；`coverage.diffCoverage` 表示行数完整度，不能把失败行数当作 0 静默处理。
- `summary.commitCount/additions/deletions/netLines` 只汇总当前团队成员已归属的提交；新增/删除行只包含 `diff_status=OK` 的提交。
- `unmappedCommitCount` 为未映射和映射到非当前成员的提交数量，不进入成员合计。
- `activeMembers` 为 `commitCount > 0` 的成员数；`memberCount` 为当前团队成员总数。
- `repoCount/branchCount` 为本范围内去重后的扫描仓库/分支数，跨月合并去重。
- `dataReady=false` 时，`summary/members` 为空，`scan` 给出缺失月份的进度；前端应展示扫描进度。
- `refreshing=true` 时，返回上一次成功数据，当前月增量扫描或 `DIFF_BACKFILL` 在后台进行；行数覆盖率可能暂时偏低。

### 4.2 个人响应

```json
{
  "dataReady": true,
  "refreshing": false,
  "range": {
    "from": "2026-10-01",
    "to": "2026-10-30",
    "days": 30,
    "months": ["2026-10"]
  },
  "scan": { "...": "同团队结构，scope=SELF" },
  "coverage": {
    "scope": "SELF",
    "memberCount": 1,
    "patMemberCount": 1,
    "repoCount": 12,
    "branchCount": 38,
    "partial": false,
    "truncated": false,
    "identityConflictCount": 0,
    "unmatchedCommitCount": 6,
    "failedDiffCount": 2,
    "diffCoverage": 0.995,
    "excludedMergeCommitCount": 20,
    "memberIssues": [],
    "repoIssues": []
  },
  "summary": {
    "commitCount": 486,
    "additions": 18420,
    "deletions": 6780,
    "netLines": 11640,
    "activeDays": 18,
    "repoCount": 12,
    "branchCount": 38
  },
  "daily": [
    {
      "date": "2026-10-01",
      "commitCount": 12,
      "additions": 320,
      "deletions": 80,
      "netLines": 240,
      "dayType": "WORKDAY"
    }
  ],
  "hourly": [
    { "hour": 0, "commitCount": 0 },
    { "hour": 9, "commitCount": 42 },
    { "hour": 23, "commitCount": 3 }
  ],
  "repositories": [
    {
      "projectName": "iw-mixes",
      "projectDisplayName": "IW 系统",
      "depotName": "inventory-service",
      "commitCount": 128,
      "additions": 4180,
      "deletions": 1340,
      "branchCount": 12,
      "lastCommitAt": "2026-10-30T15:42:31+08:00"
    }
  ],
  "branches": [
    {
      "depotName": "inventory-service",
      "branchName": "feature/order-optimization",
      "commitCount": 24,
      "lastCommitAt": "2026-10-30T15:42:31+08:00"
    }
  ]
}
```

个人口径：

- `daily` 按自然日返回范围内每一天；无提交日期补 0；`dayType` 来自工作日历。
- `hourly` 固定 24 项，按 `commit_at` 的 `Asia/Shanghai` 小时汇总当前范围内本人已归属提交；用于“提交时段分布”。
- `repositories` 只统计本人已归属提交，按提交次数降序。
- `repositories[].lineStatsExcluded=true` 表示仓库仅统计提交次数，前端显示“仅统计提交次数”。当前精确匹配 `online.base.apijar.service`；其非合并明细 `diffStatus=SKIPPED_REPOSITORY`、行数/文件数为 null，页面显示“—”，不计入行数完整度分母或 diff 失败数。
- `branches` 只统计本人已归属提交出现过的分支；同一提交在多个分支出现时按分支分别计数，用于分支活跃度图。
- `coverage.unmatchedCommitCount` 为个人扫描中发现但未匹配到本人身份的提交数，用于提示绑定 Git 身份。
- 个人不返回 `members`；团队不返回 `daily/repositories/branches`。

## 5. GET /member-statistics

查询参数：`from`、`to`、`workbenchTeamId`、`memberUserId`。

响应：

```json
{
  "member": { "id": 1, "name": "张明远", "avatar": "https://..." },
  "summary": {
    "commitCount": 245,
    "additions": 12450,
    "deletions": 4102,
    "netLines": 8348,
    "activeDays": 26,
    "repoCount": 12,
    "branchCount": 38
  },
  "daily": [
    {
      "date": "2026-10-01",
      "commitCount": 3,
      "additions": 120,
      "deletions": 30,
      "netLines": 90
    }
  ]
}
```

- 仅 `WORKBENCH_TEAM`；`memberUserId` 必须是当前团队成员。
- 只返回指定成员的日序列，用于“指定成员分析”，不重复返回团队汇总。
- `memberUserId` 不是当前成员时返回 403/业务错误。

## 6. GET /entries

仅用于“我的代码”提交明细；团队页不提供提交明细。

查询参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `from` / `to` | 是 | 时间范围，最多 31 天 |
| `depotId` | 否 | 仓库筛选 |
| `keyword` | 否 | 提交说明关键词，服务端 SQL `LIKE`，最多 100 字符 |
| `pageNumber` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20，最大 100 |
| `refresh` | 否 | 绕过 Redis 查询缓存 |

响应：

```json
{
  "range": { "from": "2026-10-01", "to": "2026-10-30", "days": 30, "months": ["2026-10"] },
  "page": { "pageNumber": 1, "pageSize": 20, "totalRow": 486, "totalPage": 25 },
  "items": [
    {
      "sha": "a1b2c3d4e5f6...",
      "shortSha": "a1b2c3d",
      "commitAt": "2026-10-30T18:42:11+08:00",
      "projectName": "iw-mixes",
      "projectDisplayName": "IW 系统",
      "depotName": "inventory-service",
      "branches": ["feature/order-optimization", "main"],
      "branchCount": 2,
      "branchTruncated": false,
      "message": "优化库存查询索引",
      "additions": 128,
      "deletions": 12,
      "filesChanged": 5,
      "diffStatus": "OK",
      "commitUrl": "https://g-iijw5014.coding.net/p/iw-mixes/d/inventory-service/git/commit/a1b2c3d4e5f6..."
    }
  ]
}
```

- `additions/deletions/filesChanged` 在 `diffStatus != OK` 时为 `null`。
- `branches` 最多 50 个；超过时 `branchTruncated=true`，`branchCount` 为实际数量。
- `commitUrl` 为空字符串时前端不显示链接。
- 提交说明按纯文本返回；前端禁止 `v-html`。

## 7. GET /scan

查询参数：`from`、`to`、`scope`、`workbenchTeamId`（TEAM 必填）。

响应：

```json
{
  "scope": "WORKBENCH_TEAM",
  "workbenchTeamId": 1,
  "range": { "from": "2026-10-01", "to": "2026-10-30", "days": 30, "months": ["2026-10"] },
  "aggregateStatus": "RUNNING",
  "months": [
    {
      "month": "2026-10",
      "jobId": 123,
      "mode": "INCREMENTAL",
      "status": "RUNNING",
      "stage": "DIFFS",
      "progress": {
        "reposDone": 80, "reposTotal": 128,
        "branchesDone": 400, "branchesTotal": 642,
        "commitsDone": 1200, "commitsTotal": 1286,
        "diffsDone": 900, "diffsTotal": 1100
      },
      "startedAt": "2026-10-30T18:00:00+08:00",
      "finishedAt": null,
      "lastSuccessfulAt": "2026-10-30T10:32:00+08:00",
      "cooldownUntil": null,
      "retryAfterSeconds": 0,
      "error": null
    }
  ],
  "retryAfterSeconds": 0
}
```

状态：

| 状态 | 含义 |
|---|---|
| `NOT_STARTED` | 该月没有扫描记录 |
| `QUEUED` | 已入队，等待工作线程 |
| `RUNNING` | 扫描中 |
| `COMPLETED` | 完成且无失败/截断 |
| `PARTIAL` | 完成但存在失败仓库/diff/截断 |
| `FAILED` | 任务级失败 |
| `CANCELLED` | 已取消 |
| `INTERRUPTED` | 心跳超时或进程重启中断 |

阶段：`DISCOVERING`、`IDENTITIES`、`BRANCHES`、`COMMITS`、`DIFFS`、`AGGREGATING`、`DONE`。

`aggregateStatus` 用于页面总状态，优先级：`FAILED > RUNNING/QUEUED > INTERRUPTED/CANCELLED > PARTIAL > COMPLETED > NOT_STARTED`；具体月份看 `months[]`。

前端在 `QUEUED/RUNNING` 时每 3 秒轮询，完成/失败后停止。

## 8. POST /scan

手动全量重扫。所有团队成员可触发；个人范围仅本人。

请求：

```json
{
  "scope": "WORKBENCH_TEAM",
  "workbenchTeamId": 1,
  "from": "2026-10-01",
  "to": "2026-10-30",
  "mode": "FULL"
}
```

- `mode` 可省略，手动默认 `FULL`。
- 后端按自然月拆分；每个月的 active_key、冷却、单用户频率分别检查。
- 当前月自动增量刷新由 `GET /statistics` 服务端触发，不调用本接口。

响应：

```json
{
  "accepted": true,
  "reason": "STARTED",
  "skippedMonths": [
    { "month": "2026-09", "reason": "COOLDOWN", "retryAfterSeconds": 3600 }
  ],
  "retryAfterSeconds": 0,
  "scan": { "...": "GET /scan 结构" }
}
```

`accepted=false` 的 `reason`：

| reason | 含义 |
|---|---|
| `ALREADY_RUNNING` | 同范围/月份已有活动任务；返回当前 `scan` |
| `COOLDOWN` | 成功扫描后的冷却期未结束 |
| `USER_COOLDOWN` | 当前用户 5 分钟内已触发过 |
| `QUEUE_FULL` | 扫描队列已满 |
| `SKIPPED` | 所有月份都因冷却/运行中被跳过 |

跨月部分接受时，`accepted=true`，`skippedMonths` 列出被跳过的月份和原因。

冷却规则：

- 当前月成功：10 分钟。
- 历史月成功：24 小时。
- `FAILED/PARTIAL/INTERRUPTED/CANCELLED`：10 分钟后可重试。
- 单用户：5 分钟内最多一次手动触发。
- 活动任务存在时，任何触发都返回 `ALREADY_RUNNING`。

## 9. POST /scan/{jobId}/cancel

- 团队任务：发起人或团队管理员。
- 个人任务：本人。
- 取消通过 Redis 取消标记通知工作线程；工作线程在分页/仓库边界检查。
- 已排队任务可直接标记 `CANCELLED`；已运行任务保留已写入的提交和行数，状态置 `CANCELLED`。
- 响应返回 `GET /scan` 结构。

## 10. GET /scan/{jobId}/failures

查询参数：`pageNumber`、`pageSize`。

响应：

```json
{
  "repoFailures": [
    {
      "projectName": "iw-mixes",
      "projectDisplayName": "IW 系统",
      "depotName": "legacy-service",
      "reasonCode": "PERMISSION_DENIED",
      "reason": "当前成员令牌均无该仓库读取权限"
    }
  ],
  "diffFailures": [
    {
      "projectName": "iw-mixes",
      "depotName": "inventory-service",
      "sha": "a1b2c3d4...",
      "authorName": "张明远",
      "reasonCode": "TOO_LARGE",
      "reason": "diff 超过 5 MB 保护上限，未统计行数"
    }
  ],
  "page": { "pageNumber": 1, "pageSize": 20, "totalRow": 10, "totalPage": 1 }
}
```

`reasonCode` 建议值：`TOKEN_MISSING`、`PERMISSION_DENIED`、`REQUEST_FAILED`、`RESPONSE_TOO_LARGE`、`TRUNCATED`、`CODING_ERROR`。原因文案不得包含 PAT、明文邮箱、原始 JSON 或堆栈。

## 11. 身份映射

### 11.1 GET /identities

查询参数：`scope`、`workbenchTeamId`（TEAM 必填）、`keyword`（可选，按作者名搜索）、`pageNumber`、`pageSize`。

响应：

```json
{
  "mappings": [
    {
      "id": 1,
      "identityType": "EMAIL",
      "displayValue": "m***@example.com",
      "authorName": "张明远",
      "member": { "id": 1, "name": "张明远", "avatar": "https://..." },
      "source": "AUTO",
      "ignored": false,
      "updatedAt": "2026-10-30T18:00:00+08:00"
    }
  ],
  "candidates": [
    {
      "identityKey": "e3b0c44298fc1c149afbf4c8996fb924...",
      "identityType": "EMAIL",
      "authorName": "zhangmy",
      "commitCount": 12,
      "repoCount": 3,
      "lastCommitAt": "2026-10-30T15:42:31+08:00",
      "suggestedMember": {
        "id": 1,
        "name": "张明远",
        "avatar": "https://..."
      }
    }
  ],
  "page": { "pageNumber": 1, "pageSize": 20, "totalRow": 12, "totalPage": 1 }
}
```

- TEAM：团队成员均可读取；只有管理员可写。
- SELF：只返回本人映射；`candidates` 为个人扫描中未匹配到本人的作者，按提交数降序。
- `identityKey` 是 SHA-256；响应不含明文邮箱。
- `displayValue` 仅用于本人身份的可识别展示：邮箱脱敏为 `m***@example.com`，姓名脱敏首尾；团队映射可返回空字符串。
- `suggestedMember` 只作提示，不自动写入。

### 11.2 POST /identities

团队管理员请求：

```json
{
  "scope": "WORKBENCH_TEAM",
  "workbenchTeamId": 1,
  "identityType": "EMAIL",
  "identityKey": "e3b0c44298fc1c149afbf4c8996fb924...",
  "codingUserId": 1,
  "ignored": false
}
```

个人请求：

```json
{
  "scope": "SELF",
  "identityType": "EMAIL",
  "identityValue": "me@example.com"
}
```

或：

```json
{
  "scope": "SELF",
  "identityType": "NAME",
  "identityValue": "zhangmy"
}
```

规则：

- TEAM 必须使用候选 `identityKey`，不得由浏览器直接提交邮箱；`codingUserId` 和 `ignored` 二选一。
- SELF 使用 `identityValue`，服务端规范化并计算 SHA-256；请求值不写日志，响应不返回明文。
- 邮箱规范化：`trim` + 小写；姓名规范化：`trim`、连续空白折叠、ASCII 小写，保留中文。
- 唯一键：`(codingTeamId, scopeType, scopeId, identityType, identityKey)`。
- 同一身份已绑定其他成员时返回业务错误；管理员可先修改原映射。
- 自动映射不覆盖 `MANUAL/SELF`；手动映射优先。
- `ignored=true` 时 `codingUserId` 为空；未归属统计排除已忽略身份。
- 新增/修改映射把身份归属到成员后，服务端会把该身份历史上 `SKIPPED_UNMAPPED` 的提交标记为 `PENDING`，并触发 `DIFF_BACKFILL` 补齐行数；统计响应在补齐期间可通过 `scan` 显示进度。

### 11.3 PUT /identities/{id}

```json
{ "codingUserId": 2, "ignored": false }
```

- TEAM：管理员；SELF：本人。
- 只能修改当前 scope 内的映射。
- 修改后立即使统计缓存版本失效，无需重扫。

### 11.4 DELETE /identities/{id}

- 只允许删除 `MANUAL/SELF` 映射；`AUTO` 映射由下一次自动匹配维护，删除后可能重新出现。
- 删除后提交回到未归属或自动匹配结果。

## 12. 错误与校验

| 场景 | 返回 |
|---|---|
| 时间范围非法（>31 天、未来、from>to） | 业务错误，提示明确 |
| TEAM 缺少团队 ID / 非团队成员 | 403 或业务错误 |
| 非管理员修改团队映射 | 403 |
| CODING 权限不足 | 403 + `CODING_PERMISSION_DENIED` + `missingPermissions` |
| 扫描运行中重复触发 | 200 + `accepted=false` + `ALREADY_RUNNING` |
| 冷却中 | 200 + `accepted=false` + `COOLDOWN` + `retryAfterSeconds` |
| 队列满 | 200 + `accepted=false` + `QUEUE_FULL` |
| 身份冲突 | 业务错误，提示先解除原映射 |
| 扫描任务级失败 | `scan.months[].error`，HTTP 仍为 200 |
| 仓库/diff 部分失败 | 200 + `coverage`/`failures`，不抛整页错误 |

新增 CODING Action 权限映射：

| Action | 权限 |
|---|---|
| `DescribeMyDepots` | 代码仓库（只读） |
| `DescribeGitCommitsInPage` | 代码仓库（只读） |
| `DescribeGitCommitDiff` | 代码仓库（只读） |

## 13. 缓存与版本

- 查询缓存 key 前缀：`zhaogang:code:v1:`。
- 统计 key 包含：scope、scopeId、from、to、memberUserId、identityVersion、scanVersion。
- `identityVersion`：每个 scope 一个 Redis 计数器，映射变更时递增。
- `scanVersion`：每个 scope+月份一个 Redis 计数器，扫描完成/取消后递增。
- 提交明细 key 额外包含页码、页大小、depotId、keyword。
- 查询缓存 TTL 60 秒；扫描完成后主动递增版本，不依赖 pattern delete。
- CODING 成员 profile 缓存 12 小时，只缓存 emailHash/name/userId，不缓存明文邮箱。
