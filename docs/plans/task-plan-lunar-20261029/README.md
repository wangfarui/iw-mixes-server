# 任务计划支持农历：实施、迁移与自验

日期：2026-10-29。代码已实施，未提交、未推送、未发布。2026-10-29 本地数据库已迁移，真实登录接口和模拟器部分验收已执行；当时生产迁移尚未执行；用户于 2026-10-30 反馈已发布并验证。详见[本地执行记录](../../acceptance/task-plan-lunar-20261029-local-r1/README.md)。

## 已确认规则与实际行为

- 本会话用户确认：农历每月遇到闰月也执行，不提供“包含闰月”开关；历史任务计划全部默认公历。
- 公历/农历均支持每日、每周、每月、每年、自定义天数。每日/每周/自定义按实际自然日间隔，农历每月和每年按农历递进。
- 农历每月含普通月和闰月；普通月的每年任务只执行一次。年度闰月规则在没有同名闰月的年份按普通月执行。
- 固定三十遇到小月时按二十九执行，后续大月恢复三十。公历月底/闰日也从原始日期计算，避免持续漂移。
- 先换算本期公历日期，再减提前生成天数/加截止天数；统一以 Asia/Shanghai 判断今天。
- 新建或修改日期规则时，本期未过、提前生成日期已过则在保存事务内生成本期待办并推进。禁用计划不自动生成；仅改名称/备注/积分/记账模板不重置当前周期。
- 原“立即生成”继续额外生成以今天为日期的任务，不推进原计划。自动任务按来源计划和本期日期去重，包括已删除的任务。
- 农历年份范围1901–2099，超出可计算范围时生成最后一期后自动禁用；预览明确提示上限。
- 历法切换转换同一个实际日期。修改农历年份导致闰月/日期不存在时，要求重新选择，不偷偷替换原始日期。
- 页面预览接下来三期的计划/生成/截止日期。尚未被调度处理的逾期或禁用计划，预览会保留当前游标，可能包含过期的待处理周期。

## 项目与主要改动

后端：`iw-packaging-parent/iw-points`，新增 `PointsTaskPlanCalendar`，新增日期规则DTO、转换/预览接口，修改计划保存、详情/列表、定时推进与自动生成去重，提供迁移和临时库验证脚本。

小程序：`iw-mixes-app-wx` 的 `api/points.js`、`pagesPoints/points/task-plan-add.*`、`task-plan.js`、`task-plan.wxml`，以及日期交互回归脚本和 npm 入口。

Web：本轮无改动。

计划固定保存 `calendar_type` 和 `anchor_date`。`anchor_date` 是用户原始日期对应的公历日，能够无损还原该日的农历年/月/日/闰月信息；`plan_date` 始终是当前游标对应的公历日期，`next_plan_date` 是生成日期。不能用小月回退后的日期覆盖 `anchor_date`。

## 接口契约

均需现有登录态，沿用 `/points-service/points/task/plan`：

| 方法与后缀 | 用途 |
| --- | --- |
| GET `/calendar?year=2028` | 农历年份实际存在的月份（month/leap/label/days） |
| POST `/convertDate` | 公历和农历选择互转，仅计算、不保存 |
| POST `/preview` | 预览三期，仅计算、不生成任务；可传本人计划id保留当前游标 |
| POST `/add`、PUT `/update` | 保存计划，新增历法/农历日期参数 |
| GET `/detail?id=...`、POST `/page` | 返回历法、原始日期、规则与双历日期文案 |

农历规则示例（可直接用于 `/preview`，保存时增加任务名称等字段）：

```json
{
  "calendarType": "LUNAR",
  "planCycle": 4,
  "lunarYear": 2027,
  "lunarMonth": 8,
  "lunarDay": 15,
  "leapMonth": false,
  "remindDays": 3,
  "deadlineDays": 0
}
```

`planCycle` 沿用1每日/2每周/3每月/4每年/5自定义；自定义需 `cycleDays`。公历使用 `planDate`（原始选择日期）。`leapMonth` 只表示所选原始日期是否为闰月，绝不是每月是否包含闰月的开关。

未传历法：新建默认SOLAR；编辑继承原历法。旧客户端编辑当前日期未变的农历计划时保留原始锚点；如果试图修改农历日期却没有完整农历参数，则拒绝，避免按公历覆盖。新版编辑从详情 `anchorDate` 回显首次日期。

## 数据迁移流程

本地已按以下步骤执行，生产尚未执行；针对其他目标库先在副本演练，再按同样顺序操作。MySQL DDL隐式提交，必须停写；这不是事务回滚型脚本。

