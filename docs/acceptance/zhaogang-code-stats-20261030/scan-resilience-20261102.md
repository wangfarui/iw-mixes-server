# 2026-11-02 十月扫描失败与读取优化

## 根因与证据

用户截图对应本地任务 6，月份 2026-10，2026-11-02 11:02:28 开始、11:02:32 失败，stage=DONE，error_code=InternalError，error_message 为 Can't invoke function. function = DescribeMyDepots。这是 CODING 上游返回的内部错误，不能据此判定 PAT 权限不足。并行的 11 月任务 5 已通过同一绑定态发现 232 仓库、9566 分支。此前仓库响应包装解析缺陷已另行修复，此次失败发生在收到成功响应之前。

## 本次修改

- CodingOpenApiClient：对 DescribeMyDepots、DescribeProjectDepotBranches、DescribeGitCommitsInPage、DescribeGitCommitDiff 四类只读动作的 InternalError、RequestLimitExceeded、HTTP 5xx 和 IOException 做最多 3 次总尝试，间隔 200/400ms；每次调用分别获取和释放现有限流许可。权限错误与写操作不重试，线程取消不重试；耗尽后提供中文失败提示。
- CodeScanWorker / CodeScanReadAhead：原来每个仓库逐分支串行请求首屏；现在每批最多 4 个分支并发预取首屏（亦受配置的 PAT 并发上限约束）。同一分支后续分页依次读取，结果由单个扫描线程按原顺序去重、聚合和写库；没有并发修改 job/提交累加器。批次有界，关闭会取消未完成读取，等待时每秒检查取消；所有实际 HTTP 调用仍经过 PAT 并发和现有 QPS 门控。未宣称整体提速固定四倍。
- 我的代码/团队代码页：没有可展示统计时，终止状态明确提示重试；仅活动任务展示后台继续和加载图标；我的代码未就绪时不再同时展示“没有已归属提交”。

## 验证

完整 iw-external Maven 测试 263 项，0 失败、0 错误、0 跳过。新增覆盖临时失败两次后成功、重试上限、权限错误不重试、四个读取同时开始、批次超过上限拒绝、关闭取消。Web type-check、build、check:zhaogang-release-notes、test:zhaogang-code-stats-range 均退出 0；git diff --check 通过。

真实浏览器先重启 Vite 清除旧模块缓存，确认当前服务返回新版页面。页面选择 2026-10 后新任务 7 于 11:18:48 开始；11:19:40 已进入 COMMITS，232/232 仓库、9567 分支、失败仓库 0，越过原先 DescribeMyDepots 失败点。未强造上游 InternalError，真实此次请求是否使用重试不做推断；重试分支由 HTTP fixture 回归覆盖。

运行版本：Java 17.0.9、Maven 3.8.6（显式 /usr/local/maven/repository、独立最小 settings）、Node 18.17.0、npm 9.8.1、Vite 5.4.21。external PID 23718、core PID 23541；运行 JAR iw-external-0.2.1.jar 时间 2026-11-02 11:14:26 +0800。后端 HEAD 0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3，Web HEAD d821532cfc9209085e6436093f52df5c9764547f，包含本地未提交修改。

## 边界与数据状态

本次未完成十月全量扫描及同范围优化前后耗时对比，不宣称月度业务验收完成。用户此前实际发起的任务 5 因必要的本地重启按心跳恢复成为 INTERRUPTED；失败任务 6 保留。当前十月任务 7 保留继续运行，相关实际扫描数据未作为合成测试数据清除。新增测试只用隔离 HTTP fixture 和内存替身，不产生数据库测试数据。未操作 CODING 写接口、未改凭证、未提交/推送/发布。
