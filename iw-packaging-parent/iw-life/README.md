# iw-life：个人生活工具

作为 `iw-core` 的业务库运行，提供心愿清单、重要日期、收藏夹。应用账号仍由 `iw-auth` 负责。

所有接口要求登录，始终使用认证用户作为所有者；不支持家庭共享。内部路径为 `/life/wish/**`、`/life/important-date/**`、`/life/bookmark/**`，小程序完整入口加 `/auth-service` 前缀。

持久层使用参数化 JdbcTemplate，表名来自封闭枚举，所有读取、版本更新和删除均带 `user_id` 条件。新增请求以 `user_id + client_request_id` 去重，更新、状态切换、置顶和删除检查 revision。日期原始历法字段保留不变，使用当前依赖 Hutool 5.8.26 转换，按上海时区动态计算下一次发生日与应用内提示。

## 数据库准备

1. 在目标库只读执行 [00-preflight.sql](scripts/00-preflight.sql)，应返回零行。存在同名表时先比较结构，不得删除或盲目重建。
2. 执行 [01-schema.sql](scripts/01-schema.sql)，新增三张表，不修改现有业务表。
3. 执行 [02-verify.sql](scripts/02-verify.sql)：应有三张 InnoDB 表，三种 invalid 数量均为0。
4. 早期已建表且 `life_bookmark.url` 仍为 NOT NULL 的环境，执行 [03-bookmark-optional-url.sql](scripts/03-bookmark-optional-url.sql)；已有链接保留，新收藏只需名称。
5. 再部署包含本模块的 core，最后更新小程序。

开发自验阶段仅在本地 `iw_mixes` 执行了新表创建。2026-10-28 用户反馈已执行生产脚本、完成发布并验证通过；该生产结果来自用户验收反馈。回退应用版本不等于删除新工具数据；不要以 DROP TABLE 作为常规回退步骤。

## 测试

`LifeDatesTest` 使用固定时钟覆盖公历、农历、闰月、月底回退、提示与支持范围；独立样本来源为[香港天文台2026年对照表](https://www.hko.gov.hk/tc/gts/time/calendar/pdf/files/2026.pdf)和[2023年对照表](https://www.hko.gov.hk/tc/gts/time/calendar/pdf/files/2023.pdf)。`LifeHttpContractTest` 覆盖认证和项目真实响应包装器。

`LifeMysqlTest` 只在显式设置 `IW_LIFE_TEST_JDBC` 时运行，限制为本机 `iw_mixes` 或 `iw_life_test`。连接凭据通过 `IW_LIFE_TEST_USER`、`IW_LIFE_TEST_PASSWORD` 环境提供，不写入源码、参数或报告。测试只在三张新表内操作 `LIFE-AC-20261028-` 夹具：常规测试回滚，并发用例在 finally 清除自己的记录；不调用或修改真实用户、家庭、账单和钱包。

在后端仓库根目录，检查环境和 `.mvn` 的仓库覆盖设置后，以 Java17运行：

```bash
/usr/local/maven/apache-maven-3.8.6/bin/mvn \
  -s /tmp/iw-life-settings.xml -Dmaven.repo.local=/usr/local/maven/repository \
  -pl iw-packaging-parent/iw-life -am \
  '-Dtest=Life*Test' -Dsurefire.failIfNoSpecifiedTests=false test
```

`/tmp/iw-life-settings.xml` 须先创建为不含凭据的中性 Maven settings，并显式保留统一本地仓库；不加载用户级 settings。未配置真实 MySQL 环境时应如实记录数据库用例跳过，不把跳过视为通过。

启动和重启仍只使用 `scripts/local-backend.sh`。先用上述规范 Maven 命令完成 core 打包，再设置 `IW_LOCAL_SKIP_BUILD=1`；必要时通过新增的 `IW_CORE_JAR` 指定已核实的绝对 JAR 路径。默认选择最新构建时间的 JAR，不再按版本文件名字典序选历史包。
