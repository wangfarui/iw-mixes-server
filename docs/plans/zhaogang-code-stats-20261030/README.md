# 找钢工作台代码统计：开发方案与自验交接

> 2026-11-04 验证更新：已确认真实每 3 小时定时增量，最近各轮 87—108 秒；无变更提交列表调用为 0，最新独立被测实例为 7 次。十月本人 98 次、8208/2302 行；4 条上游行数限制保留告警。见 [增量实测及清理](../../acceptance/zhaogang-code-stats-20261030/continuous-sync-followup-20261103.md)。

> 2026-11-03 最新方案：用户已选择本地持续同步，首次回补从 2026-01-01 开始，每 3 小时后台增量；月份切换只查本地，不再自动整月扫描。详见 [持续同步实施设计](continuous-sync-20261103.md) 和 [本轮验收](../../acceptance/zhaogang-code-stats-20261030/continuous-sync-20261103.md)。首次回补已于 13:06 结束（部分完成）；稳定分支游标修正及真实增量对比见 [下午续验](../../acceptance/zhaogang-code-stats-20261030/continuous-sync-followup-20261103.md)。

> 2026-11-02 最新实施口径：成员 SELF 身份统一供个人/团队使用；PAT 默认姓名及邮箱，支持增删；元数据返回后先按身份过滤，仅持久化命中提交；身份变化按指纹重读历史月。旧的全作者持久化、团队独立映射、邮箱优先回退及仅 DIFF_BACKFILL 补数说明由 [最新身份设计](identity-first-feasibility-20261102.md) 覆盖。实际结果见 [本轮验收](../../acceptance/zhaogang-code-stats-20261030/identity-first-20261102.md)。

日期：2026-10-30。主仓库：`iw-mixes-server`。跨端仓库：`iw-mixes-web-platform`。

- 设计状态：已确认（2026-10-30 用户确认功能清单、页面示意图和 5 项统计口径）。
- 实施授权：用户在 2026-10-30 新会话中要求按本包实施并自验；数据库写入、真实成员 PAT 扫描、提交、推送和发布仍未授权。
- 代码状态：已实施（2026-10-30，未提交）。
- 验收状态：已完成本地本人十月真实扫描及 SHA 缓存冷/热对比；热复跑 37—42 秒，278 项后端与 Web 四项检查通过。3 条 diff 仍失败，整体 AC 部分完成。详见 [最新缓存实测](../../acceptance/zhaogang-code-stats-20261030/branch-sha-cache-20261102.md)。

> 本包按 `iw-feature-handoff` 交接模板组织；模板和目录名称不构成执行授权。

## 1. 确认依据与目标

### 1.1 业务目标

在找钢工作台新增“代码”入口，统计本人和工作台团队成员在 CODING Git 仓库的提交情况：

- 全仓库、全分支扫描。
- 统计提交次数、新增代码行、删除代码行、净增代码行。
- 我的代码按个人节奏展示；团队代码按成员对比展示，并支持指定成员的时间趋势钻取。
- 扫描结果持久化缓存，避免每次打开页面重复全量扫描。

影响端：后端 `iw-mixes-server`（iw-external）和 Web `iw-mixes-web-platform`。小程序不改。

### 1.2 已确认事项

以下为 2026-10-30 用户在本会话中的确认原文摘要：

| 事项 | 确认结果 |
|---|---|
| 合并提交 | 排除；不计数、不统计行数 |
| 扫描时间 | 按自然月扫描；全量=全部仓库×全部分支，不扫全部历史 |
| 未归属提交 | 只计提交次数，不统计行数 |
| 缓存刷新 | 当前月 60 分钟增量刷新；历史月手动重扫 |
| 重扫权限 | 所有人都能手动重扫；通过并发、冷却和单用户频率限制控制 |
| 成员 PAT | 允许服务端读取成员 PAT 备份用于团队代码统计；不增加入队提示 |
| 我的代码范围 | 独立扫描本人 PAT 可见的全部仓库 |
| 时间范围 | 本周、本月、指定月、指定时间范围；自定义最多 31 天 |
| 本周/本月口径 | 本周=周一到现在；本月=月初到现在 |
| 我的代码图表 | 每日提交频率、代码行变更趋势、仓库提交频率、提交明细；另保留日历热力图、提交时段分布、分支活跃度 Top 10 |
| 团队代码图表 | 团队提交频率、团队代码行变更，两张图 x 轴都是成员；另保留指定成员分析（时间趋势） |
| 团队页移除项 | 团队活跃人数趋势、仓库/分支活跃度、成员热力图、成员贡献对比、团队提交明细 |
| 团队 0 提交成员 | 包含，排在成员列表末尾 |
| 成员柱联动 | 点击成员柱联动“指定成员分析”，默认选中提交次数最高成员 |
| 未归属作者 | 只保留 KPI 入口和抽屉，不做团队图表 |

