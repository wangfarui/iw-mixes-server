# 任务计划立即生成

微信小程序任务计划列表长按 → 立即生成 → 核对参数 → 生成 → 查看任务。

登录接口：`POST /points-service/points/task/plan/generateNow`。
请求：`{"id":123,"requestId":"plan_一次操作的唯一标识"}`。
响应 `data`：`taskId`、`planDate`、`deadlineDate`、`taskDeleted`。

- 日期由后端按 Asia/Shanghai 取今天，截止日期 = 今天 + 截止天数。
- 名称、备注、奖惩积分、完成时记账模板沿用当前已保存计划；待办进入收集箱。
- 提前提醒天数只用于自动调度。手动生成不修改计划日期、下次生成日期、周期和启用状态。
- 禁用计划仍可手动生成。同一天允许多次主动生成，也可以与自动生成共存。
- 同一计划的同一 requestId 永久对应同一任务，包括跨日重试与逻辑删除后的重试。
- 逻辑删除后重试返回原任务和 `taskDeleted=true`，不补建。再次主动生成使用新 requestId。
- 生成本身不记账、不发放积分；后续完成、逾期仍使用原有规则。

## 数据库升级

停止 iw-core 后，先确保已执行 `iw-points-migrate-20261010-task-bookkeeping.sql`，再执行
`iw-points-migrate-20261013-plan-generate-now.sql`，最后启动新版 iw-core。
迁移使用固定的 `ALTER TABLE`，旧索引名按 20261010 迁移中的 `uk_task_plan_period` 编写。
如果数据库来自旧初始化脚本，索引名是 `uk_source_plan_date`，先单独执行以下 DDL，再执行迁移：

```sql
ALTER TABLE points_task_basics RENAME INDEX uk_source_plan_date TO uk_task_plan_period;
```

新建数据库的 `iw-points-init.sql` 已包含新结构，不要再重复执行迁移。

`source_request_id IS NULL` 表示自动生成（没有来源计划的普通任务同样为空）；
非空表示手动生成。`source_plan_date` 始终保留本次计算基准日期。
生成列 `scheduled_plan_date` 只为自动生成提供唯一期次键，手动任务该列为空。
两个唯一索引均不包含 deleted，逻辑删除不会释放生成凭据。

## 验证

`verify-task-plan-generation-schema.py --socket /tmp/mysql.sock` 在独立临时数据库中
验证历史迁移、同日手动/自动共存、跨日重试、逻辑删除去重、并发去重和事务回滚。
