# CODING 洞察与工作台统计性能：2026-11-03 调研

状态：只读调研完成，方案待选择；未修改业务代码、未执行建表/迁移、未重启本地服务、未提交推送，也未更改 CODING 视图或令牌权限。本人现有 PAT 只用于只读探测，Cookie/认证头没有导出或写入资料。

## 结论与建议

当前工作台以“用户查看月份时构建该月提交数据”为主链路；CODING 洞察以服务端统计数据集查询为主链路。身份过滤只减少落库和 diff，并没有消除仓库/分支枚举及提交分页读取，因此首次切换没有准备数据的月份仍慢。

推荐先做“CODING 统计聚合接口 + OAuth 生态度量只读权限”的最小接入验证；验证成功后，核心 KPI/按日图表走聚合查询，Git 遍历用于后台明细补齐/核对。若授权或接口能力不支持已确认口径，则建设本地持续同步及日聚合数据层。两种路线均应把查询与同步解耦，切换月份只查询已有统计，不现场启动全月数据重建。

## 一、洞察页的真实行为

通过用户已登录页面 https://g-iijw5014.coding.net/insight/detail?viewId=315761 的 Chrome 原生 Network 面板观察，只读取请求 Payload、Preview 和耗时，未读取/导出认证 Header/Cookie，未导出 HAR。

一次刷新首屏实测：

| 读取 | 结果 | 耗时 |
| --- | --- | --- |
| /api/insight/view/detail?id=315761 | 视图配置，HTTP 200 | 429 ms |
| /api/insight/chart/detail-v2?chartId=631829 | 代码提交频率，HTTP 200 | 869 ms |
| /api/insight/chart/detail-v2?chartId=631830 | 代码行变更趋势，HTTP 200 | 869 ms |

图表请求并行；Network 概览窗口约 1.6 秒。该数字只覆盖当时首屏两图，不宣称整个视图所有图表或首次全月查询都已测到相同延迟。用户截图为 2026-10-26~30；刷新时服务端相对日期已是 2026-11-02~03，不能把不同范围当整月性能对照。

图表请求体：chartId、demo=false、filters=[]。filters=[] 是“没有额外覆盖”，不是没有统计筛选；服务端保存的图表配置中有：

- datasetId：CODING_CODE_COMMIT。
- author 过滤参数为当前 CODING 用户 ID 9292850，operator=2（IN）。
- date 过滤参数为 6，operator=1（Time）。当前发布前端枚举 CurWeek="6"，CurMonth="7"。
- 提交频率的 metrics 为 commit_count，dimensions 为 date（dateInterval=1）。
- 返回 columns、list、dataModel；list 已经是按日聚合，dataModel 已有图表轴标签。
- 代码行图 metrics 为 commit_insertions、commit_deletions，响应直接带每个日期的增删行数。

当前周实测返回 2026-11-02：提交 1 次、新增 12、删除 7；与本地同日对账一致。用户截图 2026-10-26~30 的 6/8/4/8/5 次提交合计 31，与本地这五天逐日记录相同；这只证明样本一致，不能代替整月、全部仓库及自定义身份的完整口径验证。

事实：前端消费的是服务端聚合结果，页面没有先逐仓库/分支拉 Git 明细。推断：CODING 内部已有统计/索引数据层，数据采集和看图查询分离。未知：其具体数据库、离线/实时处理方式、增量机制及数据刷新 SLA；不凭前端响应断言使用了 ClickHouse/ES 或已实时预计算所有组合。

## 二、发布前端静态证据

从当前页面资产清单发现并下载公开 CDN 脚本，不使用 Cookie；HTTP gzip 解码后作为惰性文本分析，未执行 bundle。

- https://assets.codehub.cn/micro-frontend/insight/view-sdk-0cb9fca80a.js
- https://assets.codehub.cn/micro-frontend/insightChart-195ad837-c560349a.js
- https://assets.codehub.cn/micro-frontend/insightChart-f352087a-1dfec153.js

