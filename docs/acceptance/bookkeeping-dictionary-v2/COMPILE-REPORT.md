# 编译与离线检查记录

日期：2026-10-15（Asia/Shanghai）。实施范围为未提交工作区；只编译/解析源码，不启动应用、执行测试或SQL、不连库、不访问已运行服务。日志位于本目录evidence/，未包含凭据。

## 后端

仓库 `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-server`。执行前检查MAVEN_OPTS/MAVEN_ARGS/JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS无本地仓库覆盖；项目.mvn无冲突；固定仓库可写。不读取用户~/.m2/settings.xml。显式无凭据settings内容保存在[evidence/maven-settings.xml](evidence/maven-settings.xml)。实际最后命令：

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
 /usr/local/maven/apache-maven-3.8.6/bin/mvn \
 -s /tmp/iw-bookkeeping-dictionary-maven-settings.xml \
 -Dmaven.repo.local=/usr/local/maven/repository \
 -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am \
 -DskipTests test-compile
```

最后退出码0，20个reactor模块SUCCESS，BUILD SUCCESS；8.573秒，完成于2026-10-15T20:22:30+08:00。[完整日志](evidence/maven-test-compile.log)。复现时可将-s替换为本目录evidence/maven-settings.xml绝对路径；仍须先检查当前环境/.mvn。

初次失败：BookkeepingConsumeCategoryStatisticsDto缺setCategoryNames，已修正公共统计DTO字段继承；[原始日志](evidence/maven-initial-failure.log)保留，后续统一test-compile通过。注解处理/unchecked提示不影响退出码。

新增字典测试8个、快照/状态测试6个，更新付款人H2建表/注入与家庭删除测试；**只编译，未运行**。H2对MySQL BINARY/实际SQL运行兼容性尚未确认。

## Web

仓库 `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-web-platform`。先检查npm生命周期与保留的prebuild检查均为离线静态脚本，未启动Vite服务/应用或部署。

```sh
npm_config_cache=/tmp/iw-dictionary-npm-cache npm run type-check
npm_config_cache=/tmp/iw-dictionary-npm-cache npm run build
```

两条最终退出码均0：[类型日志](evidence/web-typecheck.log)、[构建日志](evidence/web-build.log)。完整Web构建含既有Skill Workbench等未提交内容，保留全部prebuild；Vite构建16.41秒，有大于500kB chunk警告，未改打包阈值掩盖。未浏览器验收。

初次npm默认缓存遇EPERM，改用/tmp缓存，未删除或绕过检查：[缓存错误](evidence/web-npm-cache-failure.log)。首次实际类型检查发现Vue模板内带TS类型的箭头函数解析错误，移入script helper修复：[原始类型错误](evidence/web-typecheck-initial-failure.log)。

## 微信小程序与交接静态检查

仓库 `/Users/wangfarui/workspaces/wfr/iw-mixes_ai/iw-mixes-app-wx`：

```sh
npm_config_cache=/tmp/iw-dictionary-npm-cache npm run check:routes
# 对git列出的本期修改/新增JS逐文件node --check，对JSON逐文件json.loads
```

最后退出码0，72页路由通过；28个JS语法、6个JSON解析通过。包括最后快捷标签数组/CSV兼容收尾。[路由日志](evidence/wx-routes.log)、[逐文件语法日志](evidence/wx-syntax.log)。**未打开微信开发者工具，未编译真实WXML/WXSS，未做模拟器或UI验收**。

后端仓库下另执行：

```sh
python3 scripts/acceptance/bookkeeping-dictionary-v2/check-static.py
node --check scripts/acceptance/bookkeeping-dictionary-v2/run.mjs
```

离线检查五个SQL引号/注释/括号、只读阶段写语句与写表范围，解析auth/bookkeeping Mapper XML（不解析外部DTD），核对64个cases/SCENARIOS/RESULTS场景ID。另用已安装sql-formatter的mysql方言做五文件词法解析（不是MySQL服务端语法/语义验证）。三仓库git diff --check及新增源码尾空白检查通过。最终输出见[evidence/offline-final.log](evidence/offline-final.log)。

当前Node18.17.0/npm9.8.1用于上述源码静态检查；验收runner尚未执行，下一会话按README使用Node20+。

## 证据解释

所有业务场景64/64为NOT_EXECUTED。编译通过只证明当前源码编译/解析成功，不能证明数据库迁移、安全边界、统计、钱包、任务幂等或页面交互通过。没有SQL、业务测试、API调用、应用启动、暂存、提交、推送或发布。manifest固定当前代码与脚本哈希；最终差异状态见manifest与离线日志。
