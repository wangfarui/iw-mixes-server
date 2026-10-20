# 2026-10-16 本地数据库迁移与生产交付报告

**结论：本地数据库迁移及幂等验证通过；生产未执行；API/页面业务验收未执行。**

## 实际执行

目标为本机 `localhost:3306/iw_mixes`，MySQL 8.0.31。用户确认并停止本地后端后执行；18000/18080 端口均无监听。未重启后端。

开始时结构已迁移、账单 170 条，150 条快照完成，20 条历史测试账单异常（其中 8 条收支类型非法）。11 个已升级模板另被旧校验误报：MySQL `JSON_SET` 写入无符号来源 ID 后，`JSON_TYPE` 返回 `UNSIGNED INTEGER`。已修正所有相关 JSON 整数校验，仍拒绝字符串/浮点/布尔 ID；没有改写已有 schema2 模板。

用户先确认这 20 条为测试数据，再明确授权“已停止服务，执行本地清理与验证”。按准确 ID 归档后删除 20 条，金额 1,149.23；没有标签、任务或记账附件关联。清理和本期迁移分开执行，清理**不属于生产流程**。

| 项目 | 实际结果 |
|---|---|
| 保留账单 | 150 条，金额 25,719.64，全部快照状态 1 |
| 归档测试账单 | 20 条，金额 1,149.23；每列数据与清理前一致 |
| 迁移及重跑 | 均 `MIGRATION_PASS`，记录/模板/家庭计数 `0 / 0 / 0`；这些正常数据已在此前一轮迁移完成 |
| 验证及重跑 | 24 项异常数全部 0，`OVERALL=0 / PASS` |
| 基线 | records / wallet / relations / other_dict 恰好四条，`unchanged=1` |
| 精确审计 | 13 组按主键排序的完整行数据 SHA256 一致：保留账单、归档账单、钱包、钱包流水、通用关联、其他字典、预算、快捷配置、计划、任务模板、家庭 marker、标签快照、全部字典 |
| 服务/Git | 后端保持停服；未暂存、提交、推送、发布 |

本地账单基线由 170 / 26,868.87 明确重建为 150 / 25,719.64；旧四项基线已归档。其余三项基线没有刷新。金额减少仅来自用户确认的测试数据清理，不能将此减少归因于迁移。

## 备份和归档

本地备份均为权限 0600 的完整 MySQL dump，含十六进制二进制值、结构、数据、触发器/过程/事件，已实际导入隔离 MySQL 验证可恢复。含业务数据，不纳入交付清单或 Git：

- `logs/local/dictionary-v2-20261016/before.sql`
- `logs/local/dictionary-v2-20261016/before-cleanup.sql`（执行清理前重新备份）

本地归档表保留：

- `bookkeeping_dict_v2_local_archive_records`：20 条原账单。
- `bookkeeping_dict_v2_local_archive_baseline`：清理前四项基线。

恢复应停写并依据备份/归档单独评估；不提供覆盖新业务数据的一键恢复。

## 独立 MySQL 回归

测试使用独立数据目录、Unix socket、关闭 TCP 的 MySQL 8.0.31，不使用应用连接配置。真实备份恢复后，在隔离测试库构造旧结构和已知正常夹具；不会修改应用库来制造测试场景。

验证覆盖：首次迁移 150 条账单、11 个 schema1 模板升级（含 unsigned 整数）及未知 JSON 字段保持；重跑完整数据哈希不变；历史坏数据/来源缺失阻断；未来标签失效回滚所有业务 DML；独立 02 不提交部分成功；字符串标签 ID 拒绝；错误索引定义阻断；基线缺项阻断且不能被迁移补造；本地清理的目标保护、归档一致和幂等。

[回归原始结果](evidence/mysql-regression-20261016.txt)。测试实例已关闭；测试数据目录位于 `/private/tmp/iw-dict-v2-mysql-20261016/`。

## 生产直接使用

按[生产三步执行说明](../../../iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/production/README.md)：

1. `00-preflight.sql`。
2. `production/01-migrate.sql`。
3. `production/02-verify.sql`。
4. 同一停写窗口重跑生产 01 → 02。

迁移入口包含 schema、回填、家庭初始化、提交前 24 项校验；业务 DML 同事务，异常自动回滚并报错。DDL/首次基线建立不在此事务内，成功的 DDL 不能用 ROLLBACK 撤销。只读验证仍需人工看 `OVERALL`，不能以客户端退出码 0 作为通过依据。新快照/模板/家庭初始化不重复覆盖。

**生产异常必须按生产真实数据单独处理；不执行 `local-only/`，不复制本地测试数据清理或基线重建。** 现有 2026-10-15 `FILE-MANIFEST.json` 是实施交接历史快照，不代表本次修改后的哈希。本次 SQL 和证据哈希见 [SQL-MANIFEST-20261016.json](SQL-MANIFEST-20261016.json)。

## 原始执行证据

- [本地清理](evidence/local-cleanup-20261016.txt)
- [清理后的预检](evidence/local-preflight-20261016.txt)
- [首次迁移](evidence/local-migrate-20261016.txt) / [首次验证](evidence/local-verify-20261016.txt)
- [幂等迁移](evidence/local-rerun-migrate-20261016.txt) / [幂等验证](evidence/local-rerun-verify-20261016.txt)
- [最终版本迁移](evidence/local-final-migrate-20261016.txt) / [最终版本验证](evidence/local-final-verify-20261016.txt)
- [完整行数据哈希审计结论](evidence/local-exact-audit-20261016.txt)

日志中的已有表/过程不存在提示，以及 `BINARY expr` 弃用警告，不是失败。没有忽略 SQL 错误；每步返回 0，并核对结果后才执行下一步。原通用关联 69 条、其他字典 1,480 条保持不变。详细原始数据哈希仅保存在本地忽略目录，交付报告不包含原始业务行或凭据。