### 1.3 明确不做

- 不做仓库、分支、项目范围选择；所有成员可读仓库、所有分支全部纳入扫描。
- 不为未归属提交拉取 diff，不统计其代码行。
- 团队页不做仓库/分支活跃度、成员热力图、团队活跃人数趋势、团队提交明细。
- 不做 MR/代码评审指标、AI 提交摘要、CSV 导出。
- 不修改 CODING 仓库、提交、分支、事项或构建。
- 不把工作台变成 CODING 团队全员目录；团队范围是工作台团队成员。
- 不在设计阶段执行 SQL、启动后端、运行 Maven 或修改业务代码。

### 1.4 待验证事实（不阻断设计，阻断实施）

以下来自 CODING 官方 OpenAPI 文档，未在本会话用真实 PAT 调用，实施前必须用授权测试令牌实测：

- `DescribeMyDepots` 是否覆盖成员可读全部项目仓库；若覆盖不足，回退到 `DescribeUserProjects` + `DescribeProjectDepotInfoList`。
- `DescribeProjectDepotBranches` 对 `DepotType=CODING` 的普通成员权限和空仓库行为。
- `DescribeGitCommitsInPage` 的 `StartDate/EndDate` 边界、`PageSize` 上限、`Page.TotalRow` 准确性。
- `DescribeGitCommitDiff` 的超大提交响应、根提交、二进制文件和权限行为。
- `DescribeCodingCurrentUser` 返回的邮箱与团队 Git 作者邮箱的实际匹配率。
- `DescribeMyDepots` 返回的 `WebUrl` 能否直接拼接 `/commit/{sha}`。
- CODING 团队 30 次/秒/接口的限流在真实扫描中的表现。

## 2. 定稿行为与资料

### 2.1 页面示意图

- [我的代码页面](assets/mine-page.png)
- [团队代码页面](assets/team-page.png)

两张图是视觉示意图，数字、姓名和部分文案为模拟内容，不是可操作原型；最终以本包接口、数据口径和真实 CODING 数据为准。

### 2.2 我的代码

页面元素：

1. Tab：我的代码 / 团队代码。
2. 时间范围：本周、本月、指定月、自定义；自定义最多 31 天。
3. 我的 Git 身份入口、重新扫描、数据缓存时间。
4. KPI：提交次数、新增代码行、删除代码行、净增代码行、活跃天数、涉及仓库。
5. 图表：每日提交频率、代码行变更趋势、仓库提交频率、提交日历热力图、提交时段分布、分支活跃度 Top 10。
6. 提交明细表：时间、仓库、分支、提交说明、新增、删除、修改文件数、短 SHA、CODING 链接。

行为：

- 每日提交频率按天展示；周末底色区分；点击日期筛选提交明细。
- 代码行变更趋势为绿色新增、红色删除，净增用折线或单独数值展示。
- 仓库提交频率为横向条形图，默认 Top 10，可展开全部；点击仓库筛选明细。
- 提交明细默认当前时间范围，分页；提交说明按纯文本渲染。
- 身份未匹配时展示“有 N 次提交未匹配到你的 Git 身份”的提示，引导绑定。
- “我的 Git 身份”是个人归属所必需的支撑能力：默认用本人 CODING 邮箱自动匹配；Git 邮箱不一致时允许手动绑定多个作者名/邮箱，邮箱只保存哈希。

### 2.3 团队代码

页面元素：

1. Team 选择、时间范围、重新扫描、数据缓存时间、未归属作者入口。
2. KPI：提交次数、新增代码行、删除代码行、净增代码行、活跃成员/成员总数、未归属提交。
3. 团队提交频率：x 轴为成员，y 轴为提交次数；按提交次数降序；包含 0 提交成员，排在末尾；点击成员柱联动下方分析。
4. 团队代码行变更：x 轴为成员，y 轴为新增/删除行；每个成员展示绿色新增和红色删除，悬浮显示净增；点击成员柱联动。
5. 指定成员分析：成员下拉 + 两张时间序列图“每日提交频率”“代码行变更趋势”；默认选中提交次数最高成员。
6. 未归属作者：KPI 旁入口，抽屉展示未映射作者并可绑定/忽略。

