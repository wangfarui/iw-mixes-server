# 线上工时页面系统异常排查

时间：2026-11-20 20:47–20:51（北京时间）。用户授权排查，生产只读；尚未执行生产 DDL、重启或重新发布。

## 复现

在已有生产会话中分别 GET `/external-service/api/zhaogang/worklogs/sync?scope=SELF` 和 `/external-service/api/zhaogang/worklogs/report?from=2026-11-16&to=2026-11-22&scope=SELF`。HTTP 200，但两者均返回业务码 500、message=系统异常、success=false；页面可见相同提示。未读取或导出会话 Cookie/PAT。

## 根因及证据

实际 iw-external 位于当前内网 Java 机器，服务 active，20:43:57 启动。JAR 软链接名称含 cce9f44fd9ce；该名称取自构建时 Git HEAD，不能据此判断未提交源码是否包含于产物。实际异常栈读取新 history_before 字段，证明新读路径正在执行。日志 20:47:53 起反复出现 `java.sql.SQLException: Column 'history_before' not found.`。

只读核对活跃服务配置和数据库访问入口的地址指纹一致，目标库为 iw_mixes。四表 source、entry、issue_snapshot、sync_scope 均已存在；source 缺 history_before，只有 PRIMARY 与 idx_worklog_pending 索引，也缺 idx_worklog_member。source 当前 0 行。新后端读取旧表结构即失败，首次来源建立事务回滚。缺索引不是当前 500 的直接原因。

## 具体修复及复验

[既有迁移脚本](../../../iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261120-worklog-mirror.sql) 第 63 行起已有受 information_schema 检查保护的补列和补索引步骤，可以重复执行；仅重跑 CREATE TABLE IF NOT EXISTS 不能升级已有表。仓库 deploy-jar.sh / deploy-prod-local.sh 不自动执行数据库迁移。

本次生产实际需要的结构变更为：

```sql
ALTER TABLE iw_mixes.external_zhaogang_worklog_source
  ADD COLUMN history_before BIGINT NOT NULL DEFAULT 0;
ALTER TABLE iw_mixes.external_zhaogang_worklog_source
  ADD KEY idx_worklog_member (coding_team_id, coding_user_id);
```

上面两条展示当前缺项，执行前重读元数据；实际执行应使用幂等迁移脚本末尾的检查逻辑。保留原表、原凭证和原始审计，不操作 CODING 工时。生产写入待用户确认。

执行后检查字段与索引存在；在原生产会话重放上述两个 GET，以业务码 200 / success=true 为通过信号，观察同步队列和近期数据展示。首次后台初始化尚未完成时 partial=true 可预期，不能当作完整统计或缺勤依据。一般无需重新部署应用；是否存在其他异常仍以修复后的真实接口和日志为准。

## 本地验证边界

当前迁移在本轮隔离 MySQL 已重复执行，升级旧镜像结构成功；相关 202 项测试（含 17 项真实 MySQL 集成测试）和最终本地实际 HTTP 验收已有记录。此项证据不替代生产迁移后的复验。本次没有修改业务代码。

## 用户手动迁移后复验

用户随后告知已手动执行 SQL。2026-11-20 20:57（北京时间）使用原生产绑定会话重放上述 GET：sync 与 report 均 HTTP 200、业务码 200、success=true，原“系统异常”已不再复现。sync 返回 running=true、partial=true，已有最近增量时间，表示后台仍在补齐历史；未据此宣称全量初始化已完成。Agent 没有执行生产 DDL、重启或重新发布。
