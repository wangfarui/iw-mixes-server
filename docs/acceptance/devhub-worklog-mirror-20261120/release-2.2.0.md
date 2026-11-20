# Dev Hub 2.2.0 版本清单验收

2026-11-20。使用 iw-zhaogang-release。当前版本指针为 `zg-2026-11-20-02`，展示版本 `2.2.0`，清单计划发布时间 `2026-11-20T20:58:47+08:00`。只更新 Web public/zhaogang-release-notes.json，保留 51 条既有记录，追加本版后共 52 条。未修改 package.json 版本；本次版本清单未提交、未推送、未部署。

## 实际更新说明

- 优化个人和团队工时加载，支持每 15 分钟自动同步和手动刷新。
- 首次同步优先展示近期工时，再按月补齐 2026 年 1 月以来的记录。
- 团队工时优先使用成员个人令牌，未绑定时使用团队管理令牌，同一成员跨团队复用统计结果。

## 验证

- npm run test:zhaogang-release-notes：通过。
- npm run check:zhaogang-release-notes：通过，currentReleaseId 正确，52 条记录。
- npm run type-check：通过。
- npm run build：通过；保留已有分包大小及 pdfjs eval 提示。
- 实际本地 Web 5196 + 隔离后端 18006：合成账号 101 正常绑定，页面标签 v2.2.0，新 ID 首次提示未读。点击后实际回执接口 GET 返回 code=200、read=true，标签红点消失；刷新后仍已读。
- 更新日志当前 v2.2.0 置顶，其次 v2.1.0、v2.0.3，共 52 条，发布时间严格非增序，没有“我知道了”按钮。
- 390×844 窄屏 document.scrollWidth=390，抽屉宽 390；截图已打开检查，无横向溢出。检查后恢复 1440×1000 桌面。刷新后控制台 error/warn 为空。

本地账号与 CODING 上游为既有合成验收数据；会话、已读接口和 MySQL 为实际后端，未伪造已读响应。本次未修改通知行为或故障降级逻辑，相关契约检查通过；没有为异常场景再次注入线上故障。真实 CODING 绑定态下的新版本标签仍需随 Web 发布后验收，不能将本地结果当作生产版本已升级。

## 线上迁移复验

用户已手动执行缺列迁移。原生产会话的工时 sync、report 均重新返回 HTTP 200 / 业务码 200 / success=true；当时历史初始化仍 running=true、partial=true，不代表全部补齐。Agent 没有执行生产 SQL、重启或部署。详细证据见 [线上故障排查](production-incident-20261120.md)。

本地后端此前停止，本次按既有隔离配置由 scripts/local-backend.sh 重新启动于 18006，仍使用独立库、Redis 及合成 CODING 服务，供后续查看。发布本版只需发布更新后的 Web 清单；无新增版本专属后端接口或数据库迁移。
