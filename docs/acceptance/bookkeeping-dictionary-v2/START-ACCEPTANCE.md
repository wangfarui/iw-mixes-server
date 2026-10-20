# 用户执行脚本后使用的下一会话提示词

2026-10-16 更新：本地数据库已完成授权清理、迁移和幂等验证，后端保持停服。后续先读[数据库执行报告](DATABASE-REPORT-20261016.md)；生产改用报告链接的三步入口，不重跑本地清理。下文 2026-10-15 内容为原实施交接，API/UI 业务场景仍待验收。

前提：用户已经按MIGRATION-RUNBOOK执行数据库脚本并保留脱敏结果。未执行时不要使用下面授权启动的段落。

```text
请开始记账字典v2业务场景验收并修复本期缺陷。
工作区：/Users/wangfarui/workspaces/wfr/iw-mixes_ai
先读根及三项目AGENTS.md，再完整阅读：
/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/acceptance/bookkeeping-dictionary-v2/IMPLEMENTATION-HANDOFF.md
/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/acceptance/bookkeeping-dictionary-v2/MIGRATION-RUNBOOK.md
/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/acceptance/bookkeeping-dictionary-v2/COMPILE-REPORT.md
/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/acceptance/bookkeeping-dictionary-v2/SCENARIOS.md
/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/acceptance/bookkeeping-dictionary-v2/cases.json
/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/docs/acceptance/bookkeeping-dictionary-v2/RESULTS.md
/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/scripts/acceptance/bookkeeping-dictionary-v2/README.md

我已自行执行数据库脚本，依序00-preflight.sql、01-schema.sql、02-backfill.sql、03-family-initialize.sql、04-verify.sql，并在同一停写窗口重跑02→03→04：
【填写环境/数据库版本/脚本完整路径和SHA256/退出码/备份停写情况/脱敏00及04输出/02 issues及计数/03计数/幂等重跑结果；不要贴密码或token】

现在授权你在本地或专用测试环境启动应用、执行验收并修复本期代码缺陷。先核对迁移结果无阻塞；缺结果时只推进独立静态工作，不代我连库执行脚本。后端启动/重启必须先读并使用/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/scripts/local-backend.sh；微信使用微信开发者工具编译并验证真实页面，Web启动或复用开发服务并用浏览器验证，不访问生产写入。

先准备A群主/B家长/C成员/D儿童（家庭G）/E无家庭五个专用账号、独立空月份和runId；凭据仅安全输入/环境变量IW_ACCEPTANCE_TOKEN_A..E，不写聊天、文件或报告。按工具README显式运行/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/scripts/acceptance/bookkeeping-dictionary-v2/run.mjs（必须--execute/--base-url/--environment/--run-id/--month/--dedicated-test-accounts；远程test额外--allow-host）。它只覆盖核心子断言；SKIPPED_PREVIOUS不是本次复验通过。

完整覆盖入口管理/角色、个人家庭同名、改名快照保持及显式清空、家庭生命周期、历史名称及UNCATEGORIZED/PENDING下钻、付款人金额300/430/680/630/580基准、预算结算隔离测试、快捷后继、任务幂等与删除重建、语音确认/自动保存/重放、导入与旧入口封堵。保持既有禁止修改收支类型、导入整体事务且不联动钱包的边界。API通过不替代UI，SQL证据不替代业务验收。

保留全部未提交改动（尤其Web既有其他任务）。最多同时3个子Agent，不同家庭/角色变化和共享夹具隔离或串行。发现本期缺陷直接修复、编译并复验；Maven固定/usr/local/maven/apache-maven-3.8.6/bin/mvn + -Dmaven.repo.local=/usr/local/maven/repository + Java17与显式无凭据settings，不依赖~/.m2/settings.xml。

不得暂存/提交/推送/tag/发布，不得执行新增或既有数据库迁移/修复/清理脚本。需要新数据修复时生成独立增量脚本交我执行后继续；不改已执行脚本假装环境同步。最后更新RESULTS，列通过/失败/阻塞/未执行、实际证据、修复与复验、待用户事项，不把未覆盖项说成通过。
```
