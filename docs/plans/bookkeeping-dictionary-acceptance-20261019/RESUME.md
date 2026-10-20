# 接手续验顺序与命令

更新：2026-10-20。本页依据已有 10/19 续验结果整理，未新增功能验收。先读 [README](README.md)、[最新续验报告](../../acceptance/bookkeeping-dictionary-v2-20261019-r1019a/RESULTS-20261019-1715.md)，再对照 [MATRIX](MATRIX.md)。

## 1. 核对当前源码和环境

三端业务代码已提交：后端 `37241f9`、小程序 `25a6cb2`、Web `3969950`。先在各业务仓库检查 Git 状态与当前 HEAD，保护后续新增改动。`BASELINE*.json` 是旧交接指纹，HEAD 或资料变动不等于源码被破坏；不得覆盖基线或恢复源码来消除差异。

当前端口、JAR、登录态与数据库状态均需实时复核：

```sh
lsof -nP -iTCP:18000 -iTCP:18001 -iTCP:18006 -iTCP:13317 -iTCP:16379 -iTCP:5173 -sTCP:LISTEN
```

原库是 localhost:3306/iw_mixes；独立副本是 127.0.0.1:13317/iw_mixes_e2e，Redis 16379/0，历史数据目录 `/private/tmp/iw-dict-e2e-20261019/data`。这些是历史定位信息，不证明实例仍存在。敏感 dump/配置不得纳入 Git；副本原五角色已变更，不能重置副本来恢复原测试前提。

## 2. 仅在需要更新运行版本时构建和启停

导入日期修复已于 10/19 完成构建与真实导入复验，语音也已确认建单，不再按“尚未打包”从头执行。先核对实际 JAR 与源码；只有新的源码变化或运行包不符时才重建。任何实例仍使用目标 JAR 时不得覆盖打包。

Maven 执行前检查环境和项目 `.mvn` 是否含冲突仓库设置；不用用户 settings。需要构建时，在后端仓库执行：

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
  /usr/local/maven/apache-maven-3.8.6/bin/mvn \
  -s docs/acceptance/bookkeeping-dictionary-v2/evidence/maven-settings.xml \
  -Dmaven.repo.local=/usr/local/maven/repository \
  -pl iw-packaging-parent/iw-core -am \
  -Dtest=BookkeepingImportFormatTest,SharedQueryAuthenticationTest,BookkeepingPayerIntegrationTest,BookkeepingPayerPolicyTest,BookkeepingDictionarySnapshotsTest,BookkeepingDictionaryServiceTest,AuthFamilyGroupAccountDeletionTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Djava.awt.headless=true package
```

公共模块变化时评估 external，同样固定 Java/Maven/settings/仓库执行 `-pl iw-packaging-parent/iw-external -am -DskipTests compile`；需要更新运行包时，确认停止使用目标 JAR 后再 package。构建通过不能替代业务验收。

启停只用 `scripts/local-backend.sh`，先合规构建，再设 `IW_LOCAL_SKIP_BUILD=1`。10/19 曾遇 `SERVER__PORT` 干扰端口，以及脚本把两个 core 互判为已运行；当前需重新核实，不能绕过脚本或通过 Terminal GUI 管理进程。原库 core 的已用启动方式为：

```sh
IW_LOCAL_SKIP_BUILD=1 \
IW_LOCAL_JAVA_OPTS='-Xms128m -Xmx768m -Djava.awt.headless=true -Dserver.port=18000' \
  scripts/local-backend.sh start core
```

独立 core 需显式设置 18001、独立日志目录，以及指向独立 MySQL/Redis 的现存 `isolated.yml`；核对实际连接目标后串行切换。external 同理核对 18006。保留用户正在运行的无关进程。

## 3. 从尚缺断言继续

现有 runner 位于 `scripts/acceptance/bookkeeping-dictionary-v2/`。`run.mjs`、`run-extended.mjs`、`run-isolated.mjs`、`run-import-voice.mjs`、`run-gaps.mjs` 均含历史写入与执行状态；`check-current-state.mjs` 为原库当前状态只读回归。先读 runner 和状态，不把 `SKIPPED_PREVIOUS` 当本次复验，不删 completed 后用同 runId 重跑。

原忽略目录 `logs/local/dictionary-v2-functional-r1019a/` 优先于本包 [state-snapshots](state-snapshots/README.md)。快照在后续导入、语音及 gaps 执行前截取，不含全部新状态；不能直接覆盖恢复。

1. 优先核对实际环境与角色、R1–R6 快照/共享、A/B/C 金额与 D/E 隐私。需要新回归时使用隔离数据或新的唯一标识，保护旧夹具。
2. 按矩阵补 MG-06 真正交错、FG-06 迟到响应、新用户初始化/repair、预算和任务模板等未完成分支。无真实认证或注册通道时明确受阻，不伪造身份。
3. BU-04 预算结算只在独立副本或独立周期验证；持久化去重仍是待调查风险，不是已复现缺陷，也不是已有通过结论。
4. EN-10 导入已通过；EN-08 识别/确认/重放已有真实证据，但自动保存仍缺成功分支；EN-09 仍缺 AI 未知/错类型 ID。当前资产 `assets/expense.wav` 为 16kHz 单声道、49536 帧的有效中文语音。复验时仍校验文件与实际返回，不能将 NEED_CONFIRM 当自动保存通过。
5. 小程序补 A/B/C/D 实际登录身份及权限、多尺寸、真机键盘、异常与迟到响应、任务模板和刷新。沿用已确认 UI，保护 [UI 实施报告](../../../../iw-mixes-app-wx/docs/acceptance/bookkeeping-settings-ui-20261019-a1019/RESULTS.md) 与 [勾选修复](../../../../iw-mixes-app-wx/docs/acceptance/copy-selection-fix-20261019/RESULTS.md)。
6. Web 补详情抽屉、成员共享视角和三类旧字典深链接；既有构建不代替真实交互。新代码变化后再运行相应 type-check/build。

## 4. 残留、生产与报告

最新报告第 8 节记录 `G-r1019a-` 原库夹具与账单 202–205、A 支出 102.00，以及副本导入/语音夹具；旧交接还记录 `ACCEPT-r1019a`、`X-r1019a-`、10/16 等历史残留。金额和角色均须按当前证据核对。本次资料更新没有查询或清理数据，不扩大清理授权。

不得重放 10/16 已完成清理、刷新旧迁移基线或执行生产 SQL。生产继续使用 [生产说明](../../../iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/production/README.md) 的预检、迁移、结果集校验顺序；未获生产授权不执行。

最终逐项记录环境、源码/运行版本、角色、预期/实际、真实证据及残留；未覆盖项仍标明。当前资料整理与提交授权仅适用于本次会话，后续提交、推送、发布和跨会话消息按用户当时授权执行。
