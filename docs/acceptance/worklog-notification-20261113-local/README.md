# 工作台工时群通知实施与验收

日期：2026-11-13。用户已明确授权实施。后端 `main`、Web `dev`，基于原有未提交工作增量完成；未提交、推送或部署。小程序无改动。

## 实施内容

| 仓库/路径 | 内容 |
| --- | --- |
| 后端 `zhaogang/notification/` | 管理员配置、加密 Webhook、字节拆分、固定域名 HTTP 适配、每日采集调度、持久化顺序队列与投递状态 |
| 后端 `zhaogang/ZhaogangNotificationController.java`、`ZhaogangExceptionHandler.java` | 三条通知 API、会话及团队权限错误、请求格式错误脱敏 |
| 后端 `zhaogang/worklog/WorklogDailyReader.java`、`WorklogQueryService.java` | 本人 PAT 当日采集、StartAt、分页/去重、删除核验、父级链与独立成员完整性 |
| 后端 `iw-web/.../RequestLoggingFilter.java` | 排除通知配置请求正文日志，并有专门回归测试；其它接口保持现有条件 |
| 后端 `iw-external/scripts/iw-external-migrate-20261113-worklog-notification.sql`、`iw-external-init.sql` | 四张通知表，默认关闭；新环境/增量入口 |
| Web `src/api/zhaogangNotification.ts`、`src/types/zhaogangNotification.ts` | 与真实服务契约一致的 API 和类型 |
| Web `src/views/zhaogang/team/TeamWorklogNotification.vue`、`ZhaogangTeamView.vue` | 团队详情配置、保存/清除/测试确认、最近状态轮询、并发冲突、团队切换隔离 |

按工作台日历在工作日 17:00 采集，一条团队报告后连续发送每位成员明细。完整 0h、未达 8h、已达标、请假、数据失败/不完整分别表达；需求/任务、直接工时事项编号及名称、逐笔时长均保留。正常一人一条，超长按 4096 UTF-8 字节拆分。

## 验证结果

| 编号 | 状态 | 证据范围 |
| --- | --- | --- |
| N01 | 通过 | 后端全量 437 项，0 失败/错误/跳过；新增测试继续定向运行 10 项均通过。涵盖 formatter、sender、daily reader、module、MySQL store，以及原有工时/其它业务回归 |
| N02 | 通过 | Java 17、Maven 3.8.6、显式空 settings 与固定仓库；已检查 Maven/Java 环境和 `.mvn` 无冲突。`iw-core` + `iw-external` 编译通过；独立源副本打包通过 |
| N03 | 通过 | 隔离 MySQL 8.0.31：完整初始化 SQL、增量重复执行、配置乐观锁/权限/清除、并发唯一批次、消息领取/跨实例续发、共享通道顺序/节流、明确失败重试上限、未知提交暂停及过期后保持 UNKNOWN |
| N04 | 通过 | 实际新后端 API + 模拟 CODING：通过普通绑定和邀请加入流程建立两个合成账号。匿名 401；普通成员 GET/PUT/测试均拒绝；非法 URL 与格式错误安全返回，revision 不变 |
| N05 | 通过 | `npm run type-check`、`npm run build`；生产构建保留已有大 chunk 提示 |
| N06 | 通过 | Chrome 实际团队页面：默认关闭、非法输入失败保留草稿、保存后清空地址、不回显、并发冲突保留草稿、测试确认取消、清除并停用、390px 和中英/emoji 名称无页面横向溢出 |
| N07 | 通过 | 浏览器延迟旧团队 GET 响应并切换团队，释放旧响应后新团队仍未配置、输入为空；未覆盖真实网络断开后的所有轮询恢复情况 |
| N08 | 通过 | 最终新运行包日志中无三个 Webhook 夹具片段；响应不返回地址或密文。请求日志过滤回归测试验证正文抵达 Controller 且不写日志 |
| N09 | 未执行 | 真实 CODING PAT 的当日数据、真实企微连接测试、17:00 定时运行及群最终展现。未提供真实群地址，本轮未发真实群消息 |
| N10 | 部分完成 | 通过新 Store 实例、SENDING 持久化及 UNKNOWN 恢复测试验证重启语义；未在真实发群中杀死进程/重启，也未连续等待 30 天验证保留期 |

[汇总](verification.json)、[API 断言](api-results.json)、[浏览器断言](browser-results.json)、[390px 截图](mobile.png)。截图已检查，Webhook 字段不展示地址。HTTP sender 测试使用 mock 客户端，不能当作企微已受理证明。

## 隔离环境与残留

最终运行包由 `/tmp/iw-notification-accept-20261113/source` 独立源副本构建，业务源文件与工作区一致。后端通过项目 `scripts/local-backend.sh` 启动在 18037，Web 5186，模拟 CODING 18136。MySQL 13316 与 Redis 16379 复用已有隔离实例，仅新增 `notify_accept_20261113_1635`、`notify_api_20261113` 专用 schema，以及 Redis database 5 中的合成账号数据。未写共享业务数据库或生产环境。

最后通过页面清除测试 Webhook；运行 schema 中 enabled/cipher 均为空、批次数为 0。单测 schema 保留合成团队、配置、批次及消息，便于复核；没有真实 Webhook/PAT。临时源、JAR 和日志保留在 `/tmp/iw-notification-accept-20261113`。

启动脚本在当前进程权限下无法识别/停止已启动的隔离 Java 进程（`ps`/进程检查受限）；18036 旧验证包和 18037 最终包仍保留，未扩大停止其它后端。Web/模拟 CODING 为本轮工具会话进程。

初次打包前漏查了默认 18006 服务对工作区 `target/iw-external-0.2.1.jar` 的占用，该路径曾被 Maven 重建；随后改用独立源副本和运行包。18006 会话接口仍响应（未绑定返回 401），但没有重启它或复验其余业务，不能以此认定常用本地服务已加载本功能。后续需在受控重启时加载正确制品；本轮所有新功能运行证据来自独立 18037。

## 接入与后续验收

已有环境先执行本次四表迁移，再部署 `iw-external` 和 Web；新环境使用完整初始化 SQL。本能力还依赖既有工作日历、请假、团队、凭证，以及工时删除表；尚未执行删除表迁移的环境也需应用 `iw-external-migrate-20261113-worklog-deleted-issue.sql`。本轮未向既有本地共享库、测试库或生产库迁移。

在团队详情保存实际群 Webhook，点击连接测试确认群收到单条测试消息，再开启通知。保留稳定 `IW_INTERNAL_SECRET`（现有 session-key 来源）；轮换后需重新保存地址。确认成员本人均已备份有效 PAT，并在一个工作日检查 17:00 的报告、成员顺序与明细。

“企微已受理”仅代表平台返回 errcode=0。未知提交不自动重发，避免重复；过了当天 17:30 不续发，不补历史报告。清除/配置变更/成员变化会停止后续消息，已经提交给企微的请求不能撤回。