1. 确认连接的数据库、版本和备份恢复方式。历史库应已完成20261010记账迁移、20261013立即生成迁移，检查 `points_task_basics` 存在 `source_request_id`、`scheduled_plan_date` 和基于后者的唯一索引 `uk_task_plan_period`。
2. 停止 `iw-core`，阻止旧版本定时推进及前端写入。生产按现有运维入口停核心服务；本地只使用 `scripts/local-backend.sh stop core`。
3. 在业务库中执行下方前置检查。若已有任一新列、历史数据异常、迁移前置索引不同，停止并先核对现状，不直接重跑。
4. 使用已确认的备份方式备份整表；同时建立本次对照快照。示例快照名 `points_task_plan_backup_20261029`，若已存在，应先核对来源并选择本次唯一名称，不能覆盖。
5. 执行[一次性迁移SQL](../../../iw-packaging-parent/iw-points/scripts/iw-points-migrate-20261029-task-plan-lunar.sql)。它只增加两列，历史 `calendar_type` 由NOT NULL默认值统一为SOLAR，`anchor_date` 回填为迁移时 `plan_date`。
6. 执行后置检查，所有差异/异常计数均应为0，总数应一致；完成前保持停写。
7. 部署新版 `iw-core` 再启动，确认实际运行的包包含本次代码；小程序随后发布/使用对应版本。不要先启动新版代码访问旧表。
8. 完成下方真实链路自验，观察下一次凌晨1点（Asia/Shanghai）调度。保留备份与迁移记录，验收后按既有备份保留策略处理。

前置检查：

```sql
SELECT DATABASE(), VERSION();
SELECT column_name FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='points_task_plan'
  AND column_name IN ('calendar_type','anchor_date'); -- 必须0行
SHOW CREATE TABLE points_task_basics; -- 核对前置列与uk_task_plan_period
SELECT COUNT(*) AS invalid_rows FROM points_task_plan
WHERE plan_cycle NOT IN (1,2,3,4,5)
   OR plan_date IS NULL OR next_plan_date IS NULL
   OR plan_date < '1901-01-01' OR plan_date > '2099-12-31'
   OR (plan_cycle=5 AND (cycle_days IS NULL OR cycle_days<=0)); -- 应为0
CREATE TABLE points_task_plan_backup_20261029 LIKE points_task_plan;
INSERT INTO points_task_plan_backup_20261029 SELECT * FROM points_task_plan;
SELECT (SELECT COUNT(*) FROM points_task_plan) AS source_count,
       (SELECT COUNT(*) FROM points_task_plan_backup_20261029) AS backup_count;
```

后置检查（仍停写，`backup`名称按本次实际值修改）：

```sql
SELECT COUNT(*) AS invalid_rows FROM points_task_plan
WHERE calendar_type <> 'SOLAR' OR calendar_type IS NULL
   OR anchor_date IS NULL OR NOT(anchor_date <=> plan_date); -- 应为0，包含已删除/禁用计划
SELECT (SELECT COUNT(*) FROM points_task_plan) AS current_count,
       (SELECT COUNT(*) FROM points_task_plan_backup_20261029) AS backup_count;
SELECT COUNT(*) AS changed_rows
FROM points_task_plan p JOIN points_task_plan_backup_20261029 b ON p.id=b.id
WHERE NOT(p.task_name <=> b.task_name) OR NOT(p.task_remark <=> b.task_remark)
   OR NOT(p.plan_date <=> b.plan_date) OR NOT(p.next_plan_date <=> b.next_plan_date)
   OR NOT(p.plan_cycle <=> b.plan_cycle) OR NOT(p.cycle_days <=> b.cycle_days)
   OR NOT(p.remind_days <=> b.remind_days) OR NOT(p.deadline_days <=> b.deadline_days)
   OR NOT(p.reward_points <=> b.reward_points) OR NOT(p.punish_points <=> b.punish_points)
   OR NOT(p.status <=> b.status) OR NOT(p.deleted <=> b.deleted)
   OR NOT(p.user_id <=> b.user_id) OR NOT(p.create_time <=> b.create_time)
   OR NOT(p.update_time <=> b.update_time)
   OR NOT(p.bookkeeping_enabled <=> b.bookkeeping_enabled)
   OR NOT(p.bookkeeping_template <=> b.bookkeeping_template); -- 应为0
SELECT COUNT(*) AS missing_ids FROM points_task_plan_backup_20261029 b
LEFT JOIN points_task_plan p ON p.id=b.id WHERE p.id IS NULL; -- 应为0
```

历史滚动前的真正初始日期已无法从旧表还原，本次使用迁移时当前计划日期为锚点，不猜测过去的“31日”或农历含义。迁移不会恢复已经丢失的原始日号。

新库初始化使用更新后的 `iw-points-init.sql`，不能再执行本次增量迁移。重复执行增量脚本会显式报重列错误，不宣称幂等；中断后检查实际列和回填情况再处理。

回退：优先保持停写并修复，不直接删新增列。如果必须回退旧后端，先备份迁移后的状态，停止core并禁用所有LUNAR计划，保留两列，再回退应用；旧代码不懂农历，不能让它推进农历计划。已经生成的任务/账单不由表结构回退自动撤销，不做整表覆盖恢复。恢复新版时核对回退期间写入的SOLAR空锚点记录后再决定回填。

