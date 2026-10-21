# 会员类型 V1 验证工具

实际结果见 [本次验收记录](../../../docs/acceptance/bookkeeping-membership-types-20261021-r1/RESULTS.md)。这些工具只操作明确创建的本地临时实例，不是生产运行器。

- `test-migration.py --execute --socket /private/tmp/iw-mtype-<run>/mysql.sock`：要求独立数据目录、关闭TCP、root无密码临时实例；只创建随机前缀的新schema和生成夹具，不读取应用库或认证配置。测试迁移授权门、唯一来源、异常待核对、Unicode、幂等、索引/金额断言及家庭4004阻断。临时schema在实例销毁时一并清理。
- `run-api.py --execute --credentials /private/tmp/iw-mtype-20261021-r1/credentials.json`：本次专用，固定loopback18011、MySQL13371、私有 `mtype_api_r1` 夹具库；需要普通测试账户a/b/c（40001/40002/40003）及空业务库。先由私有部署配置核实服务实际连接该库，再运行。脚本通过真实密码登录以及真实家庭创建/邀请/加入接口验证角色，不伪造token或身份。结果只打印断言摘要；凭据和完整认证响应只临时保留在私有目录，不能放入证据或Git。

本次临时服务由当前源码的隔离副本打包，再使用副本中与仓库相同的 `scripts/local-backend.sh` 启动，以避免覆盖正在使用的原 `target/iw-core-0.2.1.jar`。小程序通过当前源码副本连接18011，仅临时副本的开发地址不同；原项目配置与会话保留。自动化socket超时后，实际页面操作改用原生工具界面完成，失败尝试没有计为通过。

这些脚本非幂等的接口流程需要新的空夹具库；不要对已有家庭或真实用户重放。不得导入真实业务行作为默认夹具。本次只从既有本地dump提取结构（67张表），数据为仓库默认模板和本轮生成的普通测试用户。
