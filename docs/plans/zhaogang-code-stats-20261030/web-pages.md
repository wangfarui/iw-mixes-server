# Web 页面与组件实施

上级文档：[README.md](README.md)。接口见 [api-contract.md](api-contract.md)。

## 1. 路由与导航

### 1.1 路由

在 [router/zhaogang.ts](../../../../iw-mixes-web-platform/src/router/zhaogang.ts) 增加公开子路由：

| 路径 | 页面 | meta |
|---|---|---|
| `/zhaogang/code` | 重定向到 `/zhaogang/code/mine` | `zhaogangView: 'code'` |
| `/zhaogang/code/mine` | 我的代码 | `zhaogangView: 'code'` |
| `/zhaogang/code/team` | 团队代码 | `zhaogangView: 'code'` |

- 两个页面共用工作台壳层 `ZhaogangWorkbench.vue`。
- 页面顶部 Tab 切换两个路由；切换时不重建壳层。
- 侧栏“代码”进入默认 `/zhaogang/code/mine`。

### 1.2 工作台壳层

修改 [ZhaogangWorkbench.vue](../../../../iw-mixes-web-platform/src/views/zhaogang/ZhaogangWorkbench.vue)：

- `TabKey` 增加 `'code'`（当前类型在 568 行附近）。
- `routeTab()` 将 `zhaogangView === 'code'` 映射为 `'code'`（700 行附近）。
- `navigateTab('code')` 跳转 `/zhaogang/code/mine`（720 行附近）。
- 侧栏在“迭代”和“工时”之间增加“代码”按钮，图标使用 `@element-plus/icons-vue` 的 `DataAnalysis`；保持收起态 `title`/`aria-label`。
- 路由内容区条件增加 `activeTab === 'code'`（147 行附近），让两个代码页面通过 `router-view` 渲染。
- 设置页“默认首页”单选增加“代码”；`types/zhaogang.ts` 的 `defaultTab` 和 `services/zhaogangPreferences.ts` 的白名单同步增加 `'code'`（当前在 22/36 行附近）。
- 不把代码统计塞进 `WorklogDashboard.vue`；独立页面，避免工时状态耦合。

### 1.3 页面文件

计划新增：

```text
src/views/zhaogang/code/
  ZhaogangCodeMineView.vue
  ZhaogangCodeTeamView.vue
  components/
    CodeStatsHeader.vue
    CodeStatsRangePicker.vue
    CodeStatsScanBanner.vue
    CodeStatsKpiCards.vue
    CodeStatsBarChart.vue
    CodeStatsDivergingBarChart.vue
    CodeStatsLineBarChart.vue
    CodeStatsHorizontalBarChart.vue
    CodeStatsCalendarHeatmap.vue
    CodeStatsMemberBarChart.vue
    CodeStatsMemberDivergingChart.vue
    CodeStatsSelectedMemberPanel.vue
    CodeCommitTable.vue
    CodeIdentityDialog.vue
    CodeScanScopeDrawer.vue
    CodeUnmappedDrawer.vue
```

可以按实际复用情况合并小组件，但以下职责必须分开：

- 时间范围解析与展示。
- 扫描状态/进度/重扫。
- KPI。
- 图表。
- 提交明细表。
- 身份映射/未归属抽屉。

## 2. 时间范围组件

### 2.1 选项

```
本周 | 本月 | 指定月 | 自定义
```

- 本周：本周一 00:00 至今天 23:59。
- 本月：本月 1 日 00:00 至今天 23:59。
- 指定月：`el-date-picker type="month"`；当前月截到今天。
- 自定义：`el-date-picker type="daterange"`；最多 31 个自然日；不可选未来。
- 指定月和自定义均不可选超过 24 个月前的日期；与后端保留策略一致。

### 2.2 展示

- 右侧显示解析后的真实范围，例如 `2026-10-01 ~ 2026-10-30`。
- 自定义范围非法时禁用查询按钮并提示“最多选择 31 天”。
- 切换范围时：
  - 取消/忽略旧请求（AbortController 或请求序号）。
  - 保留旧图表并显示 loading，避免白屏。
  - 新范围无缓存时展示扫描进度。

### 2.3 日期工具

新增 `src/views/zhaogang/code/codeStatsRange.ts` 或 `src/services/zhaogangCodeStatsRange.ts`：

