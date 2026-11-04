# 持续同步状态与失败明细范围修复：2026-11-04

## 原因与改动

本人本地任务 41 同步 2026-01-01 至 2026-11-04，仓库失败 0、当轮 diff 失败 1，状态 PARTIAL。持久数据另含 3 条 TOO_LARGE：六月 1 条、八月 2 条，普通 FAILED 在七月 1 条。十一月无未完整行数。此前月份直接继承全年任务 PARTIAL；失败接口却按任务 month=2026-11 查询，导致告警与空列表矛盾。

- `CodeScanService`：仅在持续同步元数据已完整、任务已结束时，按当前查询日期计算行数完整性。仓库读取失败、缺少成员 PAT、截断、运行中或未确认原因的 PARTIAL 保守保留。旧 FULL 月任务兼容原状态。
- `ZhaogangCodeCommitMapper`：按实际日期范围读取失败及计算 PENDING/FAILED/TOO_LARGE；排除合并提交及精确名称的 apijar 特殊仓库。不需要数据库迁移。
- `CodeStatsModels`：同步信息新增任务 ID、仓库失败及未完整行数计数；失败响应带实际任务范围、每条提交日期，保留旧构造兼容。
- `DefaultCodeStatsModule`：更新查询缓存版本，避免旧结果继续返回。
- Web `CodeStatsScanBanner`、`codeStatsPresentation`：当前范围状态与后台全年告警分开展示，不将历史 diff 说成当前月仓库失败。
- Web `CodeScanScopeDrawer`、types：标明任务进度与失败列表是后台范围，显示日期；任务切换或结束时刷新明细，避免旧列表及迟到响应。

## 验证

| 场景 | 真实结论 / 证据 |
| --- | --- |
| 修复前回归 | CodeScanRangeStatusTest 失败：expected COMPLETED but was PARTIAL，非编译失败 |
| 后端全模块测试 | Maven `-pl iw-packaging-parent/iw-external -am test`，343 项，失败/错误/跳过 0 |
| Web 检查 | type-check、build、check:zhaogang-release-notes、test:zhaogang-code-stats-range 全部通过；build 原有 chunk/pdfjs 警告 |
| 十一月真实绑定态页面 | 当前范围已同步，11 次提交、1234/156 行，覆盖率 100%、失败 diff 0；后台全年提示 4 条未知行数 |
| 失败明细真实接口/页面 | 2026-01-01 至 11-04，4 条记录均显示仓库、短 SHA、六月/七月/八月提交日期及原因，不再为空 |
| 历史七月页面 | 仍 PARTIAL，显示当前查询范围有 1 条提交的代码行未完整统计 |
| 元数据失败与旧月任务 | 回归覆盖仓库失败、截断、运行中、旧 FULL 范围、已有 TOO_LARGE；不宣称已做这些异常的实网注入 |
| 查询不触发扫描 | 页面月份切换后仍为任务 42；42 为实例启动后按既有订阅发起的正常后台同步 |

截图已检查无凭证：[真实本月与全年明细](range-status-fix-20261104.png)。机器摘要：[JSON](range-status-fix-20261104.json)。

## 环境与边界

后端 HEAD `dfeca1e63fd3c51dd7dbe30d0f319b739020a036`，Web HEAD `e7f23e8d7c356d81fbaec291a8ad2befd0a7c767`；本次修复未提交。Java 17.0.9，Maven 3.8.6，独立 settings 与仓库 `/usr/local/maven/repository`；Node 18.17.0，Vite 5.4.21。隔离 JAR 构建于 11:11:34+0800，SHA256 见 JSON；由 `scripts/local-backend.sh` 启动在 18026，Vite 5175 代理该实例。未覆盖运行中的原 18006 JAR，原端口仍需重启才加载这次改动。

真实任务 42 于 11:13:59 结束，232 仓库、9606 分支、1478 条含合并记录，提交分页 3 次、复用头 9603 个、重试 diff 1 条；仓库失败 0、diff 失败 1，全年状态保持 PARTIAL。本人原有只读 PAT 在服务端使用，未导出 Cookie/PAT/token。未创建身份夹具、未修改共享库、未清库、未提交或发布。保留正常同步任务 42 与真实统计数据用于后续查询，无额外测试提交数据。
