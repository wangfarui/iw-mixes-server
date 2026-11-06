# 找钢代码统计：失败诊断和定向重试

生产核对基线：任务 14 和任务 18 的全年 48 条 diff 失败逐条一致，其中 42 条过大、6 条读取失败。支付仓库在任务 14 只保留 104 个稳定分支，任务 18 已退出失败列表。这里实现失败诊断、定向补算，以及根提交和大提交的只读 Git 行数补全。

## 变更

- `CodingOpenApiClient` 保留 HTTP 状态、传输异常类型和本地响应上限。没有可识别的 diff 列表时保留行数未知；合法的空列表仍可表示零变更。限流、超时和上游暂时不可用走有限重试。
- `CodeDiffFailure` 与 `CodeScanWorker` 区分上游文件数限制、本地响应上限、参数异常、权限、限流、超时、上游不可用、无效响应及未知失败。普通 INFO 日志 `zhaogang code diff failed` 包含任务、项目、仓库、提交 SHA、失败分类和受限的错误码/HTTP 状态/上限。日志不写原始消息、响应正文或凭证。
- `BRANCH_CHANGED` 的仓库摘要记录待复核分支数；`zhaogang branch head changed` 为每仓库最多 10 个变化分支记录分支名和前后 SHA。稳定分支游标和成功数据仍照常保留。
- 失败明细兼容历史状态，新增详细原因和仓库 ID。提交明细继续返回已有的 `FAILED` / `TOO_LARGE` 状态。Web 将“部分分支待复核”与仓库读取未完成分开，并在重试结束后刷新失败列表和行数覆盖。
- 定向重试允许本人处理自己的失败记录，团队管理员处理本团队失败记录。每次至多 100 条，使用仓库 ID 与完整 SHA 同时定位，限定在已完成来源任务的日期和权限范围。任务执行时重新核对仍失败的记录，不枚举仓库、分支或提交历史，且不覆盖上次扫描的元数据覆盖状态。与常规同步使用同一范围任务锁。
- 定向补算先走 CODING OpenAPI。接口因参数或大小限制取不到 diff 时，`CodeGitLineStats` 才用该次查询的成员凭证对固定 CODING HTTPS 仓库按 SHA 浅拉取，读取提交和直接父对象。根提交对空树统计，普通提交对真实父对象统计；合并提交不纳入。取不到所需对象、无法鉴权或超限时仍保留未知。普通定时扫描不启动 Git。
- Git 凭证仅通过该 Git 子进程的 URL 专用 HTTP 请求头配置传入，不写命令参数、仓库配置或日志。禁止重定向和非 HTTPS 传输、不 checkout、不取标签或子模块，清理临时裸仓库。默认单进程内同一时刻执行一次 Git 补算，单次 40 秒，临时目录及展开对象上限各 128 MiB。生产需要 `/usr/bin/git` 支持通过 `GIT_CONFIG_COUNT` 传入配置，可用 `IW_ZHAOGANG_CODE_GIT_LINE_STATS_ENABLED=false` 关闭 Git 路径。

## 运行验证与采集

1. 同步交付 `iw-external` 和 Web。复用现有 `diff_status` 字段，新增状态长度在字段容量内，不需建表或加列。若回退到旧后端，须先评估将新状态映射回 `FAILED` / `TOO_LARGE`，否则旧查询不会将它们全部纳入失败覆盖率。
2. 由团队管理员打开“团队代码 → 查看失败明细”，先用“补算此提交”验收一条根提交和一条大提交。确认生产 Git 认证和指定 SHA 拉取可用后，再使用“补算失败行数”。不超过 100 条时补算本任务范围内仍失败的记录；超过时可补算当前页。
3. 按任务 ID 查看 `zhaogang code diff filled`（Git 补算成功）或 `zhaogang code diff failed`（包括 `gitFallback` 失败类别）。成功的提交会退出失败列表；仍失败的提交保留未知行数。无需重扫全年。
4. 下轮出现分支待复核时，按任务 ID 和仓库 ID 查看 `zhaogang branch head changed`。不必手动触发全年扫描。

接口：`POST /external-service/api/zhaogang/code-stats/scan/{来源任务ID}/diff-retry` 接受 `targets` 数组，每项为 `{depotId, sha}`；`GET /external-service/api/zhaogang/code-stats/diff-retry/{重试任务ID}` 查询结果。

## 本地验证

- Java 17、Maven 3.8.6，独立 settings 和约定的本地仓库：`iw-external -am test` 353 项通过，失败、错误、跳过均为 0。
- 回归覆盖权限错误不走 Git、Git 对象不足不写 0、根提交、父提交差异、重命名和二进制文件，以及同 SHA 不同仓库的定向选择和团队管理员权限。
- Web 类型检查、代码统计范围回归和生产构建通过。Chrome 用独立模拟后端验证：选中两条同 SHA 但不同仓库中的一条，实际只提交该仓库和 SHA；完成后该条退出失败清单，另一条保留，覆盖率刷新。模拟服务和验证前端已停止。
- 在 CODING 页面核对了样本的 HTTPS 克隆路径。对以下真实失败提交从现有本地仓库浅拉取到隔离裸仓库，调用生产代码的对象限制与 numstat 逻辑，未使用真实 PAT 或写入生产数据：

| 仓库 | SHA | 新增 | 删除 | 文件 |
| --- | --- | ---: | ---: | ---: |
| online.base.conductor.service | d5df5788 | 1 | 0 | 1 |
| online.base.conductor.service | fdc5f576 | 0 | 23954 | 1 |
| online.base.conductor.service | 981d0c33 | 65624 | 3286 | 106 |
| pangmaoyun-skills | 0b685caa | 3501 | 0 | 29 |
| pangmaoyun-skills | fbf0a37a | 386 | 5604 | 46 |
| pmy-skills-cli | e05edb09 | 6255 | 0 | 35 |

前四条与此前 CODING 网页可见统计一致。真实 HTTPS 令牌认证、服务端是否允许按历史 SHA 拉取，以及生产 48 条记录的补算结果，仍需上线后的单条验收。