明确不展示：团队活跃人数趋势、仓库/分支活跃度、成员热力图、成员贡献对比、团队提交明细。

### 2.4 时间范围

| 选项 | 规则 |
|---|---|
| 本周 | 本周一 00:00:00 至今天 23:59:59 |
| 本月 | 本月 1 日 00:00:00 至今天 23:59:59 |
| 指定月 | 该自然月 1 日至月末；当前月截到今天 |
| 自定义 | 开始日 00:00:00 至结束日 23:59:59；最多 31 个自然日；不可选未来日期 |

- 时区固定 `Asia/Shanghai`。
- 查询/扫描范围限制在最近 24 个自然月（`code-stats-retention-months`，默认 24）；更早月份前端禁用，后端拒绝。
- 后端接收 `from/to`，前端负责生成本周/本月快捷范围。
- 范围跨月时按自然月拆分为多个扫描月，逐月读取缓存或扫描，再按真实日期裁剪合并。
- 范围包含当前月时，当前月最多每 60 分钟自动增量刷新；历史月不自动刷新。

### 2.5 重扫触发

所有团队成员都可以触发团队重扫；个人范围只有本人可以触发。

| 限制 | 规则 |
|---|---|
| 并发 | 同一 `scope + 月份` 同时只允许一个活动任务；重复触发返回当前进度 |
| 当前月冷却 | 成功完成后 10 分钟内不可手动重扫 |
| 历史月冷却 | 成功完成后 24 小时内不可手动重扫 |
| 失败重试 | `FAILED/PARTIAL/INTERRUPTED/CANCELLED` 10 分钟后允许重试 |
| 单用户频率 | 同一用户 5 分钟内最多触发一次手动重扫 |
| 跨月范围 | 逐月检查；冷却中的月份跳过并使用缓存，其余月份正常重扫 |
| 扫描中 | 页面显示进度并禁用重扫按钮 |
| 取消 | 发起人或团队管理员可取消团队任务；个人任务本人可取消 |
| 审计 | 记录触发人、时间、范围、模式、结果 |

手动重扫使用 `FULL` 模式；当前月自动刷新使用 `INCREMENTAL` 模式。

## 3. 当前源码与环境事实

核查时间：2026-10-30。工作区：`/Users/wangfarui/workspaces/wfr/iw-mixes_ai`。

### 3.1 仓库状态

| 仓库 | 分支 | HEAD | 检查时工作区 |
|---|---|---|---|
| `iw-mixes-server` | `main` | `0d6d301` | `git status --short` 为空 |
| `iw-mixes-web-platform` | `dev` | `d821532` | `git status --short` 为空 |

HEAD 只用于定位，不代表未来实施时的现状。实施会话必须重新检查工作区，保护已有修改；不得 reset、checkout 或覆盖未提交工作。精确文件指纹见 [BASELINE.json](BASELINE.json)（33 个源码/契约文件，覆盖后端和 Web）。

`context/` 下的工作台知识文档是会话资料，不在 `BASELINE.json` 指纹范围；实施会话仍需先读 [工作台开发入口](../../../../context/zhaogang-workbench-feature-development-context.md)。

### 3.2 关键源码事实与复用点

