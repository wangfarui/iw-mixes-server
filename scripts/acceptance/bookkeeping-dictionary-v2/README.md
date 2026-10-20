# 记账字典 v2 核心 API 验收工具

此工具在实施阶段只做 `node --check`；尚未执行任何接口或业务验收。它不连接数据库、不执行迁移、不启动应用，不接入 Maven/npm 生命周期。

## 前置条件

1. 用户已按 `docs/acceptance/bookkeeping-dictionary-v2/MIGRATION-RUNBOOK.md` 执行数据库脚本，异常处理完成；另开会话明确授权本地/专用测试环境验收。
2. 该验收会话按 `scripts/local-backend.sh` 启动后端。Node.js >=20；所用入口须包含兼容 `/bookkeeping-service` 与 `/auth-service` 前缀。
3. 提供五个互不相同的专用测试账号：A=群主、B=家长、C=普通成员、D=儿童，均在同一家庭 G；E 无家庭。A/B/C 应允许家庭共享查询，避免用户设置覆盖查询口径。工具读取真实角色与 ID 校验，但不自动修改角色/家庭/查询设置。
4. 指定一个 A/B/C 的个人及家庭可见支出均为零的独立月份。工具首次运行先检查此条件。该月禁止其他进程写入；角色/家庭变更场景应在本工具后另行隔离执行。
5. token 仅由环境变量 `IW_ACCEPTANCE_TOKEN_A` 至 `IW_ACCEPTANCE_TOKEN_E` 提供。不要将 token 写入命令参数、文件、报告、聊天正文或日志。工具使用真实 `iwtoken` header，不记录完整用户信息（其中含 token）。

## 执行顺序与准确命令

从后端仓库根目录执行。当前实施会话**不执行下面业务命令**。

```sh
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server
node --check scripts/acceptance/bookkeeping-dictionary-v2/run.mjs
```

准备好五个环境变量后，明确指定实际月份与唯一 runId，调用：

```sh
node scripts/acceptance/bookkeeping-dictionary-v2/run.mjs \
  --execute \
  --base-url http://localhost:18000 \
  --environment local \
  --run-id accept001 \
  --month 2031-02 \
  --dedicated-test-accounts \
  --state-dir /tmp/iw-bookkeeping-dictionary-acceptance
```

上例 `2031-02` 只是占位月份；须先确认它在专用环境满足空月要求。不要在真实账本中使用示例夹具。runId 为 1–12 个英文字母、数字、`_` 或 `-`；相同参数重跑复用状态文件，不得删除状态后用同一个 runId 重新跑。

远程专用测试环境须同时提供 `--environment test --allow-host <实际精确hostname>`；例如 `--base-url https://iw-test.example.net --environment test --allow-host iw-test.example.net`。`local` 只接受 localhost/127.0.0.1/::1；拒绝 URL 凭据/路径/query、生产命名、`api.itwray.com` 及其所属 `*.itwray.com`，不跟随重定向。此白名单是误操作保护，操作者仍须确认目标确为专用测试环境。

安全读取 token 的 macOS zsh 示例（输入不回显，也不写入命令历史）：

```zsh
for role in A B C D E; do
  read -rs "IW_ACCEPTANCE_TOKEN_${role}?Dedicated token ${role}: "
  print
  export "IW_ACCEPTANCE_TOKEN_${role}"
done
```

运行后请自行关闭持有凭据的终端或 `unset IW_ACCEPTANCE_TOKEN_A IW_ACCEPTANCE_TOKEN_B IW_ACCEPTANCE_TOKEN_C IW_ACCEPTANCE_TOKEN_D IW_ACCEPTANCE_TOKEN_E`。不要开启 shell `set -x`。

## 实际覆盖

