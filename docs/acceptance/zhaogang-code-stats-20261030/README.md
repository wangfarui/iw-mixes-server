# 找钢工作台代码统计实施与自验记录

日期：2026-10-30。主仓库：`iw-mixes-server`；Web：`iw-mixes-web-platform`。设计依据：[交接包](../../plans/zhaogang-code-stats-20261030/README.md)。

## 1. 结论

- 代码实施：后端、Web、文档和发布清单已完成，未提交、未推送、未发布。
- 自动验证：后端 `iw-external` 全量 **255** 个测试通过；Web 类型检查、生产构建、版本清单校验和代码统计范围测试通过。
- 真实链路验证：**未执行**。本会话未获得数据库写入、启动/重启 `iw-external`、真实成员 PAT 扫描授权；`iw-external` 保持 stopped，未写 MySQL、Redis 业务数据，未调用真实 CODING 数据。
- 浏览器验证：使用本地同源模拟 `iw-external`（`127.0.0.1:18006`）完成 UI 交互检查。模拟数据和示意图不构成真实接口验收。
- 因此结论为：**实施完成，真实环境验收未完成**。

## 2. 环境与版本

| 项 | 值 |
| --- | --- |
| 工作区 | `/Users/wangfarui/workspaces/wfr/iw-mixes_ai` |
| `iw-mixes-server` HEAD | `0d6d301`（`main`，未提交本次改动） |
| `iw-mixes-web-platform` HEAD | `d821532`（`dev`，未提交本次改动） |
| Java | OpenJDK 17.0.9（JBR 17.0.9+8-1162.4-nomod） |
| Maven | Apache Maven 3.8.6，`/usr/local/maven/repository` |
| Node / npm | v18.17.0 / 9.8.1 |
| Vite | 5.4.21 |
| 已有后端 | `iw-core` PID 69793，端口 18000；`iw-external` stopped，端口 18006 |
| 模拟服务 | Node HTTP mock，`127.0.0.1:18006`，经 `iw-core` 18000 兼容入口访问 |
| Web dev | `http://127.0.0.1:5173` |

## 3. 自动验证

| 命令/范围 | 结果 |
| --- | --- |
| `mvn -pl iw-packaging-parent/iw-external -am test` | **255 tests, 0 failures, 0 errors, 0 skipped**；BUILD SUCCESS（2026-10-30 18:45 +08） |
| 新增后端测试 | `CodingOpenApiClientTest`（34，含仓库/提交/diff/超大响应）、`CodeRangeTest`（6）、`CodeIdentityResolverTest`（5）、`CodeStatsQueryServiceTest`（3）全部通过 |
| `npm run type-check` | 通过 |
| `npm run build` | 通过（Vite build 15.11s；仅既有 chunk 体积提示） |
| `npm run check:zhaogang-release-notes` | 通过，`zg-2026-10-30-01`，30 条版本记录 |
| `npm run test:zhaogang-code-stats-range` | 通过（本周、本月、指定月、31/32 天、未来、倒序、24 个月保留、日期禁用） |

说明：`-DskipTests compile` 只证明编译；本轮执行了全量测试，未用 H2/mock 替代 MySQL 行为验收。

## 4. 浏览器与交互验证（模拟接口）

打开 `/zhaogang/code/mine`、`/zhaogang/code/team`、`/zhaogang/code`、`/zhaogang`、`/zhaogang/settings` 检查：

- `/zhaogang/code` 重定向到 `/zhaogang/code/mine`；侧栏“代码”激活；默认首页选择“代码”后访问 `/zhaogang` 自动进入我的代码。
- 时间范围：本周显示 `2026-10-26 ~ 2026-10-30`；指定月显示月份选择器；自定义显示日期范围并提示“请选择时间范围”；31 天通过、32 天提示“最多选择 31 天”、未来日期提示“不能选择未来日期”（范围纯函数实际执行）。
- 我的代码：KPI、每日提交频率、日历热力图、代码行变更趋势、提交时段分布、仓库提交频率、分支活跃度 Top 10、提交明细均渲染；点击热力图日期后明细标题变为 `2026-10-15` 并出现日期筛选标签；点击仓库后出现仓库筛选标签；分支“等 3 个”可展开完整分支名；未匹配身份提示显示“有 2 次提交未匹配到你的 Git 身份”并可打开身份弹窗。
- 团队代码：团队选择、KPI、团队提交频率/代码行变更两张成员 x 轴图、0 提交成员王强、指定成员分析；点击王强柱后下拉变为“王强 · 0 次”，验证成员柱联动。
- 身份映射弹窗：类型/身份/来源/归属成员/操作列正确显示 EMAIL + AUTO 数据，管理员可改成员或忽略；我的 Git 身份弹窗显示邮箱/作者名输入和已绑定身份。
- 未归属作者抽屉：展示候选、提交次数、仓库数、最近提交、建议成员，管理员可绑定/忽略。
- 扫描范围与失败明细抽屉：月份进度、覆盖统计、成员覆盖提示、仓库读取失败、失败明细可见。
- 安全：提交说明中的 `<img src=x onerror=alert(1)>` 按纯文本渲染，表格中无 `img` 元素；提交链接 `target="_blank"`、`rel="noopener noreferrer"`。
- 重扫：确认弹窗正常；模拟返回冷却时提示 `2026-10：COOLDOWN`，未出现页面崩溃。
- 网络：代码页相关请求均返回 200；代码页无新增控制台错误。