## 自验流程

### 本轮已完成的可执行验证

见[验证记录](../../acceptance/task-plan-lunar-20261029/README.md)。这些结果不能替代迁移后的真实登录接口验收。

### 可重复的代码检查

Maven执行前检查MAVEN_OPTS/MAVEN_ARGS/JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS和项目.mvn中的仓库覆盖；不得切换到~/.m2/repository。创建只含本地仓库的临时settings，同时用 `-s`、`-gs` 隔离用户/全局settings：

```bash
cat > /tmp/iw-task-plan-settings.xml <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><localRepository>/usr/local/maven/repository</localRepository></settings>
XML
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" /usr/local/maven/apache-maven-3.8.6/bin/mvn -s /tmp/iw-task-plan-settings.xml -gs /tmp/iw-task-plan-settings.xml -Dmaven.repo.local=/usr/local/maven/repository -pl iw-packaging-parent/iw-core -am -Dtest=PointsTaskPlanCalendarTest,TaskPlanCalendarPersistenceTest,TaskPlanGenerationTest,TaskBookkeepingRegenerationTest -Dsurefire.failIfNoSpecifiedTests=false test

cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-app-wx
npm run check:routes
npm run test:task-plan-calendar
npm run test:task-plan-generation
node --check pagesPoints/points/task-plan-add.js
node --check pagesPoints/points/task-plan.js
node --check api/points.js
```

临时MySQL迁移验证（socket只能指向独立测试实例，脚本创建并自动删除专用临时schema）：

```bash
cd /Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server
python3 iw-packaging-parent/iw-points/scripts/verify-task-plan-lunar-schema.py --socket /path/to/disposable/mysql.sock
python3 iw-packaging-parent/iw-points/scripts/verify-task-plan-generation-schema.py --socket /path/to/disposable/mysql.sock
```

### 迁移后真实链路验收清单（本地实际结果见执行记录）

使用真正登录的本地账户及带唯一前缀的专用计划，不伪造用户/家庭角色。测试数据清理须限定本次创建的计划和任务。

| 编号 | 操作 | 可观察预期 |
| --- | --- | --- |
| AC-01 | 迁移后对照快照 | 所有旧计划SOLAR；已有日期、周期、状态、积分、记账模板无变化 |
| AC-02 | 打开旧计划，修改名称后保存 | 页面公历；当前游标不退回原始日期，不重复生成，不改既有任务 |
| AC-03 | 公历/农历来回切换 | 实际日期一致；农历月日和闰月标记正确；转换失败保留原选择 |
| AC-04 | 查询未来有闰月年份，从该闰月的同名普通月初一建立每月预览 | 连续三次为普通月、闰月、下个普通月；没有包含闰月开关 |
| AC-05 | 选择有三十的真实农历月，每月三十 | 小月临时二十九，随后大月恢复三十；anchor_date不变 |
| AC-06 | 每年普通月及每年闰月各建一个计划 | 普通月年度只一次；原始闰月遇无同名闰月年按普通月执行 |
| AC-07 | 当天或未来两天的计划，提前生成三天 | 启用计划保存即生成本期待办；source_plan_date与截止日期正确；游标推进 |
| AC-08 | 同计划反复保存、重复请求、删除已生成自动任务后重试 | 相同自动周期不重复生成；立即生成仍可额外创建且不改游标 |
| AC-09 | 禁用农历计划后改日期，再启用 | 保存时维持禁用、不生成；启用后按既有调度入口推进 |
| AC-10 | 每日/每周/自定义N天跨农历月或跨年 | 实际相邻日期差分别1、7、N；提前/截止均按自然日 |
| AC-11 | 网络失败、切年份与历法、快速连点、退出页面 | 可重试；失效年月日必须重选；迟到响应不覆盖新选择；不重复提交 |
| AC-12 | 用另一个真实登录用户请求计划预览/编辑/立即生成 | 无权访问或操作其他用户计划 |
| AC-13 | 任务含积分和记账模板，完成已生成任务 | 沿用原积分/记账行为；计划修改不改旧任务快照 |
| AC-14 | 观察下一次凌晨1点调度及失败重试 | 只处理启用到期计划；生成与推进同事务；失败保留到期状态；闰月正确递进 |
| AC-15 | 微信模拟器与真机检查原生表单 | 中文长名称、年/月/日picker、预览换行、滚动、底部安全区均可用 |

本地需运行新版core时，只用 `scripts/local-backend.sh`。先用上述Maven配置执行 `package`（明确本地迁移已完成），再以 `IW_LOCAL_SKIP_BUILD=1`、`IW_CORE_JAR` 指定刚构建的绝对JAR路径通过脚本启动，避免脚本内部构建落入用户Maven配置。不要把编译成功当作运行版本已经更新。
