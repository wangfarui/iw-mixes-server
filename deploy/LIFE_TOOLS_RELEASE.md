# 生活工具生产修复步骤

日期：2026-10-28。

## 已确认的故障与修正

线上 `/auth-service/life/important-date/reminder-summary` 返回 HTTP 404，日志为 `NoResourceFoundException`，说明 core 未注册该接口，尚未进入业务数据库查询。

旧 `scripts/deploy-prod-local.sh` 在 package 后对所有历史 JAR 按文件名字典序取最后一个。本地验收包为 `iw-core-0.2.1.jar`，包含 `BOOT-INF/lib/iw-life-0.2.1.jar`；目录中 `iw-core-0.2.8-admin-settings-local.jar` 等历史包不含该模块，旧选择器可误选它们。该选包问题已用隔离夹具复现。2026-10-28 本次只读核验已确认：线上当前软链接为 `/opt/iw-mixes-server/releases/iw-core/iw-core-159b11c731c5.jar`，SHA-256 为 `e3d86075d726ac974dd2876963b33586cd25a104b000d40fba50ecdbb8af4183`，包内没有 iw-life。发布后的远程文件名使用 Git SHA，无法仅凭该名称推断当时本地误选包的原文件名。

当前修正：clean package 清除历史产物；只接受唯一候选 JAR；上传前显示路径、SHA-256；固定 Maven、仓库及中性 settings，并检查仓库冲突。隔离回归脚本 `bash scripts/test-deploy-prod-local.sh` 不连接生产、不上传或重启服务。

## 执行顺序

1. 在本地后端仓库核对本次工作区范围。脚本直接构建本地文件，Git 提交／推送不是打包前提。确保新 `iw-life/`、`iw-packaging-parent/pom.xml` 的模块注册、`iw-core/pom.xml` 的依赖及本次脚本修复都在当前目录。
2. 已执行并校验通过的数据库建表不重复运行。若生产三张表不存在，按 `iw-life/README.md` 的 00 检查、01 建表、02 验证顺序执行；03 只用于早期 URL 非空结构。
3. 在本地后端仓库执行：

   ```bash
   bash scripts/deploy-prod-local.sh deploy core
   bash scripts/deploy-prod-local.sh status core
   ```

   `deploy core` 构建、上传并重启 core。本次无需部署 external，也无需再次发布小程序。确认发布输出显示本次构建的 core JAR。
4. 只读核验线上包。以下命令使用默认 SSH 别名，若发布时覆盖了主机则使用实际值：

   ```bash
   ssh aliyun87 'readlink -f /opt/iw-mixes-server/iw-core/iw-core.jar'
   ssh aliyun87 'sha256sum /opt/iw-mixes-server/iw-core/iw-core.jar'
   ssh aliyun87 'jar tf /opt/iw-mixes-server/iw-core/iw-core.jar | grep "BOOT-INF/lib/iw-life-"'
   ```

   线上 SHA-256 应与本次脚本输出一致，JAR 列表应包含 iw-life。若包不匹配，先检查实际服务启动路径和发布目标。
5. 验证接口注册：

   ```bash
   curl -sS -i https://api.itwray.com/auth-service/life/important-date/reminder-summary
   ```

   不带登录 Token 时应为业务码401；不能再返回404。该检查仅证明路由和认证链存在，不能替代已登录的数据库业务验证。
6. 用已登录的小程序进入菜单，检查无「系统异常」，再检查三个生活工具的列表、只填名称保存收藏、公历／农历日期和置顶操作。

生产发布、线上包检查和业务复验由实际执行者确认；本次代码修复不等于已经更新生产。

## 本次修复验证

- 旧选择器在多个历史版本夹具中接受歧义候选，回归测试先失败；修正后拒绝多个或缺失候选，唯一业务 JAR 正常选中，sources/javadoc/original 不参与选择。
- `bash scripts/test-deploy-prod-local.sh` 通过；包括 clean package、指定仓库、中性 settings、环境及项目仓库冲突拦截，未连接生产。
- 通过同一 `build_modules core` 执行真实本地构建（仅构建，未调用 deploy_one），BUILD SUCCESS；唯一 JAR 为 `iw-core-0.2.1.jar`，包内含 `BOOT-INF/lib/iw-life-0.2.1.jar`。
- 此次本地包 SHA-256：`5baf3b3ab37c2e9458bca466f0af03e022532e5767296d3065e46d608d1a4d22`。重新构建后以本次脚本实时输出的哈希为准。
- 已执行远程只读包核验；没有生产上传、重启、SQL 写入或小程序发布。

## 后续验收

2026-10-28，用户确认按修正后的本地发布流程完成生产验证，并授权提交、推送本会话改动。该结果来自用户验收反馈。