- `resolveCodeStatsRange(option, month, customRange, now)` 返回 `{ from, to, label }`。
- 使用本地日期计算，时区由浏览器 `Asia/Shanghai` 环境保证；后端仍会校验。
- 纯函数，便于 Node 脚本或单测覆盖 31 天、周一、月初、跨月。

## 3. 扫描状态与重扫

### 3.1 状态条

`CodeStatsScanBanner.vue`：

- 扫描中：阶段文案、仓库/分支/提交/diff 进度、进度条。
- 已完成：绿色勾选，“全量扫描完成 · 128 个仓库 · 642 个分支 · 1,286 次提交 · 行数统计 98.2%”。
- 部分完成：黄色警告，显示失败仓库/diff/截断数量，入口打开失败列表。
- 失败/中断：红色提示，显示原因和重试按钮。
- 缓存时间：`数据缓存 10:32`。
- 右侧“查看扫描范围”打开 `CodeScanScopeDrawer`。

### 3.2 重扫

- 所有团队成员可见“重新扫描”；个人页本人可见。
- 点击后确认“将全量扫描全部仓库和分支，可能耗时较长”。
- 调用 `POST /scan`，`mode=FULL`。
- 返回 `ALREADY_RUNNING`：展示当前进度，不重复提示。
- 返回 `COOLDOWN/USER_COOLDOWN`：按钮禁用，tooltip 显示剩余时间。
- 返回 `QUEUE_FULL`：提示稍后重试。
- 扫描中每 3 秒轮询 `GET /scan`；页面隐藏或组件卸载时停止。
- 完成后刷新 `statistics` 和 `member-statistics`。

### 3.3 当前月自动刷新

- 后端在 `GET /statistics` 中自动触发增量；前端只处理 `refreshing=true`。
- `refreshing=true` 时在页面右上角显示“刷新中”，保留旧数据。
- 轮询 `GET /scan`，完成后重新取统计。

## 4. 我的代码页面

### 4.1 布局

```text
Header（Tab + 时间范围 + 我的 Git 身份 + 重扫 + 缓存时间）
KPI 第一行
扫描状态条
每日提交频率（大） | 提交日历热力图（小）
代码行变更趋势（大） | 提交时段分布（小）
仓库提交频率（半） | 分支活跃度 Top 10（半）
提交明细表
```

### 4.2 KPI

`CodeStatsKpiCards.vue`：

| 卡片 | 字段 |
|---|---|
| 提交次数 | `summary.commitCount` |
| 新增代码行 | `summary.additions`，绿色 |
| 删除代码行 | `summary.deletions`，红色 |
| 净增代码行 | `summary.netLines`，负数带符号 |
| 活跃天数 | `summary.activeDays` |
| 涉及仓库 | `summary.repoCount` |

### 4.3 每日提交频率

`CodeStatsBarChart.vue`：

- x 轴日期，y 轴提交次数。
- `daily[]` 数据；无提交日期由后端补 0。
- 周末/休息日底色：`dayType === 'REST_DAY'`。
- 柱状图使用 SVG；支持横向滚动（31 天）。
- 点击柱/日期触发 `selectedDate`，过滤提交明细。

### 4.4 代码行变更趋势

`CodeStatsLineBarChart.vue`：

- 绿色新增柱、红色删除柱；净增折线或顶部数值。
- x 轴日期；y 轴支持负值。
- Tooltip：日期、提交次数、新增、删除、净增。
- 与每日提交频率共享日期选择。

### 4.5 提交日历热力图

`CodeStatsCalendarHeatmap.vue`：

- 7 行（周一至周日）× 周数；按 `daily[].commitCount` 着色。
- 颜色分档 0/1-2/3-5/6-10/11+；空档浅灰。
- Tooltip：日期、提交次数、新增/删除。
- 可用 CSS Grid + `background` 实现，不新增图表库。

### 4.6 提交时段分布

- 使用个人 `statistics` 响应中的 `hourly[]`（固定 24 项，0-23 点提交次数）。
- 图表：24 根柱，x 轴 `0-23`，tooltip 显示提交次数。
- 数据已在服务端聚合，不基于提交明细分页计算。

### 4.7 仓库提交频率

`CodeStatsHorizontalBarChart.vue`：