| 位置 | 当前事实 | 计划 |
|---|---|---|
| [CodingOpenApiPort.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/CodingOpenApiPort.java) | 端口只有项目、计划、构建、分支、工时、事项等语义方法，没有仓库发现和提交读取 | 新增 `myDepots`、`commitsInPage`、`commitDiff`、`currentUserProfile` 等方法及记录 |
| [CodingOpenApiClient.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/CodingOpenApiClient.java) | `invoke` 统一构造 CODING Action，已有分支 Action；无提交/仓库列表 Action | 新增 `DescribeMyDepots`、`DescribeGitCommitsInPage`、`DescribeGitCommitDiff`、`DescribeCodingCurrentUser` 解析；为 diff 增加响应大小上限 |
| [CodingOpenApiException.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/CodingOpenApiException.java) | Action 到权限名称映射不含新 Action | 增加“代码仓库（只读）”映射 |
| [CodingRequestLimiter.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/CodingRequestLimiter.java) | 单 PAT 并发默认 4；仅 `DescribeIssue` 有额外 QPS 限制 | 增加提交/行数 Action 的全局 QPS 限制；扫描仍受单 PAT 并发限制 |
| [ZhaogangProperties.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangProperties.java) | 已有工时缓存、成员并发、请求超时等配置 | 新增扫描并发、队列、分页上限、diff 大小、QPS、缓存、保留月数配置 |
| [ZhaogangController.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangController.java) | 统一前缀 `/external-service/api/zhaogang`；工时端点在 244-286 行附近 | 新增独立 `ZhaogangCodeStatsController`，前缀 `/external-service/api/zhaogang/code-stats`，不把扫描逻辑塞进现有 Controller |
| [ZhaogangExceptionHandler.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangExceptionHandler.java) | `assignableTypes` 列出工作台各 Controller | 加入代码统计 Controller；继续使用 `CodingOpenApiException` 的权限错误结构 |
| [ZhaogangSession.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangSession.java) | 会话含 token、userId、userName、avatar、team、teamId | 不扩展会话；代码统计通过 `Context` 传递 token，不落库、不返回前端 |
| [WorklogModule.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/worklog/WorklogModule.java) | `SELF/WORKBENCH_TEAM` 范围、Context、MemberCredential 模式可直接借鉴 | 新建 `CodeStatsModule` 及类似 Context，不修改工时契约 |
| [WorklogQueryService.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/worklog/WorklogQueryService.java) | 逐成员查询、部分失败、`coverage.memberIssues`、有界并发池 | 代码统计是“逐仓库/逐分支”查询，复用部分失败和成员问题结构，不复用逐成员工时语义 |
| [WorklogScopeDirectory.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/worklog/WorklogScopeDirectory.java) | 团队范围从工作台团队成员表解析，成员 PAT 从凭证表读取 | 新建代码统计范围解析；团队=工作台团队，成员 PAT 用于仓库发现和读取 |
| [WorklogViewCache.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/worklog/WorklogViewCache.java) | Redis 序列化、按范围/成员指纹/日历版本缓存 | 新建 `CodeStatsViewCache`，按范围/时间范围/扫描版本/身份版本缓存 |
| [CodingCredentialService.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/credential/CodingCredentialService.java) | 服务端保存成员 PAT 明文备份，当前注释限定“团队工时” | 按用户确认扩展用途到“团队工时 + 团队代码统计”；同步更新注释和产品知识；不返回前端、不写日志 |
| [WorkbenchTeamModule.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/team/WorkbenchTeamModule.java) | 已有团队列表、成员、管理员权限、`resolveWorklogScope` | 复用团队/成员/管理员解析；身份映射写操作校验管理员 |
| [WorkbenchTeamModels.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/team/WorkbenchTeamModels.java) | `TeamListItem` 含 administrator；`WorklogTeamScope` 含成员 | 代码统计 `options` 复用团队列表；团队成员用于 x 轴成员和 0 提交补全 |
| [IwExternalApplication.java](../../../iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/IwExternalApplication.java) | 已启用 `@EnableScheduling` | 用于扫描心跳恢复、过期数据清理；不做定时全量扫描 |
| [iw-external-init.sql](../../../iw-packaging-parent/iw-external/scripts/iw-external-init.sql) | 工作台表集中在外部服务初始化脚本 | 追加 4 张代码统计表；见 [sql/schema-draft.sql](sql/schema-draft.sql) |
| [ZhaogangWorkbench.vue](../../../../iw-mixes-web-platform/src/views/zhaogang/ZhaogangWorkbench.vue) | 侧栏导航、TabKey、routeTab、navigateTab、router-view 控制；工时在 111/268 行附近 | 新增“代码”导航和 `code` Tab；两页共用工作台壳层 |
| [router/zhaogang.ts](../../../../iw-mixes-web-platform/src/router/zhaogang.ts) | `/zhaogang/worklogs` 等公开子路由 | 新增 `/zhaogang/code/mine`、`/zhaogang/code/team`，`meta.zhaogangView='code'` |
| [api/zhaogang.ts](../../../../iw-mixes-web-platform/src/api/zhaogang.ts) | 独立 `fetch` + `credentials: include`；权限提示统一分发 | 新建 `zhaogangCodeStats.ts`，复用 `zhaogangRequest` 和权限提示 |
| [types/zhaogang.ts](../../../../iw-mixes-web-platform/src/types/zhaogang.ts) | `defaultTab`、工时范围/覆盖类型 | 新增代码统计类型；`defaultTab` 增加 `code` |
| [zhaogangPreferences.ts](../../../../iw-mixes-web-platform/src/services/zhaogangPreferences.ts) | 默认首页允许值白名单 | 加入 `code`，否则偏好会被重置为 release |
| [WorklogTrendChart.vue](../../../../iw-mixes-web-platform/src/views/zhaogang/components/worklog/WorklogTrendChart.vue) | 手写 SVG 图表，无 ECharts 依赖 | 代码统计图表优先沿用 SVG/CSS；不擅自新增 ECharts |
| [WorklogDashboard.vue](../../../../iw-mixes-web-platform/src/views/zhaogang/components/worklog/WorklogDashboard.vue) | 范围切换、团队下拉、刷新、骨架屏、覆盖提示 | 复用交互模式；代码统计页面按本包设计独立实现 |
| [zhaogang-release-notes.json](../../../../iw-mixes-web-platform/public/zhaogang-release-notes.json) | 工作台版本更新清单 | 功能上线时新增发布记录并更换 `releaseId` |

