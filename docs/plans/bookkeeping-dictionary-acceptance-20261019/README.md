# 记账字典 v2：继续验收交接

日期：2026-10-19（Asia/Shanghai）。主仓库：iw-mixes-server。此包承接原实施会话 `01a13f69-8d0f-7a33-a360-38387eeff63d` 和验收会话 `01a1425e-f791-74a3-b03e-ec1c875d536b`。

**范围已确认、业务代码已提交、验收部分完成，不能声明完整 64 项通过。** 本次资料更新日期为 2026-10-20，未重新执行 API、UI、数据库或生产操作。

## 当前状态（2026-10-20 更新）

三端业务代码在本次资料整理前均已提交：后端 `main / 37241f9`、小程序 `main / 25a6cb2`、Web `dev / 3969950`；检查时没有已跟踪源码差异或已暂存内容。用户已授权本次更新并提交三个仓库的待提交资料及 Skill 链接，未授权推送或发布。

最新验收证据为 [10/19 续验报告](../../acceptance/bookkeeping-dictionary-v2-20261019-r1019a/RESULTS-20261019-1715.md)，[MATRIX.md](MATRIX.md) / [MATRIX.json](MATRIX.json) 已按该报告同步，不把资料更新算作新一轮验收：

- 64 项中：本轮通过 10（MG-05 限报告列出的并发组合）、历史本地通过 5、不适用 1、未执行 6、部分通过 42。通过仅适用于报告中的断言与环境。
- 导入日期修复已完成构建与真实接口复验；EN-10 通过。语音已用有效中文 WAV 完成识别、确认建单、重放及越权/失效拒绝；自动保存分支未通过，EN-08/09 保留部分通过。
- 已有 Web type-check、临时目录构建、小程序路由/离线检查与部分真实页面证据；Web 详情与共享视角、小程序 A/B/C/D 本轮身份、真机键盘及多尺寸等仍有缺口。
- 原库新增 `G-r1019a-` 夹具及账单 202–205，A 支出影响 102.00 元；副本另有导入/语音夹具。**均为历史报告中的残留记录，本次没有查询或清理数据库。** 先读报告第 8 节，不能只依据旧交接残留清单清理。
- 当前服务、数据库、登录态与副本角色未在本次复核。报告中的 `UP`、端口及身份均有时间边界，续验前重新检查。启停仍必须使用 `scripts/local-backend.sh`。

续验从 [RESUME.md](RESUME.md) 和 [PROMPT.md](PROMPT.md) 开始。`BASELINE*.json`、10/16 manifest、state-snapshots 均保留历史指纹，不覆盖为新 HEAD；状态快照不是最新数据库或 runner 状态，不得直接恢复覆盖。

以下第 1–5 节保留**续验前交接记录**，其中“待导入复验”“尚未提交”、旧 HEAD、旧端口及旧覆盖数量不代表当前状态；与上方及最新续验报告冲突时，以更新的证据为准。

## 1. 用户目标、确认和授权

- 用户已基本验收 UI，随后明确要求“基于验收清单开始逐一自验并修复功能接口项，包括 UI 和交互也一并验收”，并提供五个专用本地测试账号。
- “记账设置”已有用户确认并实施的一版 UI 优化，必须保留，沿用[定稿与实施方案](../../../../iw-mixes-app-wx/docs/plans/bookkeeping-settings-ui-20261019/README.md)。不要重新设计页面。
- 允许使用这些账号进行本地真实 API、Web、原生小程序验收，在已确认功能范围内自主修复、构建和复验。原本地家庭关系保留；破坏性家庭生命周期测试在独立数据库与独立 Redis 的副本运行，不能为填用例而改原库角色、家庭关系或真实账单。
- 用户已确认此前 20 条异常是测试数据并授权准确归档清理；**该清理已于 10 月 16 日完成，不能重放。** 此授权不适用于生产删除。
- 用户最新回复“已停止 core，暂未重启”，原意是让 AI 更新第三处修复的运行包。随后用户明确本会话目标是生成交接；本包保留该授权和续跑命令。交接整理期间已完成一次新 core package 与 external compile，但没有把它们当成功能验收。
- 未授权提交、推送、发版、生产写入、创建新会话或向其他会话发消息。生产 SQL 仅交付给用户执行。

