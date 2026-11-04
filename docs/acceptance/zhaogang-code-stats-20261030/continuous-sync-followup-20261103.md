# 持续同步稳定分支续验：2026-11-03

本轮继续使用本人只读 PAT，在本地执行，未提交、推送、发布或修改共享库。上午记录见 [初验](continuous-sync-20261103.md)。

## 已观察的首次回补

任务 29 于 11:05:11 开始、13:06:05 结束，耗时 7254 秒；232 仓库、9584 分支、提交分页调用 33307 次。保存 1456 条身份命中记录（含合并提交），拉取 601 条待统计 diff。结果 PARTIAL：5 个仓库的 ref 在读取期间变化，4 条 diff 被上游拒绝。已经成功的数据可查看，不因局部失败丢弃。

- 5 个仓库为 online.base.outer.service、online.base.saasfunds.service、online.base.saasbusinesscenter.service、online.base.process.service、pms.front.erppsi.ui，错误为 BRANCH_CHANGED。
- 227 个仓库有有效游标，合计压缩约 2.21 MB。下一轮能复用这些范围。
- 已完成的一月至十月均有本人匹配结果；真实一月页面为 38 次提交、新增 1427、删除 361，切换不启动新任务。十月仍为 98 次、8208/2302 行。

## 本轮修正

`CodeScanWorker` 从“仓库有任一分支变化就不保存全仓库游标”，改为逐分支复核，仅发布已完整读取且头稳定的分支。未验证分支不冒充完成；部分仓库不清理旧行。整仓库元数据完整后清理当前不可达记录；全年元数据完整时清理已不在范围的仓库记录，行数不可用不阻止元数据范围清理。

新增行为验证覆盖：同仓库 main 在扫描期间变化、release 稳定，第一轮 PARTIAL 仍保存 release 的游标，下一轮只读取 main，最后保留两条分支的正确归属。另将上游明确“diff 修改文件过多”的响应归类为 TOO_LARGE，不再作为普通 FAILED 每次拉取；不伪造行数 0。

`CodeContinuousSyncScheduler`、`CodeScanService` 增加实际定时触发及活动范围跳过日志；Mapper 的持久订阅使用 SYNC_V2，接口请求/响应继续使用 SYNC。

原 18016 验收进程在当前执行上下文不能停止（ps 被限制、对应信号报告进程不可见），未使用 Terminal 或其他旁路控制。任务 29 在无活动任务时备份并归档为 SNAPSHOT_V1，停止旧版订阅、保留 227 个游标及全部命中数据；新版本兼容读取这些游标。

归档前备份（0600）：`/var/folders/wv/0ljm0h256y1_f_fgv2yw62rh0000gn/T/iw-continuous-v1-subscription-2vzhvhy5.sql`。

新服务用 `scripts/local-backend.sh` 启动于 18026；新 Vite 为 `http://localhost:5175/zhaogang/code/mine`，复用本人浏览器绑定，没有把 PAT/Cookie/token 写入命令回显、截图或证据。

## 上游 diff 限制的实际核对

使用本人现有 PAT 先经 DescribeCodingCurrentUser 确认 Id=9292850，再读取四个失败 SHA。仅输出脱敏错误和计数。

| 仓库 / 短 SHA | 实际响应 | 处理 |
| --- | --- | --- |
| business-knowledge-hub / 880a302b2195 | FailedOperation：diff 修改文件过多，不宜在网页展示 | TOO_LARGE，提交保留、行数未知 |
| pangmaoyun-skills / fbf0a37a5adf | 同上 | 同上 |
| pmy-skills-cli / e05edb099a7c | 同上，根提交 | 同上 |
| pangmaoyun-skills / 0b685caa4eb3 | FailedOperation：参数异常；GitCommitInfo 确认提交存在、父提交 0；FilePathList 返回 29 文件，按第一个文件单独请求 diff 仍参数异常 | 保留 FAILED 与部分行数告警，不静默改为 0 |

