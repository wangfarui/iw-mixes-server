# 工时群报告排版优化验收

2026-11-13。用户已确认格式并授权实施。仅后端及知识文档修改，Web、小程序无代码改动；未提交、推送或部署。

## 交付规则

- 汇总首行“团队 ｜ 日期 ｜ 今日工时报告汇总”。不足8h、未登记、数据异常、请假仅在人数大于0时展示，并附工作台成员名称；删除工时合计及相关说明。更新时间为北京时间 `yyyy-MM-dd HH:mm:ss`。
- 个人首行“团队 ｜ 日期 ｜ 成员”，保留个人登记状态，不展示更新时间。项目使用显示名称，按真实父级和事项类型列出编号及完整名称，不再统一显示“需求/任务”。
- 同一项目同一直接事项的登记合并一个块，工时显示为“2h、1h”，不同块空一行；不同项目同编号不合并。保留转义、4096 UTF-8 字节拆分、请假及数据不完整口径。
- 事项详情缺项目显示名时，通过成员本人 `DescribeUserProjects(UserId)` 补齐，每位成员每批最多查询一次；失败显示“项目名称暂未获取”，不改变已核验工时的完整性。没有写死 yuncunzheng 的名称。
- 定时和手动发送共用格式；已入队正文不重写，新采集批次使用新格式。无数据库迁移或公开接口变更。

## 修改文件

后端 `iw-packaging-parent/iw-external/src/` 下：

- `main/java/com/itwray/iw/external/zhaogang/CodingOpenApiPort.java`：提供项目标识到显示名映射，沿用用户可访问项目查询。
- `main/java/com/itwray/iw/external/zhaogang/worklog/WorklogDailyReader.java`：快照补充项目显示名和直接事项类型，父级使用真实类型，规范采集时间。
- `main/java/com/itwray/iw/external/zhaogang/notification/WorklogMessageFormatter.java`：汇总分类名单、标题、个人记录分块及多次登记合并。
- 对应 `CodingOpenApiClientTest`、`WorklogDailyReaderTest`、`WorklogMessageFormatterTest`：项目查询契约、类型识别、失败回退、精确格式和长消息回归。

同步更新联合工作区 `context/devhub-product-knowledge.md`、`context/devhub-technical-reference.md`，以及原通知方案中的示例和规则。

## 验证结果

| 项目 | 结果 |
| --- | --- |
| 定向自动化 | 73 项，0 失败/错误/跳过：Formatter 5、DailyReader 4、NotificationModule 9、WorklogQueryService 11、CodingOpenApiClient 44 |
| 编译及打包 | Java 17、Maven 3.8.6、固定本地仓库、显式空 settings；隔离源副本打包通过，不覆盖常用服务 JAR |
| 实际 HTTP 接口及持久化队列 | 通过正常会话绑定、建团队和邀请加入流程，用关闭自动通知的合成团队触发手动报告；本地接收器按顺序收到 3 条，最终 ACCEPTED 3/3 |
| 内容断言 | 标题/标准时间、零人数行隐藏、未登记名单、项目中文名称、需求/故事/子项层级、2h+1h 合并、第二事项空行、个人无更新时间均通过；实际只调用一次成员101的项目查询 |
| 边界回归 | 请假优先、异常不当0h、父级失败、项目名查询失败、同名项目同编号不串组、未知/自定义事项类型、长中文/emoji、转义及字节预算通过 |

证据：[API 断言](api-results.json)、[实际接收正文](messages.jsonl)、[汇总样例](summary.txt)、[个人样例](member.txt)。均为合成数据；详细测试、打包日志位于 `/tmp/iw-notification-format-20261113`。

## 环境和边界

隔离后端 18039（通过项目 `scripts/local-backend.sh` 启动），CODING/群消息接收夹具 18138；复用已有专用验收 MySQL schema `notify_manual_api_20261113`（13316）和 Redis database 6（16379），新建合成团队2。通过普通接口清除了本次团队 Webhook，最终关闭且无地址密文；保留合成账号、团队和已完成批次便于复查。

运行包由工作区源副本构建，仅该副本将出站 URI 替换成本地接收器，真实工作区发送器未改动。以上证明新格式的真实读取器、HTTP 接口和 MySQL 队列链路；未使用真实 CODING PAT、未向真实企微群发送本版报告，未重启用户常用服务。

收尾调用脚本停止18039时，脚本报告未运行，但随后该端口 HTTP 仍响应，说明当前进程识别受限；未绕过脚本停止其它进程。隔离服务和夹具仍保留，本次团队配置已清除且自动通知关闭。

## 群内空行反馈修正

用户随后提供真实群截图：记录间纯空行未显示。原 formatter 已有两个换行，因此本轮将每个工时块前的纯空行改为含 U+200B 不可见字符的占位行，保持视觉空白且不被当作空字符串丢弃。仅修改 formatter 及既有格式断言；首个块与后续块均使用该占位行。

定向 formatter、sender、notification module 共17项测试通过（0失败/错误/跳过），长消息字节预算及内容保留回归通过；日志 `/tmp/iw-notification-spacing-20261113/tests.log`。使用新的独立源副本编译测试，未覆盖运行 JAR。没有向真实群重发，企微客户端最终显示仍需加载新版后复验；原上节样例属于修改前的纯空行版本。
