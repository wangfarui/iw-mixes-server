# 数据库迁移执行手册

2026-10-16 更新：本地已执行并通过；生产待执行。推荐使用[生产三步入口](../../../iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/production/README.md)，结果见[本地数据库报告](DATABASE-REPORT-20261016.md)。以下保留原分步流程作诊断参考；不与合并入口混跑。

2026-10-15 实施阶段未执行 SQL；2026-10-16 已经用户授权在本地执行。支持 **MySQL 8.0.21及以上8.x / InnoDB / utf8mb4**；不宣称支持MariaDB、MySQL5.7或8.0.20及以下。已通过本地 MySQL 8.0.31 实库及隔离回归验证；未代表生产状态。

## 前置条件

- 选定唯一的iw-core数据库；核对真实schema，不以init文件推断当前结构。操作账号需要SELECT/INSERT/UPDATE/ALTER/CREATE/DROP/INDEX/CREATE ROUTINE/ALTER ROUTINE及临时表权限。脚本01使用短期存储过程实现存在性DDL，执行结束删除过程。
- 做可恢复备份，保留00原始输出。由用户安排停止旧版业务写入、任务生成/完成、预算结算及其他定时写入；整个00→04与幂等重跑窗口保持停写。实施Agent未停止现有服务。
- 所有涉及表须InnoDB；base_dict.dict_name须utf8mb4。字符集或前置迁移缺失时停止，不由本期SQL偷偷修复。
- 已有家庭共享字段、任务记账和立即生成结构、付款人迁移必须先齐备。以下是**条件依赖**，先核查结构/执行记录，不可对已执行的非幂等脚本再执行：
  1. `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-points/scripts/iw-points-migrate-20261010-task-bookkeeping.sql`
  2. `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-points/scripts/iw-points-migrate-20261013-plan-generate-now.sql`
  3. `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/iw-bookkeeping-migrate-20261014-expense-user.sql`
- 若00发现缺前置字段，先根据上述独立迁移文档及真实结构处理，再从00重新开始。本期01不会替代这些历史迁移。初始family/group/share schema也必须存在，见auth/bookkeeping历史设计；不运行整套init来修补已有库。

## 本期准确顺序

| 顺序 | 完整路径 | 作用与继续条件 |
|---|---|---|
| 先阅读 | `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/README.md` | 本目录说明及本手册 |
| 00 | `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/00-preflight.sql` | 只读：MySQL/schema/字符集、名称规范化、编码歧义、标签来源、预算重复、模板版本、记录/钱包金额基线。缺字段、SQL报错或异常不能继续；常规清单/基线输出不算异常。 |
| 01 | `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/01-schema.sql` | 增量字段/生成列/唯一索引/快照表/家庭marker/一次性迁移指标基线。不重写旧ID或名称；重复有效名称建唯一索引失败时停止，不删除数据强行通过。 |
| 02 | `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/02-backfill.sql` | 按原记录人/配置所有者唯一映射分类；标签从4001业务关联按实际字典类型回填。预算、快捷、两处schema1 JSON升级。查看issues结果必须为空，未完成账单必须0。 |
| 03 | `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/03-family-initialize.sql` | 对未标记有效家庭复制启用的系统分类模板，家庭标签空；分类与marker同事务，删空不重灌。 |
| 04 | `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/04-verify.sql` | 只读：覆盖率、异常、引用/类型、名称/版本、预算、模板、marker、schema/index；四项baseline unchanged全部1。 |
| 幂等复核 | 同一停写窗口依次重跑02→03→04 | 本次记录/模板迁移数与家庭初始化数为0，快照/JSON未被覆盖，无新增异常，baseline仍全1。不要拿应用写入后的数据与原始停写基线做不变比较。 |

原分步脚本与合并入口均已完成本地 MySQL 运行验证；**生产尚未执行**。00/04只包含SELECT、会话变量及只读PREPARE，不建表不写业务。02/03是事务DML，但DDL01会自动提交；不要使用mysql `--force`，不要让客户端遇错继续后续语句/后续文件。发生错误立即停止并ROLLBACK未完成事务，再确认连接状态与备份。

可由用户在安全终端按每个文件分别执行。连接密码通过客户端交互提示/已批准安全凭据提供，不写命令行、SQL、报告；明确传入目标数据库。每个文件输出独立保存，审阅成功后才执行下一项。生产合并入口已提供自动异常阻断及业务 DML 回滚；只读验证仍需检查 OVERALL。

## 异常判定与修复边界

- 00：missing_prerequisite、非InnoDB/非utf8mb4、非规范名称、同范围规范化后重名、旧编码候选不唯一、缺标签源/错误类型或归属、重复预算，均阻塞。模板清单需核对：已知schema1本期升级，schema2保留；未知版本/非法字段在02输出异常。
- 02：issues表输出行必须为0；分类与全部标签成功后才置status1，错误行保持status0。使用MIN仅在candidate_count=1时取值，不从重复来源任选一条。旧record_type=0且没有来源时为无分类；如有唯一code0来源则保留其来源；有多个仍算歧义。
- 账单迁移允许已逻辑删除的源项作基线。未来快捷/任务配置的源已停用或删除、类型/归属错误、非法JSON元素均报告，不给失效配置伪造替代项；需用户确认修复或在专用方案中重新保存。旧分类被原地覆盖的真实历史名称无法恢复，只能建立**迁移基线名称**。
- 04：除正常覆盖率/schema/index/family清单/四条baseline指标以外，异常查询应无行；全部账单status1；所有模板schema2；分类预算有id/name且无重复；有效家庭有marker；baseline记录数、金额、付款人/记录人/共享状态指纹、钱包余额/资产、旧通用关联、非三类字典均不变。CRC32/XOR只是辅助变化检测，不能替代备份或精确审计。
- issues未清零不进入正式验收/发布；不得任意删字典/合并预算额度/填“其他”以清零。需要数据修复时另交可审阅增量脚本，在获得对应执行授权后处理。02 现已在 issues 非空时先回滚再报错，不再提交部分成功。
- 新表保留旧通用关联为迁移证据，不双写、不清理。01–03重跑不覆盖已有账单快照、schema2 JSON或家庭marker。已知schema1 JSON使用JSON_SET并移除旧recordType输入字段，其余金额/项目/统计/未知字段保留，不触发任务完成、钱包或积分。
- 全新数据库的init文件已同步；**已有数据库禁止运行**iw-web-init.sql、iw-auth-init.sql、iw-bookkeeping-init.sql整套文件。init不在上述增量执行顺序中。

## 执行后交回下一会话

提供数据库版本/环境名（无凭据）、备份及停写窗口、实际执行路径及各文件成功/失败/退出码、00与04脱敏输出、02 issues及迁移计数、03初始化计数、幂等重跑结果。执行过的脚本建议附SHA256，交接包也提供静态清单。

新代码启用前统一更新客户端，旧code-only写入会提示升级；禁止旧后端/旧客户端与新快照混写。新版本已写入后不能直接退回旧代码继续写；没有删除新列/快照的一键回滚，回退先停写，评估备份与新数据恢复。