## 2. 先看这些结论

| 内容 | 已有结果 | 仍缺什么 |
|---|---|---|
| 10/16 数据迁移 | 本地迁移、幂等、异常阻断、精确审计有历史通过证据；生产未执行 | 不重跑旧清理或刷新业务基线；DB-02 精确覆盖需复核 |
| 10/19 五角色核心 API | 12 个执行阶段有实际 PASS；曾发现共享状态缺陷，修复后相关阶段复验通过 | 新包启动后再做当前状态只读断言；不能把 SKIPPED_PREVIOUS 当复验 |
| 10/19 扩展 API | 13 个阶段有实际 PASS；儿童历史名称泄露已修复并真实复验 | 剩余并发组合、入口和 UI，见逐项矩阵 |
| 独立副本 API | 8 个阶段 PASS，覆盖付款人预算、任务重复生成/完成、删除重建、角色降级和家庭生命周期 | 奖惩结算幂等、模板兼容和 UI 刷新等尚未齐全 |
| 记账设置 UI | 独立 UI 实施报告已有大量实测；本轮补了无家庭 E、成员 C 的截图 | 儿童 D 最后登录后的身份/页面未完成核验；多尺寸、真机键盘、异常/迟到响应等仍有缺口 |
| 导入 | 真实 XLSX 触发时间解析 500；源码修复、单项回归和 10/19 新包构建已完成 | **未完成真实导入接口复验** |
| 语音/AI | runner 已准备，尚无实际语音链路通过证据；交接包已附 16kHz 单声道 6 秒测试 WAV | 供应商、确认、重放、失效来源、自动保存分别验证 |

33 个 PASS 是累计实际执行的工具阶段（12+13+8），**不是 33/64 场景通过**；原场景往往同时要求 API、UI、数据库或多个异常分支。详见 [64 项完整矩阵](MATRIX.md)、[续跑步骤](RESUME.md)和[可复制提示词](PROMPT.md)。

## 3. 已修复缺陷与部署边界

| 修复 | 源码/测试 | 验证和当前状态 |
|---|---|---|
| 10/16：统计 SQL 被 JSqlParser 拒绝 | records/yearly Mapper 等使用 CAST AS BINARY 保持名称精确匹配 | 真实 MySQL 与实际 API 通过；历史报告保留 |
| 10/16：已捕获的“选项失效”污染外层事务 | 字典 resolveSuccessor 的业务异常回滚语义 | 捕获后可提交、未捕获仍回滚的真实 Spring 事务测试及 API 通过 |
| 10/19：普通编辑漏传 shared 时把私人账单变共享 | [BookkeepingRecordsServiceImpl](../../../iw-packaging-parent/iw-bookkeeping/src/main/java/com/itwray/iw/bookkeeping/service/impl/BookkeepingRecordsServiceImpl.java)，扩展 BookkeepingPayerIntegrationTest | 缺省沿用账单当前共享值并遵守强制共享策略；先红后绿；修复包曾运行，HS-02-SHARING、ST-02 实际复验通过 |
| 10/19：首次身份懒加载时儿童查询漏用限制，筛选泄露成人历史名称 | [SharedQueryScopeAspect](../../../iw-web/src/main/java/com/itwray/iw/web/core/aop/SharedQueryScopeAspect.java)，新增 [SharedQueryAuthenticationTest](../../../iw-packaging-parent/iw-bookkeeping/src/test/java/com/itwray/iw/bookkeeping/support/SharedQueryAuthenticationTest.java) | 在确定共享查询策略前解析真实登录身份；先红后绿；修复包曾运行，X-PRIVACY 实际复验通过。公共 iw-web 影响 external，仍需补编译检查 |
| 10/19：导入时间格式大小写错误 | [BookkeepingRecordsImportBo](../../../iw-packaging-parent/iw-bookkeeping/src/main/java/com/itwray/iw/bookkeeping/model/bo/BookkeepingRecordsImportBo.java)，新增 [BookkeepingImportFormatTest](../../../iw-packaging-parent/iw-bookkeeping/src/test/java/com/itwray/iw/bookkeeping/support/BookkeepingImportFormatTest.java) | `yyyy/m/d h:mm` 改为 `yyyy/M/d H:mm`；真实 FastExcel 字符串回归先红后绿，覆盖单数字月日和 23:59；**源码已修，运行包未更新** |