- `repositories[]` 按 `commitCount` 降序，默认显示 Top 10。
- 每行：仓库名、提交次数、新增/删除（tooltip 或右侧小字）。
- 点击行设置 `depotId` 过滤提交明细。
- 可展开“查看全部仓库”。

### 4.8 分支活跃度 Top 10

- `branches[]` 按 `commitCount` 降序 Top 10。
- x 轴为提交次数，y 轴为分支名；横向条形图。
- 分支名过长截断，tooltip 显示全名。
- 同一提交多分支会在每个分支计数；页面提示“分支活跃度按分支计次”。

### 4.9 提交明细表

`CodeCommitTable.vue`：

- 列：时间、仓库、分支、提交说明、新增、删除、修改文件数、短 SHA。
- `branches` 展示前 2 个 + `等 N 个`；点击展开完整列表。
- 短 SHA 链接到 `commitUrl`；新窗口 `rel="noopener noreferrer"`。
- 提交说明纯文本，超长省略 + tooltip。
- 筛选：日期（来自图表点击）、仓库、关键词、分页。
- 空数据、加载、失败、diff 状态为 `FAILED/TOO_LARGE` 时显示 `—` 与提示。

## 5. 团队代码页面

### 5.1 布局

```text
Header（Tab + 团队 + 时间范围 + 重扫 + 缓存时间）
KPI 第一行
扫描状态条
团队提交频率（x 轴成员） | 团队代码行变更（x 轴成员）
指定成员分析（成员下拉 + 每日提交频率 + 代码行变更趋势）
未归属作者入口/抽屉
```

明确不渲染：团队活跃人数趋势、仓库/分支活跃度、成员热力图、成员贡献对比、团队提交明细。

### 5.2 KPI

| 卡片 | 字段 |
|---|---|
| 提交次数 | `summary.commitCount` |
| 新增代码行 | `summary.additions` |
| 删除代码行 | `summary.deletions` |
| 净增代码行 | `summary.netLines` |
| 活跃成员 | `summary.activeMembers / summary.memberCount` |
| 未归属提交 | `summary.unmappedCommitCount`，琥珀色；点击打开未归属抽屉 |

### 5.3 团队提交频率

`CodeStatsMemberBarChart.vue`：

- x 轴成员姓名，y 轴提交次数。
- `members[]` 已按提交次数降序，0 提交在末尾。
- 成员头像可选展示在姓名上方；成员多时横向滚动、姓名旋转。
- 点击成员柱设置 `selectedMemberId`，联动指定成员分析。
- 默认选中 `members[0]`（提交次数最高）；若全部为 0，选中第一位成员。

### 5.4 团队代码行变更

`CodeStatsMemberDivergingChart.vue`：

- x 轴成员姓名，y 轴新增/删除行。
- 每个成员绿色新增、红色删除；可用正负柱或并排柱。
- Tooltip：成员、提交次数、新增、删除、净增。
- 点击成员柱联动指定成员分析。
- 成员多时横向滚动。

### 5.5 指定成员分析

`CodeStatsSelectedMemberPanel.vue`：

- 成员下拉，选项来自 `members[]`。
- 选中后调用 `GET /member-statistics`。
- 左侧“每日提交频率”：日期 x 轴，提交次数柱状图。
- 右侧“代码行变更趋势”：日期 x 轴，新增/删除柱 + 净增线。
- 加载时保留旧图 + skeleton；失败可重试。
- 成员切换请求带序号，忽略迟到响应。

### 5.6 未归属作者

`CodeUnmappedDrawer.vue`：

- 打开时调用 `GET /identities?scope=WORKBENCH_TEAM&workbenchTeamId=...`。
- 展示 `candidates[]`：作者名、提交次数、仓库数、最近提交、建议成员。
- 管理员可“绑定到成员”或“忽略”；普通成员只读。
- 绑定后刷新统计、未归属数量、成员图。
- 绑定/改绑后如触发 `DIFF_BACKFILL`，显示“行数统计补齐中”，轮询 `GET /scan`，完成后刷新图表。

### 5.7 团队身份映射

`CodeIdentityDialog.vue`：

- 管理员入口：团队成员、来源、身份类型、作者名、操作。
- 手动绑定使用候选 `identityKey`，不输入邮箱。
- 忽略/取消忽略、删除手动映射。
- 操作后刷新 `GET /identities` 和统计。
- 普通成员不显示编辑按钮。

## 6. 我的 Git 身份

