# 记账字典快照 v2 增量迁移

本地 MySQL 8.0.31 已执行并验证通过；2026-10-20 生产已完成迁移、独立验证及重复执行验证，详见[生产执行记录](production/README.md#2026-10-20-生产执行记录)。支持 MySQL 8.0.21+（8.x）、InnoDB、utf8mb4。

推荐使用[生产三步说明](production/README.md)：

1. `00-preflight.sql`：只读预检。
2. `production/01-migrate.sql`：结构、历史回填、家庭初始化，提交前验证；异常回滚本轮业务 DML。
3. `production/02-verify.sql`：24 项汇总，要求 `OVERALL=0 / PASS`、四条基线 `unchanged=1`。
4. 同一停写窗口重跑生产 01 → 02，迁移计数必须 `0 / 0 / 0`。

执行前备份、停全部写入，确认家庭/付款人/任务记账与立即生成结构；必须明确数据库，客户端遇错立即退出，禁用 `--force`。DDL 不能依靠事务回滚。已有库禁止执行整套 init。

原 00→01→02→03→04 分步文件保留用于诊断；不要与生产合并入口混跑。原 02 已改为 issues 非空时回滚并报错。JSON 数字同时接受 MySQL 的 `INTEGER` / `UNSIGNED INTEGER`，仍拒绝字符串、浮点、布尔等非法 ID。

`local-only/` 是经确认的本地测试数据归档清理，**严禁生产使用**。迁移不猜测缺失分类，不覆盖已有快照/schema2 模板，不触发钱包、积分或任务完成；旧通用关联保留。

- [本地执行报告](../../../../docs/acceptance/bookkeeping-dictionary-v2/DATABASE-REPORT-20261016.md)
- [历史完整迁移手册](../../../../docs/acceptance/bookkeeping-dictionary-v2/MIGRATION-RUNBOOK.md)

离线检查：`python3 scripts/acceptance/bookkeeping-dictionary-v2/check-static.py`。生产入口生成：`python3 scripts/acceptance/bookkeeping-dictionary-v2/build-production-sql.py`。这两条命令本身不连接数据库。