限制：以上为模拟数据，未验证真实 CODING 权限、真实 MySQL 聚合、真实扫描进度、真实并发/冷却、跨团队越权或真实 XSS 攻击链路。

## 5. AC 对照

| 编号 | 结果 | 说明 |
| --- | --- | --- |
| AC-01 | 部分执行 | 前端范围解析和错误提示已执行；后端接口校验未用真实服务执行 |
| AC-02 | 未执行 | 需要真实数据库扫描和跨月数据 |
| AC-03 | 未执行 | 需要多个真实成员 PAT |
| AC-04 | 未执行（UI 部分） | 0 提交成员 UI 已验证；真实分支/多分支去重未验证 |
| AC-05 | 未执行 | 需要真实仓库合并提交与数据库 |
| AC-06 | 未执行 | 需要真实 diff 样本对账 |
| AC-07 | 未执行 | 需要真实扫描、Redis 冷却和当前月增量 |
| AC-08 | 未执行 | 需要真实扫描、取消、重启和心跳 |
| AC-09 | UI 部分执行 | 模拟接口下页面/图表/明细/空错状态结构已检查；真实接口未验收 |
| AC-10 | UI 部分执行 | 模拟接口下两张成员图、0 提交成员和联动已检查；真实接口未验收 |
| AC-11 | 未执行 | 需要真实 Git 邮箱/姓名和 DIFF_BACKFILL |
| AC-12 | 未执行 | 需要真实普通成员/管理员/非成员会话 |
| AC-13 | 部分执行 | HTML 文本渲染和外链属性已验证；跨团队/跨用户数据隔离未执行 |
| AC-14 | 未执行 | 需要真实 128 仓库/642 分支扫描性能验证 |
| AC-15 | 未执行 | 需要隔离 MySQL 执行保留清理 |
| AC-16 | 通过 | 后端测试、Web 类型检查/构建、发布清单和范围测试通过 |

## 6. 改动范围

### 后端 `iw-mixes-server`

- CODING 适配：`CodingOpenApiPort`、`CodingOpenApiClient`、`CodingOpenApiException`、`CodingRequestLimiter`、`CodingResponseTooLargeException`、`ZhaogangProperties`。
- 新增 `zhaogang/codestats/`：范围、扫描任务/工作线程、仓库发现与分支/提交/diff、身份解析与映射、查询聚合、Redis 缓存、扫描状态/冷却/重试、DIFF_BACKFILL。
- 新增 Controller：`ZhaogangCodeStatsController`；异常处理加入 `ZhaogangExceptionHandler`。
- 新增 Mapper/实体：4 张代码统计表及 `ZhaogangCode*Mapper`。
- SQL：`iw-external-init.sql` 追加 4 张表；新增 `iw-external-migrate-20261030-code-stats.sql`（未执行）。
- 测试：`CodingOpenApiClientTest` 扩展、`CodeRangeTest`、`CodeIdentityResolverTest`、`CodeStatsQueryServiceTest`。
- 注释：`CodingCredentialService` 和凭证表注释扩展为“团队工时 + 团队代码统计”。

### Web `iw-mixes-web-platform`

- 类型/API：`src/types/zhaogangCodeStats.ts`、`src/api/zhaogangCodeStats.ts`。
- 范围纯函数：`src/services/zhaogangCodeStatsRange.ts`、`scripts/testZhaogangCodeStatsRange.mjs`。
- 页面：`src/views/zhaogang/code/ZhaogangCodeMineView.vue`、`ZhaogangCodeTeamView.vue`、`useCodeStatsScanPolling.ts`、`codeStatsPresentation.ts` 及图表/表格/身份/未归属/扫描范围组件。
- 壳层：`ZhaogangWorkbench.vue`、`router/zhaogang.ts`、`types/zhaogang.ts`、`services/zhaogangPreferences.ts`。
- 发布：`public/zhaogang-release-notes.json` 新增 `zg-2026-10-30-01 / 1.11.0`。

### 资料

- `context/zhaogang-workbench-product-knowledge.md`
- `context/zhaogang-workbench-technical-reference.md`
- `context/zhaogang-workbench-operations-and-verification.md`
- 交接包 `docs/plans/zhaogang-code-stats-20261030/` 保留，未修改设计结论。

## 7. 数据与残留

- **未写 MySQL/Redis 业务数据**；未执行建表、迁移、扫描或清理 SQL。
- **未调用真实 CODING**；未创建、修改或删除任何 CODING 资源。
- **未提交、未推送、未发布**。
- 浏览器模拟服务、Vite dev、临时 mock 脚本和日志均为本会话临时产物；验证结束后停止，仓库内无 mock 文件、无测试数据。
- `iw-core` PID 69793 为会话开始前已运行的服务，未重启；`iw-external` 保持 stopped。
- `dist/` 由构建重新生成，属于既有构建产物，不纳入源码改动。

## 8. 下一步

获得明确授权后，按 AC-01 至 AC-15 在隔离测试库和只读测试 PAT 下执行：建表迁移、小范围真实扫描、diff 对账、并发/冷却/取消恢复、成员权限和跨团队隔离；完成后再决定提交、推送和发布。

待执行脚本、命令、前置/后置断言和授权清单已整理为 [本地验收交接](../../plans/zhaogang-code-stats-20261030/local-acceptance-handoff.md)，供新会话在本地自动验收。