| 工具输出场景 | 断言范围 |
| --- | --- |
| PRE-EMPTY | 五个独立身份、家庭角色、首次独立空月；每次重跑都重核身份与家庭绑定 |
| MG-01 | A/B 家庭可管理、C/D 只读、C/D/E 家庭新增被业务权限拒绝；本人个人可管理 |
| MG-02 | 同范围当前重名拒绝，个人/家庭同名独立 ID；停用后重名场景仍需另验 |
| OP-01 | A/B 新候选同名个人 ID 优先；C 候选不含 A 私人标签 |
| MG-07 | 通用新增（含管理员同步参数）、修改三类旧值为其他类型、删除旧入口拒绝；repair 需额外授权专用管理员后另验 |
| FIX-R1-R5 | A/B 五张基准账单，真实付款人与共享状态；明确计入统计、无激励积分 |
| ST-01 | A 个人300/家庭680，B 个人430/家庭630，C 家庭580；分类饼图同名来源合并一行 |
| MG-03 | 改名新 ID、旧 revision 请求拒绝；并发分叉须另验 |
| HS-01 | R1/R2/R3 分类历史 ID/名称、R1 标签历史 ID/名称在来源改名后保持 |
| HS-02 | 不传 dictionaryChanges 修改 R1 普通字段，失效来源的原分类与标签快照保持 |
| ST-02 | R6 新名称20，旧名称680；A/B 个人与家庭总额符合后续基准 |

测试名称使用 `验收餐饮-<runId>`、`验收用餐-<runId>`、`验收外卖-<runId>`，保持同名合并语义同时避免与真实名称混淆。账单 `recordSource=ACCEPT-<runId>`，备注含 R1–R6 标记。它们改变专用账号的钱包，工具不会删除它们，也不会自动退群或清理历史。

本工具只覆盖表格明确列出的子断言，不代表对应设计场景全部通过；UI、SQL、MySQL 名称精确索引/并发、停用/删除/退群、按名称筛选、预算、快捷、语音、导入、任务幂等及账单重建等仍须按 SCENARIOS 独立验收。任务幂等没有伪装成此 runner 自动覆盖。

## 状态、重跑与证据

- 输出逐行 JSON，仅场景 ID、状态、固定断言说明及数值差异；不输出完整请求、响应、token、完整用户信息。运行者可将 stdout/stderr 重定向至验收证据文件。
- PASS 仅代表本次实际完成的工具阶段。`SKIPPED_PREVIOUS` 是前次已完成、此次未复验，不能记作本次通过；`NOT_EXECUTED` 是待手工/独立工具覆盖范围。任一失败退出码为1。
- 状态文件 `<state-dir>/<runId>.json`（默认脚本目录 `.runs/`，已 gitignore）只包含环境绑定、用户/家庭/字典/账单 ID、非敏感夹具名称及场景状态，不含凭据；权限0600。每次写入使用临时文件后原子替换。
- 锁文件阻止同 runId 并发；异常退出后若残留锁，先检查进程确已结束，再人工移除 `<runId>.lock`，保留 JSON。不同 runId 也不得共用同一空月并发执行。
- 新增账单响应不明确时，重跑先用 recordSource+备注精确恢复；发现重复或变更即阻塞，不再次创建。字典按本 runId 精确名称恢复；改名按新名称恢复。全部阶段成功后再次运行仅核身份并标注前次阶段，完整复验应准备新的隔离月份/runId。
- 遇到 HTTP/JSON/身份错误、环境绑定变化、非空月、无法识别的服务器异常均阻塞或失败；不把任意非200当作权限拒绝。失败后先分析修复，再使用原参数续跑。
- 夹具保留为后续页面验收证据；清理需另行明确授权，仅针对 runId，不由 runner 提供自动 cleanup。

## 2026-10-16 数据库验证补充

本地数据库及隔离 MySQL 回归已通过，API/UI 场景仍未执行，见 `docs/acceptance/bookkeeping-dictionary-v2/DATABASE-REPORT-20261016.md`。

- `build-production-sql.py`：离线生成生产 01/02 入口，源脚本变更后重新生成。
- `test-migration.py`：真实 MySQL 回归；仅接受 `/private/tmp/iw-dict-v2-mysql-*/` 私有 socket，要求独立且 TCP 已关闭的可丢弃 MySQL 实例及清理前备份；会创建独立测试库，不连接应用数据库。
- 运行示例：`python3 scripts/acceptance/bookkeeping-dictionary-v2/test-migration.py --mysql /绝对路径/mysql --socket /private/tmp/iw-dict-v2-mysql-自选目录/mysql.sock --backup /安全目录/本地清理前备份.sql`。该回归夹具针对本次 170/150/20 数据集，通用生产库不要直接运行。