脚本中分别定义视图详情、图表详情 v2、统计预览、统计指标预览、数据下钻 API。调用代码对每个图表读取 detail-v2，并依据 metricConfig.metrics、dimensions、groups、filters 构造统计配置，消费列和图表模型；数据下钻另外读取 data-detail。不存在把这组接口直接等同公开 OpenAPI 的依据。

REA 静态父证据：ev_671e1acdf17167f68772a724599c8082f58c5f420bd4e832185619e961c943d2；投影 ev_197ef29ce3f6c77d3d8b85c9adb4a9ef66a695294dd37aca8800331958dc426e。解析 3 个 JS，未出现 UTF-8/语法失败；语义图因 minified bundle 和节点预算部分截断，不以“没有抽取到某关系”证明该行为不存在。

REA 的 CDP 实时入口不可达，因此网络运行事实来自 CUA 操作的 Chrome 原生 DevTools，不冒充 REA runtime Evidence。下载最初为 gzip 字节，第一次 REA 记录不含可用 JS 解析结果；有效证据是解压后的上述第二次分析。原始与解码 SHA-256 见 [资产清单](insight-research-20261103-evidence/asset-manifest.json)。没有把原始第三方脚本复制到仓库。

## 三、现有 PAT 是否可直接调用

已做本人现有 PAT 的最小只读探测：

1. 从本地既有绑定读取本人 9292850 的令牌，仅存在进程内存，不出现在命令参数、标准输出或文档。
2. 不带 Cookie 调用官方 /open-api 的 DescribeCodingCurrentUser，确认确为本人，成功。
3. 同一 PAT、不带 Cookie，对已观察的 detail-v2 发相同只读请求：HTTP 200，但业务 code=3015，oauth_user_scope_error：“当前的 scope 不支持访问此 API”，无统计 data。

所以已证实“当前 PAT scope 不足”，不能把它解释成令牌失效，也不能因 HTTP 200 就判断接入成功；尚未证明任何 PAT 都不支持，更不能据此宣称接口只支持网页登录 Cookie。