10/19 本会话按固定 Maven 路径、显式 settings、仓库和 Java 17 重新打包 core：指定测试集实际运行 33 个，0 失败、1 个 MySQL 条件测试跳过；`iw-external` 共享模块编译成功。该结果只证明构建/测试，未替代真实导入或完整功能验收。[脱敏测试摘要](evidence/backend-checks.txt)仍只摘录历史日志。

小程序 UI 优化及后续“复制勾选反馈”是已有工作，均保护：

- [UI 优化实施与自验结果](../../../../iw-mixes-app-wx/docs/acceptance/bookkeeping-settings-ui-20261019-a1019/RESULTS.md)：包含已通过、部分通过和真机待验项。
- [复制选择框修复结果](../../../../iw-mixes-app-wx/docs/acceptance/copy-selection-fix-20261019/RESULTS.md)：真实选中数量与勾号一致；未新增家庭数据。
- 本轮离线小程序 settings、payer、task-plan-generation 和 72 页路由检查已通过，Web type-check 已通过；Web build 仍需补齐。10/19 external 编译已通过。新会话须记录自己的实际验证。

## 4. 源码、环境和基线

联合工作区 `/Users/wangfarui/workspaces/wfr/iw-mixes_ai`。三仓库均有大量既有未提交/未跟踪改动，不能按 HEAD 恢复或清理。

| 仓库 | 分支 / HEAD | 责任 |
|---|---|---|
| iw-mixes-server | main / eac7fd9666a3744008dfdc0e38be00994c0f82c5 | 字典版本/快照/权限、统计、预算、快捷、语音、导入、任务适配、SQL、验收工具与主报告 |
| iw-mixes-app-wx | main / cd997144a41b129ff4e886802c64c6a5ce4034b9 | 已优化记账设置、管理弹层、录入/快捷/账单/统计/预算/任务 UI 和缓存隔离 |
| iw-mixes-web-platform | dev / b91c2acc8324525a36aafccf409eb9a569e48e71 | 历史名称筛选、共享账单详情、旧通用字典维护禁用；不新增 Web 记账设置产品范围 |

本包 [BASELINE-HANDOFF.json](BASELINE-HANDOFF.json) 是本次交接最终显式选定源码和资料的指纹，含未跟踪关键文件；不是所有文件的快照，不证明运行包或数据库版本。目录中的 `BASELINE.json`、`BASELINE-FINAL.json` 是此前捕获的中间基线，保留供追溯；变化不得靠覆盖旧基线消除。

本轮交接实际检查 TCP 监听结果已存 [runtime-state.txt](evidence/runtime-state.txt)：

- **18000 core、18001 隔离 core、18006 external 均无监听**。这比只看脚本 status 更可信；当前执行器曾无法 ps/kill，status 可能误报。
- 5173 两个 Vite 进程分别监听 IPv4 127.0.0.1 和 IPv6 ::1，先核对页面/源码，不盲目重复启动或停掉用户进程。
- 独立 MySQL 13317、Redis 16379 仍监听，仅 loopback。副本持有业务数据，备份/配置不可进报告或 Git。
- 这些仅为交接时状态，新会话必须重查。

Skill 唯一源 `skills/iw-feature-handoff/` 及四处 `.agents/skills` 相对链接本轮已检查正常，本轮实际调用并读取；不推断新会话工具发现状态。

## 5. 账号、数据与业务约束

| 测试角色 | 登录凭据来源 | userId | 原本地应用库身份 |
|---|---|---|---|
| A 群主 | 私有安全输入 | 43 | 家庭 2，角色 1 |
| B 家长 | 私有安全输入 | 42 | 家庭 2，角色 2 |
| C 成员 | 私有安全输入 | 8 | 家庭 2，角色 3 |
| D 儿童 | 私有安全输入 | 44 | 家庭 2，角色 4 |
| E 无家庭 | 私有安全输入 | 45 | 无家庭 |

