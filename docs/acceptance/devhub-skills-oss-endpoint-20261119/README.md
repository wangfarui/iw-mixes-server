# Skills 上传预览 OSS endpoint 修复

## 故障与根因

关联会话：`01a1f1af-bded-7542-8eef-469ff84caba3`。
本机 Skills CLI 2.0.3 执行 `preview` 稳定返回 503：`无法核对 Skills OSS 的版本保留设置`，未进入发布。

生产进程配置的 OSS endpoint 已包含当前 Bucket 名。OSS Java SDK 3.17.2 会再次拼接 Bucket，生成重复前缀的主机名。使用相同运行凭证向正确 Bucket 域名执行签名 `GET /?versioning` 返回 HTTP 200，版本状态为空（从未启用）；重复前缀地址访问失败。因此本次故障由 endpoint 配置格式引起，已排除版本查询权限缺失及实际启用版本控制。

## 改动

- `SkillPackageStore` 将包含当前 Bucket 的阿里云标准 OSS 域名归一为服务 endpoint，再创建 SDK 客户端；地域 endpoint 保持原行为，版本控制检查继续执行。
- 生产配置示例补充 Skills 配置和 endpoint 填写说明。
- 回归测试通过真实 SDK 的 `determineFinalEndpoint` 校验最终请求主机，覆盖地域、Bucket、内网及加速域名，不访问外部网络。

## 验证

Maven 使用 `/usr/local/maven/apache-maven-3.8.6/bin/mvn`、显式本地仓库 `/usr/local/maven/repository` 和临时 settings，未使用用户级 settings。

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" PATH="$(/usr/libexec/java_home -v 17)/bin:$PATH" \
/usr/local/maven/apache-maven-3.8.6/bin/mvn \
-s /tmp/devhub-skills-maven-settings.xml \
-Dmaven.repo.local=/usr/local/maven/repository \
-pl iw-packaging-parent/iw-external -am \
-Dtest=SkillPackageStoreTest,SkillLibraryTest \
-Dsurefire.failIfNoSpecifiedTests=false test
```

临时 settings 内容：`<settings><localRepository>/usr/local/maven/repository</localRepository></settings>`。

修复前：地址回归测试 4 项，3 项失败，断言捕获重复 Bucket 前缀。
修复后：共执行 10 项测试，全部通过；包含共享目录、浏览器/设备发布更新、版本并发与回放、删除权限和包校验。

## 生产状态

用户已确认手动修改线上 env 配置。本会话仅只读检查生产配置和 OSS 版本状态，未执行生产配置修改、重启或部署；未核实手动修改后的进程配置和线上上传结果。若尚未重启 `iw-external`，需重启后配置才会生效。未发布目标 Skill。
