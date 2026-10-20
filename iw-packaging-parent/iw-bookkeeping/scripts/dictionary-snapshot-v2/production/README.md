# 生产执行：预检 → 迁移 → 验证

已在本地 MySQL 8.0.31 实库及隔离实例验证；2026-10-20 生产已按 DataGrip 分步流程完成迁移与验证，见文末执行记录。**不要把 `local-only/` 下的测试数据清理 SQL 带到生产。**

## 执行前

- 确认唯一目标库、MySQL 8.0.21+（8.x）、InnoDB/utf8mb4，以及家庭、任务记账、立即生成、付款人历史迁移已完成。
- 停止旧 `iw-core`、任务/预算结算及所有其他写入方；保持停写至幂等复核完成。
- 使用有 DDL、存储过程、临时表及业务表读写权限的账号。显式选择目标数据库。
- 完成可恢复备份。连接用安全的 `--login-path` 或客户端配置，不把密码写入命令/SQL。

下面命令中的 `PROD_DB` 必须替换为真实生产库名，`iw-prod` 必须是已配置且核对过目标主机的 login-path。使用 MySQL **非交互批处理**，每个命令执行完检查成功后再继续；禁止 `--force`、交互式 `SOURCE` 或客户端“遇错继续”。

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server/iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2
```

备份示例（必须另外核验备份可恢复）：

```bash
mysqldump --login-path=iw-prod --single-transaction --hex-blob --routines --triggers --events --no-tablespaces --set-gtid-purged=OFF PROD_DB > /受限备份目录/before-dictionary-v2.sql
```

备份包含业务数据。将 `/受限备份目录/` 替换成真实的受限备份目录，不放进代码仓库。

## 三步执行

| 步骤 | SQL | 继续条件 |
|---|---|---|
| 1 | `../00-preflight.sql` | 无缺失依赖、非法收支类型、名称/编码歧义、无效关联、重复预算；模板版本为已知的 1 或 2。普通结构、模板、金额清单可以有行。 |
| 2 | `01-migrate.sql` | 客户端退出码 0，末尾出现 `MIGRATION_PASS`。包含原 01/02/03；业务回填、家庭初始化和提交前校验在同一事务。 |
| 3 | `02-verify.sql` | 24 项 `exception_count=0`，`OVERALL=0 / PASS`，账单总数=已完成数，四条基线 `unchanged=1`。 |

```bash
mysql --login-path=iw-prod --database=PROD_DB --batch --raw < 00-preflight.sql > preflight.log 2>&1
```

```bash
mysql --login-path=iw-prod --database=PROD_DB --batch --raw < production/01-migrate.sql > migrate.log 2>&1
```

```bash
mysql --login-path=iw-prod --database=PROD_DB --batch --raw < production/02-verify.sql > verify.log 2>&1
```

**验证 SQL 是只读查询：即使输出 FAIL，客户端也可能退出 0；必须看 `OVERALL`，不能只看退出码。** `OVERALL.exception_count` 是失败检查的异常数之和，同一账单可能被多个检查计入，不是去重后的异常账单数。

同一停写窗口，再单独执行一次 `01-migrate.sql` 和 `02-verify.sql`，输出改存 `rerun-migrate.log`、`rerun-verify.log`。必须得到：

```text
MIGRATION_PASS  records_migrated_this_run=0  templates_upgraded_this_run=0  families_initialized_this_run=0
OVERALL         exception_count=0           result=PASS
```

全部通过后，保留备份和日志，再启用匹配的新后端及客户端。不要混用旧写入端。

## 失败处理

遇到任何 SQL 错误、`STOP`、`FAIL`，停止后续操作并保持停写。异常数据另行按真实归属修复，禁止猜分类、填“其他”、删数据凑通过；生产不执行本地那 20 条测试账单的清理。

DDL 和首次基线建立不在业务回填事务内；DDL 已成功的部分会保留。回填、家庭初始化、提交前校验失败会自动回滚本轮业务 DML。已有基线缺项会停止，不补造；旧基线漂移也不能覆盖刷新。新版本已写入后，不得简单回退旧代码继续写。

这两个入口由 `scripts/acceptance/bookkeeping-dictionary-v2/build-production-sql.py` 根据原分步脚本生成，包含源文件 SHA256。**选择本流程后，无需再额外执行原 01/02/03/04。** 修改源脚本后应重新生成并运行隔离 MySQL 回归测试。

## 2026-10-20 生产执行记录

- 执行方式：操作者在 DataGrip 中分步执行结构变更、基线建立和回填过程；回填过程保留本目录的业务迁移与 24 项提交前检查，并在过程内初始化名称检查规则。
- 迁移前发现的收入账单错误标签关联、重复个人标签已按用户确认范围分别归档修复；保留账单、金额和原支出标签，不使用本地测试账单清理脚本。
- 首次回填返回 `MIGRATION_PASS`，账单全部完成快照回填；重复调用返回 `MIGRATION_PASS / 0 / 0 / 0`。
- 提交后独立只读验证返回 `24 / 0 / PASS`，包含四条迁移基线的计数、金额和指纹比对。
- 操作者确认服务发布后基础验证正常，并已清理本次辅助存储过程。正式业务表、迁移基线和两张修复归档表继续保留。
- 以上证明本次数据库迁移通过；完整多角色权限、自动记账与预算结算等功能验收仍按各自验收清单补齐。
- 恢复正常写入后，旧基线只作为本次停写迁移窗口的证据；不要因正常业务变化覆盖基线或重跑迁移来修复差异。
