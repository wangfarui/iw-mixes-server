# 工时群通知 Webhook 弹框与历史记录

已完成后端与 Web 改造，未提交、推送或部署。

- 已配置 Webhook 仅展示 key 前3位 + xxx + 后4位；完整地址仍不返回。
- 更换/首次配置使用弹框，底部为发送连接测试、取消、确认。测试草稿不替换原配置，确认后立即保存；失败保留弹框和草稿，取消丢弃草稿。
- 开启通知、汇总提醒成员变更即保存；失败恢复已保存值。页面保留立即发送，移除保存配置、页面级连接测试、清除并停用以及用户指定的两段文案。
- 最近发送按新到旧显示最多3条报告/连接测试批次记录，保留状态和受理进度。

## 本次变更路径

后端（相对 iw-mixes-server）：

- `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangNotificationController.java`：带地址的测试请求返回独立测试结果；不带地址的旧调用保留队列接口契约。
- `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/WecomWebhook.java`：服务端生成脱敏地址提示。
- `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/WorklogNotificationModule.java`：草稿测试、历史与脱敏状态输出。
- `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/NotificationStore.java`：最近3条查询、草稿测试冷却/通道互斥、测试中断恢复。
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/ZhaogangNotificationControllerTest.java`：新旧测试请求协议。
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/notification/WecomWebhookTest.java`：脱敏提示。
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/notification/WorklogNotificationModuleTest.java`：草稿测试不替换配置、失败/未知不盲目重试、历史输出。
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/notification/NotificationStoreMysqlTest.java`：真实隔离MySQL的权限、版本、冷却、通道占用、首次配置前测试、未知恢复、历史数量与排序。

Web（相对 iw-mixes-web-platform）：

- `src/views/zhaogang/team/TeamWorklogNotification.vue`：页面与弹框交互、自动保存、最近3条。
- `src/api/zhaogangNotification.ts`：草稿测试入参与结果。
- `src/types/zhaogangNotification.ts`：脱敏提示、历史与测试结果类型。

联合工作区资料：`context/devhub-product-knowledge.md`、`context/devhub-technical-reference.md`。

## 验证

- `backend-tests.log`：36项相关测试通过，无失败/跳过；其中10项在真实隔离MySQL8.0执行。不使用应用数据库配置。
- `web-typecheck.log`、`web-build.log`：Web类型检查与生产构建通过。
- `browser-results.json`、`browser-fixture-requests.jsonl`：实际组件挂载在隔离Vite页面，使用合成API响应验证。覆盖脱敏、最多3条、弹框三按钮、草稿测试、取消、模拟保存失败、确认保存、开关/成员自动保存以及刷新保留。该浏览器证据不等同真实后端联调；后端逻辑和真实持久化由上面的单元/数据库测试覆盖。
- 发送器使用模拟HTTP客户端测试；没有向真实企微群发送消息，真实群内表现未验收。
- 两仓库 `git diff --check` 通过。保留既有其它功能未提交改动。

本次交互改造无新增数据库字段或迁移。完整手机号通知功能仍需先执行已有 `iw-external-migrate-20261116-worklog-mobile.sql`，再部署后端，最后发布Web。现有本地18000/18006后端及共享数据库未修改、未重启；本次临时5198页面和隔离13326数据库验证后停止，数据文件保留。
