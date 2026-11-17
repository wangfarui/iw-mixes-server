# 手机号工时通知开发与验收记录

本次仅修改后端与 Web，未修改微信小程序；未提交、推送或部署。保留两仓库已有的 Skills、Agent、Nginx 等其他改动。

## 完成行为

- 团队工时群通知可选一名当前团队成员接收汇总提醒。新报告使用企微 `text.mentioned_mobile_list`，不配置企微 userid。
- 全员明细照常逐人发送；仅未请假、采集完整且不足 8h（含未登记）的成员，在本人明细首条按手机号提醒。已满 8h、请假或数据异常不提醒。未填手机号只发送正文，分段续条不重复提醒。
- 成员表增加手机号：所有团队成员可查看，仅管理员可在表内编辑。用户可在设置的通用设置、当前 CODING 角色下维护本人手机号。
- 手机号按 CODING 顶层团队与用户共享，支持留空清除，校验 11 位中国大陆手机号；与角色、最近活动时间独立写入。后台复核当前管理员、目标成员和团队版本。
- 发送前校验手机号指纹；手机号变化取消未提交消息。旧队列保留 Markdown 类型兼容。手机号请求和相关响应不记录正文日志。

## 变更文件

后端路径均相对 `iw-mixes-server/`：

| 路径 | 改动 |
| --- | --- |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangProfileController.java` | 本人手机号 GET/PUT |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangTeamController.java` | 管理员编辑成员手机号 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/ZhaogangExceptionHandler.java` | 手机号接口异常处理 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/mapper/ZhaogangWorkbenchProfileMapper.java` | 手机号读取、独立 upsert、成员资料查询 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/profile/WorkbenchMobile.java` | 手机号校验及请求模型 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/profile/WorkbenchProfile.java` | 共享资料增加手机号 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/team/WorkbenchTeamModels.java` | 团队成员、通知采集成员及编辑命令 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/team/WorkbenchTeamModule.java` | 编辑成员手机号接口 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/team/DefaultWorkbenchTeamModule.java` | 管理员、成员、版本校验及资料输出 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/team/WorkbenchTeamRepository.java` | 成员手机号存储接口 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/team/MybatisWorkbenchTeamRepository.java` | 事务内成员校验与保存 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/NotificationMessage.java` | 持久化消息类型与提醒手机号 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/NotificationStore.java` | 汇总接收人及消息队列字段 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/WorklogNotificationModule.java` | 配置、采集、发送与手机号变更失效 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/WorklogMessageFormatter.java` | 文本模板、2048 字节分段及缺时提醒 |
| `iw-packaging-parent/iw-external/src/main/java/com/itwray/iw/external/zhaogang/notification/WecomWebhook.java` | 企微 text 与 mentioned_mobile_list |
| `iw-packaging-parent/iw-external/scripts/iw-external-init.sql` | 新环境初始化字段 |
| `iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261116-worklog-mobile.sql` | 已有环境增量迁移 |
| `iw-web/src/main/java/com/itwray/iw/web/core/webmvc/RequestLoggingFilter.java` | 手机号请求正文日志排除 |
| `iw-web/src/main/java/com/itwray/iw/web/core/webmvc/ResponseLoggingFilter.java` | 手机号响应正文日志排除 |

测试文件相对后端：

- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/ZhaogangProfileControllerTest.java`
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/ZhaogangWorkbenchProfileMapperMysqlTest.java`
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/notification/NotificationStoreMysqlTest.java`
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/notification/WecomWebhookTest.java`
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/notification/WorklogMessageFormatterTest.java`
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/notification/WorklogNotificationModuleTest.java`
- `iw-packaging-parent/iw-external/src/test/java/com/itwray/iw/external/zhaogang/team/DefaultWorkbenchTeamModuleTest.java`
- `iw-web/src/test/java/com/itwray/iw/web/core/webmvc/WorklogWebhookLoggingTest.java`

Web 路径均相对 `iw-mixes-web-platform/`：

| 路径 | 改动 |
| --- | --- |
| `src/api/zhaogangProfile.ts` | 本人手机号 API |
| `src/api/zhaogangTeam.ts` | 管理员编辑 API |
| `src/types/zhaogangTeam.ts` | 成员手机号类型 |
| `src/types/zhaogangNotification.ts` | 汇总提醒人类型 |
| `src/views/zhaogang/settings/ZhaogangMobileSettings.vue` | 本人手机号表单 |
| `src/views/zhaogang/ZhaogangWorkbench.vue` | 将表单置于 CODING 角色后；该文件另有既有改动 |
| `src/views/zhaogang/team/ZhaogangTeamView.vue` | 成员手机号列及管理员编辑框 |
| `src/views/zhaogang/team/TeamWorklogNotification.vue` | 汇总提醒人选择、缺号提示及保存 |

联合工作区资料同步更新：`context/zhaogang-workbench-product-knowledge.md`、`context/zhaogang-workbench-technical-reference.md`、`context/zhaogang-workbench-operations-and-verification.md`。

## 验证证据与边界

- `backend-tests.log`：45 项 iw-external 测试通过，含真实隔离 MySQL 的 mapper 与队列测试。
- `phone-change-test.log`：新增手机号改变但成员不变时取消剩余消息的用例；该模块 10 项测试通过，其中 9 项与上条重复。因此 iw-external 共验证 46 个不同用例。
- `logging-tests.log`：使用 Surefire 3.2.5 执行 5 项 iw-web 日志测试通过。老版默认 Surefire 未执行 JUnit5 的结果不计入。
- `web-typecheck.log`、`web-build.log`：最终 Web 类型检查及生产构建通过。
- 完整初始化 SQL 和迁移脚本在隔离 MySQL 8.0 执行，增量脚本连续执行两次通过；未实际运行 MySQL 5.7。
- `api-results.json`：真实后端 API、隔离数据库、合成 CODING 数据与本地消息接收器联合验收，覆盖管理员编辑、本人身份防伪造、权限/成员/旧版本拒绝、跨团队共享和三条消息的提醒规则。
- `messages.jsonl`：两批共六条真实出站 JSON，均送本地接收器；第二批从浏览器切换汇总接收人后触发，汇总与缺时成员明细均为新选择的手机号，8h 成员明细无提醒。
- 浏览器验证管理员编辑手机号、普通成员仅查看、本人设置保存及两处读取一致；汇总成员修改后未保存时禁止立即发送，保存并刷新后保留选择。设置页请求窄窗口时工具实际视口最小为 500px，实测无页面横向溢出；未宣称 390px 真机验收。截图仅内联查看，未保存截图文件。
- 本地发送验收在独立后端源码副本中将出站地址替换成本地接收器；工作区正式源码仍只接受真实企微 HTTPS 地址。没有发送真实企业微信群消息，真实群内 @ 效果仍待上线后受控确认，手机号需与企微登记信息一致。
- 原有本地 18000/18006 服务和共享数据库未迁移、未重启。所有验收资料使用合成账号及号码。

## 上线顺序

1. 已有数据库执行 `iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261116-worklog-mobile.sql`，整份脚本须在同一连接执行。
2. 部署 iw-external 后端，再发布 Web。
3. 成员或管理员维护企微登记手机号，管理员选择汇总提醒成员并保存。未配置手机号时正文照发且不 @；不自动 @所有人。

## 临时环境

本次验收目录 `/tmp/zg-mobile-accept-20261116`；数据保留以便复查。两个早期隔离 Java 进程监听 18048/18049，因运行上下文拒绝进程探测/信号未能终止，通知均未启用；这不是用户正常后端。清理情况见 `runtime-cleanup.json`。