### 3.3 设计阶段未执行的验证

- 未用真实 PAT 调用 CODING 新 Action。
- 未执行 Maven、未启动后端、未执行 SQL、未写数据库。
- 未启动 Web、未做真实浏览器交互。
- 未修改任何业务代码。
- 示意图已保存并打开检查，但只是视觉示意。

## 4. 接口、数据与状态约束

完整接口契约见 [api-contract.md](api-contract.md)。

数据表、扫描状态机、身份解析、缓存与限额见 [data-and-scan.md](data-and-scan.md)。

建表草案见 [sql/schema-draft.sql](sql/schema-draft.sql)，**未执行**。

Web 页面、组件和交互见 [web-pages.md](web-pages.md)。

核心约束摘要：

- 统一前缀：`/external-service/api/zhaogang/code-stats`。
- 范围：`SELF` / `WORKBENCH_TEAM`；团队必须传 `workbenchTeamId`，后端校验成员身份。
- 时间：`from/to` 含首尾，最多 31 天，不可选未来；跨月拆分为自然月任务。
- 扫描：全仓库、全分支；去重键 `(codingTeamId, scopeType, scopeId, depotId, sha)`。
- 合并提交：`Parents > 1`，存库但不计数、不取 diff。
- 行数：只对已归属成员的提交取 diff；未归属 `diff_status=SKIPPED_UNMAPPED`。
- 身份：邮箱哈希优先，姓名唯一匹配次之，手动映射优先；不存明文邮箱。
- 缓存：MySQL 为持久缓存，Redis 为查询缓存；完成扫描/修改映射后通过版本号失效。
- 重扫：所有团队成员可触发；并发、冷却、单用户频率限制见 2.5。
- 安全：成员 PAT 只在服务端内存和凭证表；不返回、不写日志；CODING 只读。

## 5. 文件级实施计划