[官方 OpenAPI](https://coding.net/help/openapi) 公开了提交详情、文件列表和带可选 Path 的 diff 参数；没有证据说任意根提交或超多文件提交都一定可返回 diff。上述失败结论来自本次真实只读请求，不由文档推测权限不足。

## 检查与结果记录范围

最终受控测试 322 项，失败/错误/跳过 0；Web type-check、build、check:zhaogang-release-notes、test:zhaogang-code-stats-range 已重跑通过。编译/行为检查与实网统计分开记录。下表记录真实任务及分页请求；不是 mock 结果。Web 四项检查于 2026-11-04 再次执行通过（build 有原有 chunk/pdfjs 警告）。


## 2026-11-04 接续核对：共享父链与真实定时结果

新增 `CodeCommitGraph` 在同仓库的分支之间共享不可变 SHA 父链和准确命中集合。遇到已知祖先直接复用，缺失节点以 SHA 为 Ref 请求；合并的两条父链分别闭合，强推后按当前可达范围清理。真实只读请求已确认 Ref 支持本次使用的 SHA；不能将此实测扩展为官方文档的普遍承诺。完整 EOF 才可建立日期范围闭包；失败、分页截断不发布伪完整进度。测试覆盖跨分支复用、双父链、强推、缺失元数据、日期范围外头及失败保留。

| 任务 / 实际时间 | 耗时（秒） | 分支 | 提交分页调用 | 待取 diff | 仓库失败 / diff 失败 |
| --- | ---: | ---: | ---: | ---: | --- |
| 29，11-03 首次全年回补（旧父链逻辑） | 7254 | 9584 | 33307 | 601 | 5 / 4 |
| 33，11-03 17:00 续跑补缺口 | 280 | 9594 | 1430 | 9 | 1 / 4 |
| 34，11-03 18:00 定时增量 | 108 | 9597 | 19 | 2 | 0 / 1 |
| 35，11-03 21:00 定时增量 | 102 | 9602 | 24 | 1 | 0 / 1 |
| 36，11-04 00:00 定时增量 | 87 | 9604 | 0 | 1 | 0 / 1 |
| 37，11-04 03:00 定时增量 | 94 | 9604 | 1 | 1 | 0 / 1 |
| 38，11-04 06:00 定时增量 | 88 | 9604 | 0 | 1 | 0 / 1 |
| 39，11-04 09:00 定时增量 | 93 | 9604 | 0 | 1 | 0 / 1 |
| 40，11-04 09:32 被测实例启动续跑 | 102 | 9605 | 7 | 1 | 0 / 1 |

所有已结束任务均为真实 232 仓库范围。分页数是提交列表调用次数，不包含仓库/分支目录、diff 及重试；当前仍遍历目录以发现新仓库、新分支，因此后台整轮不是几秒。增量没有重读全年所有提交。34/35 新增记录与 diff 处理属于真实自然变更；36/38/39 无新增提交列表请求，已知成功 diff 均复用。

3 条 TOO_LARGE 保留提交、行数未知，不重复作为瞬时错误请求。另 1 条根提交仍 FAILED，每轮重试 1 个 diff，故整体 PARTIAL；这不阻止月份查询。最新 40 的仓库错误为 0，游标为 232 份、压缩正文 2356836 字节。未声称全部行数完整。

### 验收结论

| 场景 | 结论 | 实际证据 |
| --- | --- | --- |
| 从 2026-01-01 回补 | 通过，部分行数受上游限制 | 一至十一月均有命中数据；四条未知行数如上 |
| 每 3 小时后台增量 | 通过 | 34—39 实际 cron 与结束记录，活动范围无重复任务 |
| 未变分支不读提交列表 | 通过 | 39 skippedHeads=9604，commitPageCalls=0 |
| 变更分支只补新缺口 | 通过 | 34/35 分别 19/24 次；强推、合并边界另有行为测试 |
| 月份切换不触发扫描 | 通过 | 11-04 真实 Chrome 复用本人绑定态，十一月 8 次切换十月 98 次；切换不产生新任务；独立实例 40 结束后再复验，数据同步 09:34，任务仍为 40 |
| 十月口径与特殊仓库 | 通过 | 98 次、新增 8208、删除 2302、16 天、6 仓库；apijar 9 次只计提交 |
| 未归属提示移除 | 通过 | 实际页面不再显示该提示，Git 身份 3 个 |
| 团队成员真实 PAT | 未执行 | 本次授权限本人 PAT；已有行为测试不能充当团队实网验收 |
| 后端与 Web 检查 | 通过 | Maven 322 项，失败/错误/跳过 0；Web 四项全部通过 |

### 版本及运行边界

后端 HEAD `0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3`；Web HEAD `d821532cfc9209085e6436093f52df5c9764547f`，均包含未提交及未跟踪的本功能改动。实际 Java 17.0.9（JetBrains），Maven 3.8.6，仓库 `/usr/local/maven/repository`，显式独立 settings；Node 18.17.0、npm 9.8.1、Vite 5.4.21。系统默认 Java 为 8，测试/脚本明确选择 Java 17。

当前源码与被测 graph 临时构建的 codestats 源文件逐个一致；工作区 target 与被测 graph JAR 的 97 个功能 class 逐个一致，但全 JAR 哈希不同，不能据此把其他模块改动认定已测。11-04 再用 local-backend.sh 在 18026 启动独立被测 graph JAR（时间戳 11-03 15:30:24+0800；SHA256 `50d3ac7507e168c4932e1ade771a90b145cc11c7525be6d01ed47e18426251ce`），避免覆盖用户现有 18006。Vite 5175 代理此实例。

页面截图已检查无 PAT/Cookie/token：[十月真实页面](continuous-sync-october-20261104.png)。UI 交互可见结果与后台整轮耗时分开；本次未采集单独 HTTP 毫秒级指标。


### S8 清理与交付文件

- 取消的中间任务 27/28 已在前序备份清理；本次对 31/32 再断言均为本人 CANCELLED、提交 last_seen 引用为 0、游标引用为 0，备份后删除两条任务及其仓库摘要，删除提交行 0。备份 0600：`/var/folders/wv/0ljm0h256y1_f_fgv2yw62rh0000gn/T/iw-continuous-clean-31-32-etv6djkj.sql`。精确取消 Redis 键 31/32 已删除、剩余 0，无 FLUSH。
- 保留本人真实功能数据、任务 29 的历史归档、33—40 的实际增量摘要以及最新 232 份游标，用于页面查询和后续定时同步；没有创建额外 Git 身份测试夹具。3 条 TOO_LARGE、1 条 FAILED 是真实数据残留，非伪造测试数据。无其他业务表、共享库、他人 PAT 操作。
- 本次主要后端文件：`CodeCommitGraph`、`CodeBranchSync`、`CodeSyncCheckpointCodec`、`CodeScanWorker`、`CodeContinuousSyncScheduler`、`CodeScanService`、`CodeStatsQueryService`、`CodeStatsModels`、`CodeIdentityService`、`DefaultCodeStatsModule`、三个 Code Mapper、ScanJob/ScanRepo 实体、`ZhaogangProperties`、持续同步迁移及初始化 SQL；对应行为测试位于 `codestats` 测试目录。
- Web：`src/types/zhaogangCodeStats.ts`、`src/api/zhaogangCodeStats.ts`、`src/views/zhaogang/code/` 的个人/团队视图及同步状态组件、`vite.config.ts`；月份本地查询、立即同步及后台状态展示，无小程序改动。
- 脱敏机器证据：[JSON](continuous-sync-evidence-20261104.json)、[定时与分页日志](continuous-sync-log-20261104.txt)。只保存计数、版本、状态，不复制原始鉴权或完整请求日志。
- 本地最终实例 18026 与 Vite 5175 保持运行；原 18006 不误停。两个调度器共享范围唯一活动索引，活动范围会跳过；本次未部署或合并这些未提交改动。


### 2026-11-04 提交前隔离验证

仅导出 HEAD 与本次精确暂存内容到临时目录，排除 Agent 令牌、Agent 授权设置和技能工作台的其他未提交改动，再执行后端 Maven 模块测试与 Web 四项检查：全部通过，后端 322 项、失败/错误/跳过 0。Maven 使用独立 settings、Java 17 与指定本地仓库。更新日志同步为当前身份与每 3 小时持续同步口径。暂存内容已检查无凭证字面量。此验证不触发服务重启、数据库迁移或生产构建。