`CodeIdentityDialog.vue` 的个人模式：

- 展示已绑定身份（脱敏展示，不返回完整邮箱）。
- 新增：选择邮箱/姓名，输入值；保存后显示匹配提交数。
- 删除本人身份；删除后统计刷新。
- 提示“邮箱只保存哈希，不保存明文”。
- 个人身份绑定不需要管理员。

## 7. API 与类型

新增：

```text
src/types/zhaogangCodeStats.ts
src/api/zhaogangCodeStats.ts
```

API：

```ts
getCodeStatsOptions()
getCodeStatsStatistics(params)
getCodeStatsMemberStatistics(params)
getCodeStatsEntries(params)
getCodeStatsScan(params)
triggerCodeStatsScan(body)
cancelCodeStatsScan(jobId)
getCodeStatsFailures(jobId, params)
getCodeStatsIdentities(params)
createCodeStatsIdentity(body)
updateCodeStatsIdentity(id, body)
deleteCodeStatsIdentity(id)
```

- 复用 [api/zhaogang.ts](../../../../iw-mixes-web-platform/src/api/zhaogang.ts) 的 `zhaogangRequest`，保持 `credentials: 'include'` 和权限提示。
- 类型与 `api-contract.md` 字段一一对应；不要使用 `any`。
- 分页响应统一 `{ pageNumber, pageSize, totalRow, totalPage }`。
- 扫描状态类型使用字符串联合，不使用魔法字符串散落页面。

## 8. 图表实现

- 项目当前没有 ECharts 依赖；工时图表是手写 SVG（[WorklogTrendChart.vue](../../../../iw-mixes-web-platform/src/views/zhaogang/components/worklog/WorklogTrendChart.vue)）。
- 代码统计优先沿用 SVG/CSS：
  - 柱状图/折线图：SVG，带 `viewBox`、横向滚动容器。
  - 横向条形图：SVG 或 CSS Flex。
  - 日历热力图：CSS Grid。
  - 成员多时横向滚动，不压缩到不可读。
- 不新增图表库，除非实施时确认 SVG 实现成本不可控并单独获得依赖授权。
- 每个图表：
  - `role="img"` + `aria-label`。
  - Tooltip 使用纯文本。
  - 空数据展示空状态，不渲染坐标轴假数据。
  - 图例颜色固定：新增 `#67C23A`、删除 `#F56C6C`、提交 `#409EFF`、未归属 `#E6A23C`。
- 数字格式化：千分位；净增负数带 `-`；百分比保留 1 位小数。

## 9. 页面状态

每个页面必须覆盖：

- 加载会话/选项：骨架屏。
- 无 CODING 会话：沿用工作台绑定提示，不重复实现。
- 团队页面无团队：提示加入/创建团队。
- 范围无缓存/扫描中：进度条 + 阶段文案 + 已用时间；不显示空图表。
- 扫描失败/部分失败：保留已有数据，显示 coverage 和失败入口。
- 无提交：空状态“当前范围没有已归属提交”，说明扫描范围和身份匹配。
- 权限不足：沿用 `ZhaogangPermissionPrompt`。
- 请求失败：可重试；切换范围/成员时忽略迟到响应。
- 只读：普通成员不显示团队映射编辑；个人页不显示他人数据。
- 重扫冷却：按钮禁用 + 剩余时间 tooltip。

## 10. 可访问性与交互细节

- 侧栏“代码”有 `aria-label` 和收起态 `title`。
- Tab 使用可聚焦按钮，支持键盘切换。
- 图表 tooltip 不依赖 hover 才能获取关键数据；KPI 和表格提供等价文本。
- 颜色不是唯一信息载体：新增/删除同时有图例文字和正负号。
- 提交链接 `target="_blank"` + `rel="noopener noreferrer"`。
- 长时间扫描提示“可以离开页面，扫描会在后台继续”。

## 11. 验证

- `npm run type-check`。
- `npm run build`。
- 浏览器检查：
  - `/zhaogang/code/mine`
  - `/zhaogang/code/team`
  - 四个时间范围选项。
  - 扫描中、完成、部分失败、冷却、无数据、无团队、权限不足。
  - 成员柱点击联动、0 提交成员、成员多时横向滚动。
  - 未归属作者映射/忽略。
  - 我的 Git 身份添加/删除。
- 发布前 `npm run check:zhaogang-release-notes`。