| 步骤 | 仓库及文件/模块 | 改动与依赖 | 本步验证 |
|---|---|---|---|
| 1 | 后端 `CodingOpenApiPort`、`CodingOpenApiClient`、`CodingOpenApiException` | 新增 `DescribeMyDepots`、`DescribeGitCommitsInPage`、`DescribeGitCommitDiff`、`DescribeCodingCurrentUser` 解析；补权限映射；diff 响应大小上限 | `CodingOpenApiClientTest` 增加 JSON fixture 解析；编译 |
| 2 | 后端 `CodingRequestLimiter`、`ZhaogangProperties` | 提交/行数全局 QPS、扫描并发/队列、分页上限、缓存/保留配置 | 限流单测；配置绑定测试 |
| 3 | 后端 `iw-external-init.sql`、`codestats/entity/`、`mapper/ZhaogangCode*Mapper` | 4 张表、实体、Mapper；复杂聚合 SQL 可用 XML | 编译；隔离库执行 DDL 仅限实施授权后 |
| 4 | 后端 `codestats/CodeStatsScopeDirectory`、`CodeAuthorIdentityService`、`CodeStatsModels`、`CodeStatsModule` | SELF/TEAM 范围、团队管理员、成员 PAT、身份自动匹配/手动映射 | 单测：本人/团队/非成员/管理员/无 PAT/重名 |
| 5 | 后端 `codestats/CodeRange`、`CodeScanService`、`CodeScanRepository` | `from/to` 校验、跨月拆分、active_key、冷却、单用户频率、队列、恢复/清理 | 单测：31 天边界、未来日期、跨月、active 冲突、冷却 |
| 6 | 后端 `codestats/CodeScanWorker`、`CodingRepoDiscovery` | 发现仓库/身份/分支/提交；token 池；批 upsert；分支 JSON 聚合；diff；`DIFF_BACKFILL`；取消/心跳；全量清理 | Mock CODING 端口：多分支去重、合并排除、diff 状态、映射后行数补齐、部分失败、取消 |
| 7 | 后端 `codestats/CodeStatsQueryService`、`CodeStatsViewCache` | 本人/团队聚合、0 提交成员、指定成员日序列、提交明细分页、未归属作者、Redis 版本缓存 | 聚合一致性单测；SQL/Mapper 测试；缓存失效测试 |
| 8 | 后端 `ZhaogangCodeStatsController`、`ZhaogangExceptionHandler` | 10 个端点、参数校验、权限、统一异常；加入 assignableTypes | MockMvc/Controller 单测；编译 |
| 9 | Web `types/zhaogangCodeStats.ts`、`api/zhaogangCodeStats.ts` | 类型与 API 封装；复用 `zhaogangRequest` | `npm run type-check` |
| 10 | Web `router/zhaogang.ts`、`ZhaogangWorkbench.vue`、`zhaogangPreferences.ts`、`types/zhaogang.ts` | 两个路由、侧栏“代码”、TabKey/routeTab/navigateTab、默认首页白名单 | 路由可达；`npm run type-check` |
| 11 | Web `views/zhaogang/code/ZhaogangCodeMineView.vue` 及组件 | 时间范围、KPI、六类图表、提交明细、我的 Git 身份、扫描状态/空/失败 | `npm run type-check`、`npm run build`、浏览器检查 `/zhaogang/code/mine` |
| 12 | Web `views/zhaogang/code/ZhaogangCodeTeamView.vue` 及组件 | 团队选择、KPI、成员 x 轴两图、指定成员分析、未归属作者、重扫/冷却 | `npm run type-check`、`npm run build`、浏览器检查 `/zhaogang/code/team` |
| 13 | Web 共享组件 | `CodeStatsHeader`、`CodeStatsRangePicker`、`CodeStatsScanBanner`、`CodeStatsKpiCards`、`CodeStatsBarChart`、`CodeStatsDivergingChart`、`CodeStatsLineBarChart`、`CodeStatsCalendarHeatmap`、`CodeCommitTable`、`CodeIdentityDialog`、`CodeScanScopeDrawer`、`CodeUnmappedDrawer` | 组件独立渲染；键盘/只读/空数据 |
| 14 | 文档与发布 | 更新 `zhaogang-workbench-product-knowledge.md`、`zhaogang-workbench-technical-reference.md`、`zhaogang-workbench-operations-and-verification.md`、`public/zhaogang-release-notes.json` | 文档链接；`npm run check:zhaogang-release-notes` |
| 15 | 端到端 | `scripts/local-backend.sh` 启动后端；Web dev；真实只读 PAT；按 AC 验证；记录数据残留 | 见第 6 节；未授权前不执行 |

依赖顺序：1→2→3→4→5→6→7→8；Web 9/10 可与后端并行，11/12 依赖 9/10 和 8 的联调；13 随 11/12；14 最后；15 需实施授权和真实环境。

## 6. 自验与测试数据

环境、登录态、工具前提：实施会话需要可用的 CODING 测试 PAT（只读范围）、本地 MySQL/Redis、`iw-mixes-server` 和 `iw-mixes-web-platform`；后端启动只使用 `scripts/local-backend.sh`，Maven 使用工作区约定的 Java 17。

允许的测试数据范围：单元测试使用合成 CODING JSON fixture；隔离测试库可使用前缀 `AT-CODE-20261030-` 的团队/身份/扫描记录；真实 CODING 仓库只读，不创建、不修改、不删除任何 CODING 资源。测试完成后清理隔离库记录；不清理真实 CODING 数据。

