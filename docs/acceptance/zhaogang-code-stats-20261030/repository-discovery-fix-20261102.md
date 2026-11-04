# 2026-11-02 仓库发现修复与真实复验

结论：0 仓库是代码解析缺陷，当前 PAT 能读取仓库。本次真实请求 `DescribeMyDepots` 的响应结构为 `Response.Payload.Depots`，第一页有 100 项；原实现仅接受 Data 或根节点，误解析为空并将任务标记完成。

修复：`CodingOpenApiClient.myDepots` 支持 Payload，读取 Payload.Page.TotalRow；无已知仓库数组时明确抛错，避免将不兼容响应伪装为空结果。保留原有 Data 和根节点结构支持。新增两个回归用例，覆盖 Payload 分页与未知结构。临时诊断仅记录字段名、类型、数量，现已从源码移除。

真实复验：本人浏览器绑定态发起当前月 FULL 扫描，任务 ID 4，真实发现 232 个仓库、9565 个分支；最后采集阶段 COMMITS，读取并入库 4 条提交、3 个仓库局部读取失败。失败码 REQUEST_FAILED，含义为局部请求失败，不是已证实的令牌权限不足。未完成整月扫描，不认定统计 KPI、行数、完整覆盖、权限和性能 AC 全部通过。为遵守本次测试数据清理要求，结束测试服务会话后清理本次范围数据。

此前 S3 code=0 是交接文档错误：当前公共 GeneralApiCode.SUCCESS=200，真实 options 为 code=200、success=true。按用户最新指示修正断言并继续排查，不更改公共业务响应规范。

验证：Java 17.0.9、Maven 3.8.6，显式仓库 /usr/local/maven/repository，最小独立 settings；完整 iw-external Maven 测试 258 项、失败/错误/跳过均为 0。Web type-check、build、check:zhaogang-release-notes、test:zhaogang-code-stats-range 全部退出 0；Node 18.17.0、npm 9.8.1、Vite 5.4.21。git diff --check 通过。后端 HEAD 0d6d30194373e3df4ce7b1a9bac6b3430e33a3a3，Web HEAD d821532cfc9209085e6436093f52df5c9764547f；均保留未提交工作。

最终运行：通过 local-backend.sh 启动，core PID 16919、external PID 17101；external JAR 为 iw-external-0.2.1.jar，时间 2026-11-02 10:57:21 +0800。该最终 JAR 已去掉临时诊断；真实仓库扫描在相同解析逻辑且含临时字段结构诊断的前一运行包完成上述阶段。

清理：停止测试工作线程后，只删除 coding_team_id=4166569 的四张代码统计表测试数据。逐表查询 commit/identity/job/repo 均为 0。只对 Redis 127.0.0.1:6379/0 的 zhaogang:code:v1: 精确扫描所得键逐键 DEL，共删除 6 键，剩余 0。表结构保留；浏览器绑定凭证保留。未修改 CODING 资源或凭证表，未清空 Redis 数据库。未提交、推送、创建 PR 或发布。

本次修复文件为 CodingOpenApiClient.java、CodingOpenApiClientTest.java 和验收文档；此前本会话还修复 DefaultCodeStatsModule、CodeScanService、CodeScanWorker 的 Spring 构造器选择并新增 CodeStatsModuleContextTest。原始整体自动化验收仍为部分完成，逐项结论见交接文件 §6。
