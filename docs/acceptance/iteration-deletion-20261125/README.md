# 迭代工时明细、工时删除与事项同步删除验收

日期：2026-11-25。范围：`iw-mixes-web-platform` 与 `iw-mixes-server/iw-packaging-parent/iw-external`。本验收记录对应的功能和版本清单已完成；Git 交付状态以对应分支提交历史为准，生产尚未发布。

## 已确认范围

- 用户确认在迭代事项“已登记工时”提供明细和单条删除；明细只查询 Dev Hub 本地数据表，覆盖 Dev Hub 登记及主动同步 CODING 时保存的记录。
- 单条和批量移除事项均提供默认不勾选的“同时删除 CODING 事项”。展示实际本地子树数量及可展开清单；父子重复选择去重，标识“已选/随父事项移除”。不展示跨迭代影响提示。
- 同步删除失败保留对应本地事项及其父级，其它分支继续；删除迭代仍只删除本地数据。

## 实现与数据库要求

- 工时 GET/DELETE：`/external-service/api/zhaogang/iterations/{id}/issues/{issueId}/worklogs[/{worklogId}]`。GET 不调用 CODING；DELETE 明确未同步记录只移除本地审计，已同步记录以远端工时 ID 调用删除并归还剩余工时。
- 关联、补链和主动同步保存逐条工时。登记成功后尝试回读匹配真实工时 ID；回读失败不重试已接受登记。历史无 ID 审计仅在唯一匹配时补链，有歧义时禁止直接删除，需先同步核对。
- 事项删除请求增加 `deleteCoding`，默认 false；Web 提交 `expectedIssueIds` 校验完整本地范围。远端父事项删除前核对 CODING 子项范围，避免级联删除未确认事项。部分成功按事项反馈原因及权限信息。
- 确认删除事实和团队删除版本同时入库。报表 SQL 排除远端已确认删除记录；本地迭代读取也排除相同远端工时 ID，迟到同步不能使已删除记录重新展示。事项删除按项目/编号保存事实，不猜内部事项 ID。
- 旧环境须先执行 [迁移 SQL](../../../iw-packaging-parent/iw-external/scripts/iw-external-migrate-20261125-iteration-worklog-deletion.sql)，再启动新版后端；新环境 [初始化 SQL](../../../iw-packaging-parent/iw-external/scripts/iw-external-init.sql) 已包含最终结构。历史只存汇总的事项需要主动“同步 CODING 事项”一次补齐明细。

## 验证记录

| 编号 | 验证 | 结果与证据边界 |
| --- | --- | --- |
| AC-01 | Web 迭代专项脚本 | 通过：本地 GET/DELETE 路径、同步删除开关及预期范围 payload、隐藏子树展开、父子选择去重及清单深度。`npm run test:zhaogang-iteration` |
| AC-02 | Web 类型检查与生产构建 | 通过：`npm run type-check`、`npm run build`；构建保留既有大 chunk、PDF eval 和 NODE_ENV 提示。 |
| AC-03 | 后端 Dev Hub 范围 | 通过：510 项，其中 498 通过、12 项 opt-in 未启用。显式启用了本次迭代 MySQL 用例和原有工时镜像 MySQL 用例。使用 Java 17、Maven 3.8.6、显式最小 settings 与 `/usr/local/maven/repository`。 |
| AC-04 | 真实隔离 MySQL 8.0.31 | 通过：同一远端 ID 重复同步不产生重复明细；Dev Hub 登记与同步快照归并；缺项隐藏但保留审计；删除租约互斥；跨迭代删除事实生效；迟到同步不重新展示；删除版本幂等。 |
| AC-05 | 增量迁移 | 通过：在新建隔离库恢复旧表结构、插入历史审计后运行迁移两遍，验证新增字段、唯一索引、删除事实表以及原审计 ID/工时/状态/登记人保持。未迁移实际业务库或生产库。 |
| AC-06 | 浏览器真实组件 + 模拟接口 | 通过：初次打开明细只发 GET；展示本地来源/同步来源、登记人及工时；模拟 DELETE 失败后两条记录保留；重试成功后剩一条且合计 1.5h。没有真实 CODING 写入。 |
| AC-07 | 浏览器批量确认 | 通过：勾选父子两项，实际范围三项，CODING 两项、本地一项；清单缩进及标识正确；确认事件携带去重范围 `[1,2,3]`；重新打开时同步删除默认关闭。 |
| AC-08 | 390 × 844 窄屏 | 通过：长中文标题、25 个子项（共 26 项）场景，弹框宽 362px、高约 684px，确认按钮底部约 735px，保持可见；内容独立滚动。 |
| AC-09 | iw-external 全模块回归 | 未全绿：534 项，1 项错误、12 项跳过。唯一错误为无关 `RemoteShareBinaryStoreTest` 的 `DiskLowException`，触发既有最低可用磁盘比例保护；未修改远程共享模块或降低阈值。随后 Dev Hub 范围回归全绿。 |
| AC-10 | 真实 CODING / 生产验收 | 未执行：未使用真实 PAT 创建、登记或删除事项/工时；未部署、未触发构建、未执行生产迁移。真实账号删除权限、CODING 现场返回及剩余工时变化仍需受控环境验证。 |

截图已打开检查：[工时明细](screenshots/worklog-details.png)、[批量移除清单](screenshots/batch-remove.png)。截图来自正式 Vue 组件挂载及合成数据，不代表真实 CODING 数据或完整业务 API 联调通过。

## 环境与残留

本次 MySQL 使用 `/private/tmp/devhub-delete-mysql` 独立数据目录、23325 端口及专用 socket；测试库仅包含合成数据。未读取或修改实际业务数据库。正式本地后端原本停止，本次未启动或重启；浏览器验证使用当前 Web Vite 开发服务及模拟响应。测试结束停止本次创建的 MySQL 和 Vite 进程；隔离测试数据目录保留用于复查。

工作期间发现 Web 仓库新增其它会话的 `docs/plans/devhub-statistics-20261125/`，未修改该目录。

## v2.3.0 版本清单验证

本次会话升级版本为 `2.3.0`，新 `releaseId` 为 `zg-2026-11-25-01`；预计发布时间 `2026-11-25T14:50:28+08:00`，不代表已部署。更新说明仅包含本会话完成的工时明细/删除和事项可选同步删除两项能力，其它会话的统计方案不纳入。

本轮已通过 `npm run test:zhaogang-release-notes`、`npm run check:zhaogang-release-notes`、`npm run type-check`、`npm run build`。浏览器实际访问本地 `/zhaogang` 可正常显示公开绑定入口，静态清单 HTTP 200 且指向 2.3.0。未使用真实 CODING 绑定态，因此本轮未完成真实账号的新版本红点与已读回执验收；未模拟或写入真实已读接口。后端代码、数据库未因本轮版本号调整新增变化，未重新运行后端测试。实际业务库迁移及生产发布仍未执行。
