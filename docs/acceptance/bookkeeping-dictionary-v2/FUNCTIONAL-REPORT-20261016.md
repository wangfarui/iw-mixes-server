# 2026-10-16 本地功能自验

本地核心服务、外部服务、Web 和微信开发者工具已启动。已修复两处真实运行缺陷，针对性测试和本地 API 复验通过；Web 筛选/详情、小程序设置/分类选择等页面检查通过。**这不是完整 64 场景或生产验收通过结论。**

## 环境与执行边界

- 后端：`127.0.0.1:18000`（core）、`127.0.0.1:18006`（external），通过 `scripts/local-backend.sh` 启动；最新 core 由用户在普通终端停止并重启，重启流程生成的 JAR 时间为 10:39:11。10:44 后的真实 API 已验证第二处修复生效。
- Web：`http://127.0.0.1:5173/bookkeeping`，复用本地 Vite 服务和登录态。
- 小程序：原生微信开发者工具 Stable 1.06.2402040，当前项目 `iw-mixes-app-wx`，模拟器实际加载新页面。初次开发工具未识别新 WXML，清除项目文件列表缓存并重新打开项目后恢复。
- API：用户明确提供的本地测试账号，实际用户 43、家庭 2、群主。小程序沿用用户 42 的家长登录态；未冒充其他角色或猜测凭据。
- 本轮修改后端和验收脚本/文档；Web、小程序无需新增代码修改。既有未提交改动保留，未暂存、提交、推送、发布。

## 两处修复及证据

1. **按分类/标签统计报错。** Mapper 的一元 `BINARY` 表达式不能被实际数据权限拦截器中的 JSqlParser 解析。将记账记录、年度统计 Mapper 及预算/快捷配置的相关条件改成 `CAST(... AS BINARY)`，保留名称的大小写、完整字节精确比较。隔离 MySQL 测试通过真实 Mapper 和数据权限拦截器，覆盖 `Food` / `food` / `food2`、多字符标签的筛选与分组；随后真实本地统计 API 通过。
2. **停用选项后，快捷配置详情返回 500。** 调用方已捕获“不可用”的业务异常，内层事务代理仍将外层事务标记为 rollback-only。仅对只解析来源链的 `resolveSuccessor` 增加 `noRollbackFor=BusinessException.class`。真实 Spring 事务代理回归证明：捕获不可用后调用方事务能提交，未捕获的异常仍由外层写事务回滚。重启后 API 验证：停用返回 `available=false`、历史账单快照保留、启用恢复 `available=true`。

修改位置：

- `iw-auth/.../BookkeepingDictionaryService.java`
- `iw-bookkeeping/.../BookkeepingActionsServiceImpl.java`、`BookkeepingBudgetServiceImpl.java`
- `iw-bookkeeping/src/main/resources/mapper/BookkeepingRecordsMapper.xml`、`BookkeepingRecordsYearlyStatisticsMapper.xml`
- `iw-bookkeeping/.../BookkeepingPayerIntegrationTest.java`

## 已执行结果

| 验证 | 结果与范围 |
|---|---|
| 独立 MySQL 集成测试 | 26 个通过、0 失败；含真实数据库名称比较/分组与数据权限拦截器。先于第二处修复执行；测试实例已关闭。 |
| 第二处修复后的默认回归 | 26 个实际执行通过、0 失败，另 1 个 MySQL 专用测试按条件跳过；包含捕获与未捕获异常的事务回归。 |
| 小程序离线回归 | 72 页路由、付款人/共享行为、任务立即生成行为检查通过。 |
| 本地 API | MANAGE、RECORD、RENAME、STATISTICS、INVALID、CLEAR、STATUS 七组写入/断言通过；重启后 FINAL-READ 另做当前状态只读核验。 |
| Web | 按 `SELF-f10161031` 来源查到 2 条账单；分类筛选只返回 2.02 元，标签筛选同样只返回 2.02 元；详情正确显示分类/标签快照，另一条显示未分类。 |
| 小程序 | 菜单记账设置恰好分类/支出标签/收入标签三项；个人/家庭分类列表可加载；分类新增弹窗可打开和取消；家庭复制已有分类提示“添加0项，跳过1项”；支出/收入标签列表可加载；记账表单分类选择器含未分类及家庭分类，支出标签可见。 |

API 进一步覆盖同范围重名拒绝、停用仍占名、跨范围同名、个人来源优先、改名新 ID/旧快照保持、过期 revision 拒绝、预算同名拒绝、快捷配置跟随来源链、旧客户端 code-only 写入拒绝、错收支类型标签拒绝且不新增账单、普通修改保留快照、显式清空分类与标签。

最终只读核验：个人支出总额 3.03；未分类 1.01；新分类/新标签各 2.02；旧分类筛选 0；饼图金额合计 3.03；快捷配置可用；预算仍保留旧分类 ID/名称。`SKIPPED_PREVIOUS` 仅表示保留之前写入阶段，不能作为本次重跑该阶段的证据。

## 本地夹具

runId 为 `f10161031`。保留测试夹具供复查：2031-03-01 的账单 190/191，合计 3.03，来源 `SELF-f10161031`；快捷配置 111；预算 16（实际预算月份由现有服务决定为 2026-10，金额 9.99，奖惩积分均 0）；独立命名分类/标签 4657–4662。标签已恢复启用。

测试账单通过正常业务 API 产生，钱包随支出相应减少 3.03。没有借用或修改既有账单做测试。此阶段新增夹具后，不能把停写迁移阶段的 150 条/25,719.64 基线当作应用持续运行后的不变总量，也不能为通过旧基线校验而刷新基线。数据库迁移结论及生产 SQL 见[数据库报告](DATABASE-REPORT-20261016.md)。

## 尚未覆盖

仅有一个可用于自动 API 登录的群主账号，以及小程序既有家长登录态。成员、儿童、无家庭用户的负向权限矩阵，角色变更/退群/删号并发，全部入口（语音/AI/任务/模板/预算结算）、所有长名称/空态及小程序全流程提交尚未完成。五角色 `run.mjs` 未执行，不能声称完整 64 场景通过；原场景矩阵中的未完成项保留在 [RESULTS.md](RESULTS.md)。

外部服务本轮只确认启动，没有调用收费供应商接口。生产数据库没有执行；生产按[三步 SQL 说明](../../../iw-packaging-parent/iw-bookkeeping/scripts/dictionary-snapshot-v2/production/README.md)操作，保留异常并停止，禁止使用 `local-only/` 测试清理。

## 证据

- [API 原始断言与复验](evidence/local-api-functional-20261016.txt)：保留首次失败及修复后结果，末尾 `FINAL-READ PASS`、`COMPLETE`。
- [测试摘要](evidence/functional-checks-20261016.txt)，原始构建日志保存在本地忽略目录 `logs/local/dictionary-v2-20261016/`。
- [Web 详情截图](evidence/web-bill-detail-20261016.png) / [DOM 证据](evidence/web-bill-detail-20261016.txt)。
- [小程序家庭分类](evidence/wx-family-categories-20261016.png) / [表单分类选择器](evidence/wx-bookkeeping-options-20261016.png)。
- [本轮源码与证据 SHA256](FUNCTIONAL-MANIFEST-20261016.json)，初始实施 manifest 是历史快照。
