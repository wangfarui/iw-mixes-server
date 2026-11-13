# 工时报告“立即发送”验收

日期：2026-11-13。后端 `main`、Web `dev`；基于原有工时群通知增量修改，未提交、推送或部署，小程序无改动。

## 交付行为

团队管理员在团队详情的“工时群通知”点击“立即发送”，重新采集今天的工时并发送一条团队汇总及全员逐人明细。使用已保存的 Webhook 和配置版本；有未保存修改时按钮禁用。自动开关关闭、非工作日或 17:30 后均可手动操作，不会开启定时通知，也不占用 DAILY 批次。

后台异步采集，页面轮询采集与受理进度。同团队已有 DAILY/MANUAL 报告处理中时拒绝新触发，10 秒内不重复触发；同一 requestId 重试复用批次。MANUAL 批次窗口为触发起 30 分钟，复用既有格式、持久化队列、节流和未知结果暂停规则。更改配置或成员仍取消后续消息。沿用通知四表，无新增迁移。

## 修改范围

- 后端 `zhaogang/notification/NotificationStore.java`：手动批次事务、权限及版本核验、幂等和重复触发保护。
- 后端 `WorklogNotificationModule.java`：手动异步入口，共用每日采集流程，MANUAL 投递前检查成员和配置，独立有效期及状态 kind。
- 后端 `ZhaogangNotificationController.java`：新增 POST `/external-service/api/zhaogang/teams/{teamId}/worklog-notification/send-now`，参数 revision、requestId。
- 后端 `WorklogMessageFormatter.java`：报告说明改为“采集时的登记进度”，适用于任意手动发送时间。
- 后端 `iw-web/.../RequestLoggingFilter.java`：新路由沿用通知正文不记录规则。
- Web `TeamWorklogNotification.vue`、`api/zhaogangNotification.ts`、`types/zhaogangNotification.ts`：立即发送按钮、状态轮询、请求幂等标识、保存状态及忙碌检查。
- `NotificationStoreMysqlTest.java`、`WorklogNotificationModuleTest.java`：并发幂等、权限、开关关闭时采集/投递、失败不发部分报告及成员变化取消测试。

## 验证

| 验证 | 结果与边界 |
| --- | --- |
| 后端定向测试 | 22 项，0 失败/错误/跳过；formatter 3、sender 3、module 9、隔离 MySQL store 7。`iw-web` 日志测试在本次 Maven 命令中运行数为 0，不计作执行通过 |
| 编译与构建 | Java 17，固定 Maven 3.8.6 和仓库、显式空 settings；隔离源副本打包成功。Web type-check、build 成功，保留已有大 chunk 提示 |
| 实际浏览器点击 | 未配置时按钮禁用；保存后在自动通知关闭状态点击，17:32 生成 MANUAL，最终显示 3/3；窗口已超过 DAILY 的 17:30 截止 |
| 真实采集及队列、本地消息接收夹具 | 正常绑定/邀请流程创建两位合成成员；由真实读取器采集模拟 CODING。依次得到团队 3h 汇总、管理员含上级 #100 / 直接任务 #101 及 2h+1h 登记、另一成员 0h；消息到达间隔约 5.10、5.08 秒（接收夹具处理耗时 1 秒） |
| 重复及权限 | 处理中不同 requestId 被拒绝；完成后原 requestId 重放仍返回同一批次，没有新增消息。旧 revision 被拒绝；未登录返回业务 code=401，普通成员被拒绝，HTTP 均为现有 200 响应封装 |
| 页面 | 未保存 URL 时按钮禁用；390px 无横向溢出，最近受理进度正常展示。通过页面清除夹具 Webhook，最终关闭且密文为空 |

证据：[运行断言](verification.json)、[合成消息](messages.jsonl)、[390px 配置区截图](mobile.png)。详细构建/测试日志与源副本保留在 `/tmp/iw-notification-manual-20261113`。

## 隔离环境与未验证项

后端 18038、Web 5187、模拟 CODING/群消息接收器 18137，复用 MySQL 13316 与 Redis 16379。新增独立 schema `notify_manual_api_20261113`，Redis database 6 仅存合成会话；测试复用原有专用 schema `notify_accept_20261113_1635`。未修改共享业务库或用户常用服务的运行制品。

后端由临时独立源副本构建，**仅副本的出站 URI 被替换为本地接收夹具**；工作区真实发送器仍严格访问企微固定 HTTPS 地址。其余实现与工作区一致。这验证按钮、真实 HTTP 接口、CODING 读取器、MySQL 和队列全链路，不证明真实 CODING 或企微已受理。未向真实群发送消息，也未在真实群做断网/重启演练。非工作日允许手动发送由后端测试覆盖，本次运行日期为工作日。

合成账号、团队和已完成批次保留便于复查；运行配置 Webhook 已清除。部署前仍需完成原版通知四表迁移；本次新增按钮不需要新 SQL。