不在本包记录密码/token。优先复用仍有效的登录态；API 需要重新登录时通过私有 stdin/FIFO 或安全输入供应。若新会话无法取得旧聊天中的认证信息且登录态不可复用，仅请求安全输入，不猜密码、不把密码写到命令或文件。

独立副本已经执行退群、转让、解散：**副本 A/B 是家庭 7 普通成员，E 是家庭 7 群主，C/D 无家庭**。这不是原库被改，也不是角色错误。不能让核心五角色 runner 对副本强套原始身份，不能为了续跑重置副本。

已确认业务：记录人 user_id 与付款人 expense_user_id 分开；个人按付款人统计；家庭为本人全部加他人共享并去重；代记强制共享。新选项同名个人优先，旧账单保存可信 ID/名称快照；改名生成后继来源，不能重写历史；dictionaryChanges 缺省/局部更新/显式清空语义保留；收支类型不允许编辑切换；导入沿用整体事务且不联动钱包的既有语义。

真实接口/字段以源码及[原 64 项定义](../../acceptance/bookkeeping-dictionary-v2/SCENARIOS.md)为准。前端路径带 `/bookkeeping-service`、`/auth-service`、`/points-service` 等兼容入口，不能发明接口。

### 本地夹具与残留

- 原应用库 `r1019a` 核心月份 2032-04，账单 R1–R6 为 193–198，来源 `ACCEPT-r1019a`，合计 750（A 付款 320、B 付款 430）。核心末态 A 个人 320/家庭 700，B 个人 430/家庭 650，C 家庭 600。
- 扩展月份 2032-05，前缀 `X-r1019a-`，三张账单合计 113，均 A 付款；包含历史改名/显式清空/已删除来源等刻意夹具。分类、标签、不可用快捷配置也有残留；排序已恢复既有项相对顺序。
- 本轮原库测试支出合计 A 433、B 430，经正常接口影响钱包；这些是执行时记录，交接没有重新读钱包余额。不能把此变化归为迁移差异。
- 10/16 的 `f10161031` 两张账单 190/191 合计 3.03、快捷 111、预算 16 等仍按历史报告保留。独立 UI 任务 `UI-a1019-` 已清理残留 0，不属于本轮新清理范围。
- 本轮没有执行测试夹具清理。原 runner 约定清理需另行明确授权；在没有具体清理确认前保留夹具并报告，不擅自删账退钱。新增测试使用唯一前缀和正常 API，禁止复用真实账单。私有副本可用于破坏性隔离测试，但不重置原库。
- 状态保存在后端忽略目录 `logs/local/dictionary-v2-functional-r1019a/`，必须保留；本包 [state-snapshots](state-snapshots/README.md) 另存脱敏续跑副本及指纹，只在原状态丢失且核对环境/ID 后评估恢复，不能覆盖正在推进的状态。

## 6. 下一步和完成条件

按 [RESUME.md](RESUME.md) 顺序：核对当前源码、运行环境和最新状态 → 对照更新后的矩阵补尚缺 API/并发/预算/任务断言 → 补原生小程序及 Web UI → 汇总真实证据、未覆盖项和数据残留。导入与语音确认已有 10/19 真实复验，不再作为从未执行项重跑；新增回归应使用新的唯一标识并核对幂等状态。

仍未覆盖的断言必须标明未执行/受阻，不以单测、mock、旧截图或供应商“不满足自动保存”分支代替真实功能分支通过。当前没有新业务设计问题阻断上述首步；只有权限、有效认证或供应商实际不可用时才报告具体阻断。

生产 SQL 继续使用[生产说明](../../../iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/production/README.md)：00-preflight → production/01-migrate → production/02-verify，并在同一停写窗口幂等重跑 01→02，查看结果集与 OVERALL。**生产保留异常并停止，绝不执行 local-only 清理。** 不为让旧基线通过而更新基线。

完成后按三端列出实际修改、运行版本、每项验收结果、真实证据、未覆盖项及数据残留。不得声称生产执行或完整验收通过，除非各自有真实证据。