2026-11-03 再核对 [官方 OpenAPI](https://coding.net/help/openapi) 的 [当前 YAML](https://assets.codehub.cn/document/openapi/353b949e0b605519499f4c684f185b2649231b9f.yaml)：SHA-256 4464a3266cbb631eb93c2aa0fd3e75786aaab3fc3036a2618e3e42d31ac6a89d。paths 中没有“效能洞察相关接口”的操作；该名称仅是顶层分类，不能误认为已公开可调用的图表 Action。

重要线索：OAuth 认证下的“生态令牌权限”表列有“研发度量数据集”，performance:dataset，ro，完整 scope=performance:dataset:ro。这是 OAuth 生态权限说明；本次没有证据说个人 PAT 设置中一定有该选项，也没有验证它是否正好覆盖 detail-v2。没有擅自新增权限、创建令牌/应用或读取其他成员 PAT。

## 四、工作台当前瓶颈（源码事实）

- DefaultCodeStatsModule.statistics 先调用 autoRefresh，查询动作与同步调度相连。CodeScanService.autoRefresh 对未准备的月份创建 FULL，身份指纹变更也创建 FULL。
- CodeScanWorker.collectCommits 对 FULL 和 INCREMENTAL 均从自然月第一天读取；“增量”主要依靠分支头快照复用，不是基于已同步提交游标只取新增 SHA。
- snapshotKey 同时包含身份指纹、范围、仓库、月份、截止日和分支头 SHA。切到另一月无法复用原月快照；当前月跨天导致截止日变化，已有相同 head 的缓存也会键失配。
- 只把命中身份提交落库，但仍须先读取可读仓库全部分支的元数据才能匹配。这是个人视图也要遍历大量分支的原因。
- 原 2026-11-02 实测：232 仓库、约 9574 分支；新口径冷扫描任务 21 为 9037 次提交分页、847 秒（PARTIAL）；任务 23 热复扫 53 次分页、55 秒（COMPLETED）。这些是当时真实记录，不是本轮再跑的测速，也不是所有请求数。

相关源码：CodeScanWorker.java:284、387、404；CodeScanService.java:118；DefaultCodeStatsModule.java:89。

## 五、建议路线

### A. 优先验证只读聚合数据源

先用受支持的授权方式验证 OAuth 生态度量只读 scope 和聚合接口的可用性。保持现有 PAT 不变，不采集/持久保存浏览器 Cookie，不要求全权限令牌。

最小验证范围：仅本人、2026-10 自然月；提交次数、按日新增/删除，核对每月及每天样本、错误处理和查询耗时。成功判据为 HTTP 与业务 code 均成功，聚合结构完整，口径对账成立；秒级为需要实测的目标，不能拿当前周 869 ms 保证任意月份。

通过后：工作台按日期查询聚合 KPI 和图表，短期缓存；团队按用户分组查询；Git 扫描退为后台明细补齐和核对。是否支持按小时、分支 Top10、SHA 明细、文件数，需要单独核对 data-detail 与数据集维度。

仍须维持已经确认的规则：姓名/邮箱多个 Git 身份、自定义别名、合并提交排除、仓库/SHA 去重、online.base.apijar.service 只计次数不计行数，以及 PAT 仓库可见范围。当前聚合接口按平台用户 ID 筛选，不能假设自动覆盖工作台的自定义身份集合；仓库、合并过滤维度也尚未验证。任何不等价的部分必须显式保留原能力，不能静默替换口径。

### B. 若授权或口径不满足，建设本地持续同步及聚合层

- 首次绑定时后台回补所需历史范围，一次读取覆盖多个月，避免逐月重新遍历同一批分支。
- 按仓库/分支记录已同步 head 与游标，识别新 head，仅补增量；处理强推、回填时间及新分支，不能把分支尖端提交日期当所有历史提交的上界。
- 按仓库+SHA 复用匹配提交和 diff；个人/团队聚合复用同步工作，身份与权限仍独立检查，不绕过访问范围。
- 查询侧使用按日期、成员索引的提交表及日聚合结果。切月份纯本地读取，不同步枚举上游。
- 当前月后台周期同步；若需要推送事件，仅在另行授权后配置 Service Hook，不在本次调研注册回调。
- 新增身份因没有保存其他作者明细，仍需后台回补；删除即时排除。向用户说明数据同步时间和覆盖范围，不能将“尚未同步”伪报为零提交。

### C. 两条路线共用的交互规则

切换月份只查询结果，已有数据立即展示；后台同步以数据时间/同步状态表达，不让整页长期显示扫描中。没有数据时区别“未覆盖此范围”和“已同步且零提交”。明细/分支类图表可独立加载，核心 KPI 不被最慢的补数任务阻塞。

这是架构调整；单纯增大线程数或再加一层月缓存，无法消除未知月份的上游遍历成本。保留当前全量扫描作为校验/人工刷新后备，而非默认查看链路。

## 六、待验证边界与本轮改动

已回答：当前工作台慢的调用结构；洞察真实查询与返回结构；当前 PAT 访问失败原因；可选改造方向。

未验证：OAuth 度量只读 scope 对 detail-v2 的授权覆盖；该接口的公开稳定性承诺；整月查询耗时与完整口径；CODING 内部存储引擎与刷新 SLA。因此路线 A 是优先做最小验证的推荐，不是已接入完成。

本轮只增加此报告和脱敏证据 JSON，不改业务实现、不跑新全月扫描、不改库或权限。操作时用户切换了浏览器窗口，随后停止原生浏览器动作；未操作用户其他业务页面或保存视图编辑。

证据：[浏览器观察](insight-research-20261103-evidence/browser-observations.json)、[当前 PAT 脱敏探测结果](insight-research-20261103-evidence/own-pat-result.json)、[官方 scope 摘要](insight-research-20261103-evidence/official-scope-summary.json)。