| 编号 | 前置条件 | 操作 | 预期结果 | 验证方式 | 结果 |
|---|---|---|---|---|---|
| AC-01 | 已登录工作台 | 分别选择本周、本月、指定月、自定义 31 天；尝试 32 天、未来日期、from>to | 合法范围解析正确；非法范围被拒绝且提示明确 | 接口 + 浏览器 | 未执行 |
| AC-02 | 范围跨两个月 | 选择 2026-09-25~2026-10-10 | 拆分两个月扫描/缓存；合并结果按真实日期裁剪；跨月提交不重复 | 接口 + 数据库 | 未执行 |
| AC-03 | 团队成员仓库可见性不同 | 团队扫描；一个成员未绑定 PAT | 仓库取可读并集；未绑定成员进入 coverage；其独有仓库缺失有明确提示 | Mock + 真实 PAT | 未执行 |
| AC-04 | 仓库存在 0 提交成员、feature 分支 | 团队/个人扫描 | 0 提交成员出现在团队图表末尾；只在 feature 分支的提交被统计；同一 SHA 多分支只计一次 | Mock + 真实 API | 未执行 |
| AC-05 | 存在合并提交 | 扫描含合并提交的仓库和月份 | 合并提交不计数、不拉 diff；`excludedMergeCommitCount` 正确 | Mock + 数据库 | 未执行 |
| AC-06 | 已归属成员有普通/失败/超大 diff | 扫描后对账 | 新增/删除/文件数与样本一致；失败/超大/未归属有状态，不出现伪造 0 | 真实 API + 样本对账 | 未执行 |
| AC-07 | 历史月已有扫描 | 重复进入历史月；当前月等待 60 分钟后进入；手动重扫 | 历史月不自动重扫；当前月触发增量；手动全量受冷却；活动任务不重复 | 接口 + Redis/DB | 未执行 |
| AC-08 | 扫描运行中 | 离开页面再进入、取消、重启进程、模拟心跳超时 | 进度恢复；取消生效；中断可重试；部分失败保留成功数据 | 后端集成 + 浏览器 | 未执行 |
| AC-09 | 我的代码有数据/无数据 | 打开 `/zhaogang/code/mine` | KPI、每日提交频率、代码行趋势、仓库频率、日历热力图、时段分布、分支 Top 10、提交明细均正确；空/加载/失败状态完整 | 浏览器 + 接口 | 未执行 |
| AC-10 | 团队有 0 提交成员 | 打开 `/zhaogang/code/team` | 两张成员 x 轴图正确且包含 0 提交成员；点击成员柱更新指定成员分析；已移除的图表/表格不存在 | 浏览器 + 接口 | 未执行 |
| AC-11 | 团队成员有不同 Git 邮箱/姓名 | 自动匹配、手动映射、忽略、个人身份绑定；映射后补齐历史行数 | 邮箱哈希优先；姓名唯一才自动；手动优先；映射即时生效；`SKIPPED_UNMAPPED` 提交经 `DIFF_BACKFILL` 补齐行数；响应不含明文邮箱 | 接口 + DB | 未执行 |
| AC-12 | 普通成员/管理员/非成员 | 触发重扫、打开团队页、修改映射 | 所有成员可重扫；非成员 403；只有管理员可改团队映射；个人身份仅本人可改 | 接口 + 浏览器 | 未执行 |
| AC-13 | 构造提交说明含 HTML、跨团队 ID | 浏览器渲染、直接请求他人团队 | HTML 按文本显示；无 XSS；跨团队/跨用户数据不可读 | 安全回归 | 未执行 |
| AC-14 | 128 仓库/642 分支/月 | 全量扫描并观察耗时、QPS、diff 失败 | 扫描异步可完成；单 PAT 并发和全局 QPS 生效；无重复扫描；diff 上限生效 | 性能/日志 | 未执行 |
| AC-15 | 超过 24 个月的数据 | 触发保留清理 | 超期提交/任务按配置清理；未超期数据保留；清理可重入 | 隔离库 | 未执行 |
| AC-16 | 代码完成 | 后端编译/测试；Web type-check/build；发布清单检查 | 目标模块编译和测试通过；Web 类型/构建通过；发布清单校验通过 | 命令 | 未执行 |

设计阶段所有结果均为“未执行”。实施会话必须记录真实运行版本、脱敏输出、必要截图和数据残留；不得把示意图、Mock 或旧会话结果当作本次验收。

## 7. 授权边界与完成条件

已授权动作及环境：

- 本会话：读取源码和文档、生成视觉示意图、创建本交接包、采集源码基线。
- 本会话未授权：修改业务代码、运行 Maven、启动/重启后端、执行 SQL、写数据库、启动 Web 开发服务、提交、推送、发布。

尚未授权的外部操作：

- 生产/测试环境数据库写入。
- 触发 UAT/PRD 构建。
- 提交、推送、创建 PR、发布生产。
- 使用真实成员 PAT 进行大规模扫描（实施验证需单独确认测试账户和范围）。
- 创建或启动新的实施会话。

