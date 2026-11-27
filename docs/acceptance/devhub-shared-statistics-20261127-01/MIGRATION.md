# Dev Hub 共享统计迁移与回退

适用于 MySQL 8.0.29 及以上、已有 2026-11-20 工时镜像和 2026-11-26 全员统计及代码检查点迁移的环境。新库直接使用最新版初始化脚本。[工时镜像迁移](../../../iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261120-worklog-mirror.sql) · [全员统计迁移](../../../iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261126-devhub-statistics.sql) · [检查点分片迁移](../../../iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261126-code-checkpoint-parts.sql) · [初始化脚本](../../../iw-packaging-parent/iw-external/scripts/iw-external-init.sql)

## 切换

1. 核对主机、库名、版本和权限。迁移账号须可建表、建索引、建触发器及创建临时表。使用稳定的触发器定义者账号。导出包含工时镜像、删除事实、凭证指纹与团队关系、代码提交、扫描任务、仓库检查点及分片、报表版本与任务的加密备份。
2. 暂停所有实例的 Dev Hub 写入和后台同步，等待或停止活跃租约。对账旧代码记录数、组织／仓库／SHA 去重数、有效 diff 和冲突数，记录工时来源、登记、删除事实与最近成功边界。旧版表和扫描任务可用于迁移对账。
3. 在目标库使用同一个 MySQL 会话执行完整脚本。出现 SQL 错误或 `missing_shared_commits` 大于零时保持写入暂停，保留证据，修复前置条件后重跑。脚本可以重复执行，已存在的共享提交和项目进度优先保留。[共享统计迁移](../../../iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261127-shared-statistics.sql)
4. 核对同组织同仓库同 SHA 只有一条共享记录。同一 SHA 在其他仓库、组织独立保留。核对有效 diff、分支、历史月份、工时登记和删除事实。作者或提交元数据冲突进入未归属，冲突 diff 进入待补算。老的按范围检查点仍保留，首次同步只复用能够证明作者覆盖和 SHA 对应数据都齐全的检查点。
5. 加载新版 `iw-external` 后核对一项个人与团队的重合权限、一项权限收窄和一项全员统计重算。代码仓库目录由当前凭证核验，未核验的范围显示准备中或部分覆盖。工时使用各凭证的项目可见范围，迁移时仅接续有旧记录证据的项目。新发现的项目补自身历史缺口。确认报表版本和同步时间后恢复流量，再更新 Web。

迁移期可使用以下只读断言。第一条应返回零行，第二条应返回零。保留按组织导出的汇总，便于解释去重或冲突造成的差异。

```sql
SELECT coding_team_id, depot_id, sha, COUNT(*) AS copies
FROM external_zhaogang_shared_code_commit
GROUP BY coding_team_id, depot_id, sha
HAVING COUNT(*) <> 1;

SELECT COUNT(*) AS missing
FROM external_zhaogang_code_commit old
LEFT JOIN external_zhaogang_shared_code_commit shared
  ON shared.coding_team_id=old.coding_team_id AND shared.depot_id=old.depot_id AND shared.sha=old.sha
WHERE shared.id IS NULL;

SELECT coding_team_id, COUNT(*) AS commits, SUM(diff_status='OK') AS successful_diffs,
       SUM(author_conflict=1) AS author_conflicts, MIN(commit_date) AS first_date, MAX(commit_date) AS last_date
FROM external_zhaogang_shared_code_commit GROUP BY coding_team_id;
```

## 回退

- 切换前失败时使用前置备份确认旧路径与数据。保留新表及迁移诊断。保持写入暂停，直到被确认的旧路径能够按原权限工作。
- 新版已经运行时，先暂停写入和同步，单独备份新版共享提交、工时镜像及进度、删除事实、当前凭证与成员关系和报表版本。旧版工时查询不理解项目授权表。恢复旧版时，必须恢复切换前的工时镜像并按当前权限复核，不能将新版保留的权限外历史直接交给旧版查询。
- 按当前权限从切换前的成功边界增量补齐旧路径，保留切换后产生的删除事实。补齐代码时保留新版共享表和检查点供对账，按当前授权在旧版需要的范围回扫，不跨身份复制提交。已发生的 CODING 业务登记通过只读回拉对账，回退不重复向 CODING 登记或删除。
- 恢复和补齐先在隔离副本验证，确认范围、时间与明细一致后再恢复后台任务。生产迁移、回退和发布分别授权。