完成条件：

- 设计包可独立阅读，接口、表结构、页面、扫描状态、权限和验收用例完整。
- 实施会话获得明确授权后，按第 5 节顺序完成，并逐项记录 AC 结果。
- 后端编译/测试、Web type-check/build 通过。
- 真实 CODING 只读验证和浏览器检查完成；未执行项明确标注。
- 不把模拟图、Mock 结果或设计阶段检查当作验收通过。

## 8. 实施与验收记录

2026-10-30 实施会话已完成代码、离线测试和模拟 UI 检查，详见 [实施与自验记录](../../acceptance/zhaogang-code-stats-20261030/README.md)。真实数据库、真实成员 PAT、真实权限和并发/冷却验收仍待授权后执行。

本地自动化验收（含待执行脚本与命令、逐步断言、AC 结果填写要求和授权清单）见 [本地验收交接](local-acceptance-handoff.md)。

## 9. 新会话启动提示词

> 使用 `$iw-feature-handoff`，读取 `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/plans/zhaogang-code-stats-20261030/README.md`，按已确认方案完成找钢工作台代码统计功能开发并自行验证。先读联合工作区 `AGENTS.md`、`iw-mixes-server/AGENTS.md`、`iw-mixes-web-platform/AGENTS.md` 和本包 `BASELINE.json`，核对当前已有修改；基于现状增量实施，不 reset/checkout/覆盖已有工作。已确认：合并提交排除、按自然月扫描、未归属只计次数不统计行数、当前月 60 分钟增量刷新且历史月手动重扫、所有成员可手动重扫但受并发/冷却/单用户频率限制、我的代码独立扫描本人全部可读仓库、时间范围本周/本月/指定月/自定义最多 31 天、团队页两张成员 x 轴图且只保留指定成员分析。不要重新询问这些已确认项。按本包接口契约、表结构和文件级计划实施；后端只使用 `scripts/local-backend.sh`，Maven 使用 Java 17 和工作区约定；Web 使用 `npm run type-check`、`npm run build` 和真实浏览器检查。数据库写入、真实成员 PAT 扫描、提交、推送和发布需在本会话获得明确授权后执行；未授权时先完成离线 Mock/单测和可执行的前端构建。记录真实运行版本、脱敏结果、AC 结果和数据残留；mock、示意图和旧会话结果不充当本次验收。

2026-11-02 最新续验：修复 Payload.Depots 解析，真实发现 232 仓库/9565 分支，后端 258 项和 Web 四项检查通过；整体 AC 仍部分完成，本次测试数据已清理。见 [仓库发现修复与证据](../../acceptance/zhaogang-code-stats-20261030/repository-discovery-fix-20261102.md)。

2026-11-02 十月续验：修复只读瞬时错误重试、四路分支首屏预取和失败页面状态；263 项后端及 Web 四项检查通过，十月新任务已进入提交阶段。见 [失败与性能修复记录](../../acceptance/zhaogang-code-stats-20261030/scan-resilience-20261102.md)。

2026-11-02 部分结果续验：局部分支失败继续其余范围，未完成或中断也展示已保存数据；后端 268 项、Web 四项检查通过，真实 10 月页面已显示 98 次本人提交。见 [部分结果验证](../../acceptance/zhaogang-code-stats-20261030/partial-results-20261102.md)。

2026-11-02 最新状态：按分支头 SHA 和月份复用规范化提交缓存，十月首次 809 秒、最终复跑 42/37 秒；默认冷却已恢复，真实统计保留。见 [SHA 缓存实测](../../acceptance/zhaogang-code-stats-20261030/branch-sha-cache-20261102.md)。

2026-11-02 特殊仓库规则：`online.base.apijar.service` 仅保留提交次数，跳过行数及 diff；十月真实任务 19 已完成（72 秒，仓库/diff 失败均 0），后端 284 项及 Web 四项检查通过。见 [专项验收与迁移记录](../../acceptance/zhaogang-code-stats-20261030/apijar-commit-count-only-20261102.md)。

2026-11-03 性能调研（尚未改业务代码）：洞察页实际使用 CODING_CODE_COMMIT 聚合数据集，当前本人 PAT 调用图表接口因 scope 不支持被拒绝；优先验证 OAuth 生态研发度量只读接入，再决定聚合数据源或本地持续同步方案。见 [洞察与工作台性能调研](insight-performance-research-20261103.md)。
