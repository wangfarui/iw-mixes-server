# iw-mixes-server 运维与日志排查

本文档整理生产环境常用的服务状态、日志查看和接口故障排查命令。

## 服务器约定

生产当前有两台机器：

| 机器 | 说明 | 主要服务 |
| --- | --- | --- |
| 外网机器 | 有公网 IP | Nginx、MySQL、Redis、`iw-external` |
| 内网 Java 机器 | 只能内网访问 | `iw-core` |

下面命令默认你本地已经配置过 SSH 别名：

```bash
ssh iw-public
ssh iw-core
```

如果没有 SSH 别名，就把命令替换成真实登录方式，例如：

```bash
ssh root@47.120.xx.xx
ssh -J root@47.120.xx.xx root@172.22.61.87
```

## 生产配置文件位置

生产敏感配置不提交到 GitHub，统一放在服务器的 `/etc/iw-mixes/` 目录，由 systemd 启动 Java 服务时读取。

### 快速索引

| 配置/文件 | 所在机器 | 生产路径 | 说明 |
| --- | --- | --- | --- |
| `iw-core` 环境变量 | 内网 Java 机器 | `/etc/iw-mixes/iw-core.env` | `iw-core` 的端口、数据库、Redis、内部密钥、阿里云文件/短信/邮件等配置 |
| `iw-external` 环境变量 | 外网机器 | `/etc/iw-mixes/iw-external.env` | `iw-external` 的端口、数据库、Redis、AI、博客访问、天气、热榜、钉钉等配置 |
| `iw-core` systemd unit | 内网 Java 机器 | `/etc/systemd/system/iw-core.service` | 定义 `iw-core` 启动命令、工作目录和 `EnvironmentFile` |
| `iw-external` systemd unit | 外网机器 | `/etc/systemd/system/iw-external.service` | 定义 `iw-external` 启动命令、工作目录和 `EnvironmentFile` |
| 当前 Jar 软链接 | 两台机器分别存在 | `/opt/iw-mixes-server/<service>/<service>.jar` | systemd 实际启动的 Jar 软链接 |
| 历史发布包 | 两台机器分别存在 | `/opt/iw-mixes-server/releases/<service>/` | GitHub Actions 发布后保留的最近 Jar |
| OOM dump | 两台机器分别存在 | `/var/log/iw-mixes/*.hprof` | Java 内存溢出时生成，普通运行日志不在这里 |
| 完整 Nginx 配置 | 外网机器 | `/etc/nginx/nginx.conf` | 当前生产若使用完整配置文件，就改这个 |
| 拆分 Nginx 配置 | 外网机器 | `/etc/nginx/conf.d/iw-mixes-server.conf` | 仅在采用拆分 conf.d 方式时使用；不要和完整配置重复启用 |

仓库内的模板文件：

```text
deploy/env/iw-core.env.example
deploy/env/iw-external.env.example
deploy/systemd/iw-core.service
deploy/systemd/iw-external.service
deploy/nginx/nginx.conf
deploy/nginx/iw-mixes-server.conf
```

### 查看配置文件

查看 `iw-core` 生产配置：

```bash
ssh iw-core
ls -l /etc/iw-mixes/iw-core.env
sed -n '1,120p' /etc/iw-mixes/iw-core.env
```

查看 `iw-external` 生产配置：

```bash
ssh iw-public
ls -l /etc/iw-mixes/iw-external.env
sed -n '1,160p' /etc/iw-mixes/iw-external.env
```

不要把 `.env` 文件内容直接粘贴到聊天、GitHub issue 或提交记录中，里面通常包含数据库密码、Redis 密码和第三方密钥。

### 修改配置流程

修改 `iw-core` 配置：

```bash
ssh iw-core
vim /etc/iw-mixes/iw-core.env
chmod 600 /etc/iw-mixes/iw-core.env
systemctl restart iw-core
journalctl -u iw-core -n 100 --no-pager
```

修改 `iw-external` 配置：

```bash
ssh iw-public
vim /etc/iw-mixes/iw-external.env
chmod 600 /etc/iw-mixes/iw-external.env
systemctl restart iw-external
journalctl -u iw-external -n 100 --no-pager
```

`.env` 文件修改后必须重启对应 Java 服务才会生效；只执行 `systemctl reload` 不会让 Spring Boot 重新读取环境变量。

修改 systemd unit 后需要重新加载 systemd：

```bash
systemctl daemon-reload
systemctl restart iw-core
systemctl restart iw-external
```

实际操作时只重启改过的服务即可，不需要两个都重启。

修改 Nginx 配置后检查并重载：

```bash
nginx -t
systemctl reload nginx
```

当前生产如果使用 `/etc/nginx/nginx.conf` 这一份完整配置，就不要再额外启用 `/etc/nginx/conf.d/iw-mixes-server.conf`，否则可能出现重复 upstream、重复 server 或路由行为不一致。

### 配置项归属

两边都会出现的基础配置：

```text
JAVA_OPTS
IW_WEB_ENV
IW_WEB_API_PREFIX
IW_DB_*
IW_REDIS_*
IW_INTERNAL_SECRET
```

`iw-core.env` 常见专属配置：

```text
IW_CORE_PORT
IW_EXTERNAL_BASE_URL
IW_AUTH_APPLICATION_ACCOUNT_AES_KEY
IW_AUTH_MANAGED_SECRET_AES_KEY
ALIYUN_OSS_*
ALIYUN_SMS_*
ALIYUN_EMAIL_*
```

`iw-external.env` 常见专属配置：

```text
IW_EXTERNAL_PORT
IW_CORE_BASE_URL
IW_AI_DEFAULT_API_URL
IW_AI_DEFAULT_API_KEY
IW_AI_DEFAULT_MODEL
IW_AI_IMAGE_PROVIDER
IW_AI_IMAGE_API_BASE_URL
IW_AI_IMAGE_API_KEY
IW_AI_IMAGE_MODEL
IW_AI_IMAGE_SOURCE_BASE_URL
IW_EXTERNAL_DAILYHOT_*
BLOG_ACCESS_*
ALIYUN_ASR_*
IW_EXTERNAL_AMAP_KEY
IW_EXTERNAL_UPTIMEROBOT_KEY
IW_EXTERNAL_EXCHANGE_RATE_KEY
IW_DING_TALK_*
```

一般情况下：

- MySQL 和 Redis 在外网机器，`iw-external.env` 可使用 `127.0.0.1`。
- `iw-core` 在内网 Java 机器，连接 MySQL、Redis、`iw-external` 时应使用外网机器的内网 IP。
- `iw-external` 调用 `iw-core` 时，`IW_CORE_BASE_URL` 应使用内网 Java 机器地址，例如 `http://172.22.61.87:18080`。
- `iw-core` 调用 `iw-external` 时，`IW_EXTERNAL_BASE_URL` 应使用外网机器内网地址，例如 `http://172.22.61.86:18006`。

## Java 服务日志

`iw-core` 和 `iw-external` 由 systemd 托管，没有继续使用 `nohup.log`。普通运行日志通过 `journalctl` 查看。

### 查看 iw-core

`iw-core` 部署在内网 Java 机器：

```bash
ssh iw-core
```

查看服务状态：

```bash
systemctl status iw-core --no-pager -l
```

查看最近 200 行日志：

```bash
journalctl -u iw-core -n 200 --no-pager
```

实时追踪日志：

```bash
journalctl -u iw-core -f
```

查看最近 1 小时日志：

```bash
journalctl -u iw-core --since "1 hour ago" --no-pager
```

按时间段查看日志：

```bash
journalctl -u iw-core --since "2026-06-30 10:00:00" --until "2026-06-30 11:00:00" --no-pager
```

只看错误级别日志：

```bash
journalctl -u iw-core -p err -n 100 --no-pager
```

查看更接近应用原始输出的日志：

```bash
journalctl -u iw-core -o cat -n 200 --no-pager
```

### 查看 iw-external

`iw-external` 部署在外网机器：

```bash
ssh iw-public
```

查看服务状态：

```bash
systemctl status iw-external --no-pager -l
```

查看最近 200 行日志：

```bash
journalctl -u iw-external -n 200 --no-pager
```

实时追踪日志：

```bash
journalctl -u iw-external -f
```

查看最近 1 小时日志：

```bash
journalctl -u iw-external --since "1 hour ago" --no-pager
```

只看错误级别日志：

```bash
journalctl -u iw-external -p err -n 100 --no-pager
```

## Nginx 日志

Nginx 部署在外网机器：

```bash
ssh iw-public
```

实时查看访问日志：

```bash
tail -f /var/log/nginx/access.log
```

实时查看错误日志：

```bash
tail -f /var/log/nginx/error.log
```

查看最近的 API 请求：

```bash
grep 'api.itwray.com' /var/log/nginx/access.log | tail -n 100
```

查看最近的 4xx/5xx 请求：

```bash
awk '$9 ~ /^[45][0-9][0-9]$/ {print}' /var/log/nginx/access.log | tail -n 100
```

查看最近的 504 请求：

```bash
awk '$9 == 504 {print}' /var/log/nginx/access.log | tail -n 50
```

修改 Nginx 配置后检查并重载：

```bash
nginx -t
systemctl reload nginx
```

## 发布状态排查

查看服务是否正在运行：

```bash
systemctl is-active iw-core
systemctl is-active iw-external
```

查看当前 Jar 软链接：

```bash
readlink -f /opt/iw-mixes-server/iw-core/iw-core.jar
readlink -f /opt/iw-mixes-server/iw-external/iw-external.jar
```

查看保留的发布包：

```bash
ls -lh /opt/iw-mixes-server/releases/iw-core
ls -lh /opt/iw-mixes-server/releases/iw-external
```

查看服务启动参数和环境文件路径：

```bash
systemctl cat iw-core
systemctl cat iw-external
```

检查生产环境变量文件权限：

```bash
ls -l /etc/iw-mixes/iw-core.env /etc/iw-mixes/iw-external.env
```

不要把 `.env` 文件内容直接粘贴到聊天或 issue 中，里面通常包含数据库密码、Redis 密码和第三方密钥。

## 接口故障排查

### 502/504

先在外网机器看 Nginx 错误日志：

```bash
ssh iw-public
tail -f /var/log/nginx/error.log
```

同时在内网机器看 `iw-core`：

```bash
ssh iw-core
journalctl -u iw-core -f
```

检查 Nginx 能否访问内网 Java 服务：

```bash
ssh iw-public
curl -i http://172.22.61.87:18080/dict/version
```

检查 `iw-external` 本机端口：

```bash
ssh iw-public
curl -i http://127.0.0.1:18006/external-service/api/heartbeat
```

检查端口监听：

```bash
ss -lntp | grep -E '18080|18006|80|443'
```

如果 `curl` 内网服务不通，优先检查目标服务状态和防火墙；如果 `curl` 正常但域名 504，优先检查 Nginx `proxy_pass`、upstream 地址和超时配置。

### AI 图片生成失败

衣柜“衣物编辑”页的“AI优化”会由 `iw-external` 的参考图 provider module 同步生成，再由 `iw-core` 落位。先确认 `iw-external` 启动日志中 provider、model 与可信源图基础地址已解析；配置不完整时调用将返回 `CONFIGURATION_ERROR`。

```bash
journalctl -u iw-external -n 200 --no-pager | grep '参考图生成配置'
```

Gemini 配置示例：

```text
IW_AI_IMAGE_PROVIDER=gemini
IW_AI_IMAGE_API_BASE_URL=https://generativelanguage.googleapis.com/v1beta
IW_AI_IMAGE_API_KEY=<gemini-api-key>
IW_AI_IMAGE_MODEL=gemini-3.1-flash-image
IW_AI_IMAGE_SOURCE_BASE_URL=https://<trusted-image-host>/<trusted-prefix>/
```

如改用 OpenAI Image API：

```text
IW_AI_IMAGE_PROVIDER=openai
IW_AI_IMAGE_API_BASE_URL=https://api.openai.com/v1
IW_AI_IMAGE_API_KEY=Bearer <openai-api-key>
IW_AI_IMAGE_MODEL=gpt-image-2
IW_AI_IMAGE_SOURCE_BASE_URL=https://<trusted-image-host>/<trusted-prefix>/
```

修改 `/etc/iw-mixes/iw-external.env` 后只需重启 `iw-external`：

```bash
systemctl restart iw-external
journalctl -u iw-external -n 80 --no-pager
```

### 跨域问题

查看 API 响应头：

```bash
curl -i \
  -H 'Origin: https://web.itwray.com' \
  https://api.itwray.com/auth-service/dict/version
```

预检请求检查：

```bash
curl -i -X OPTIONS \
  -H 'Origin: https://web.itwray.com' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: iwtoken,content-type' \
  https://api.itwray.com/auth-service/dict/version
```

正常情况下响应头中应包含：

```text
Access-Control-Allow-Origin: https://web.itwray.com
Access-Control-Allow-Credentials: true
```

当前 Nginx 配置允许 `itwray.com` 及其任意子域名访问 `api.itwray.com`，同时保留 `localhost` 和 `127.0.0.1` 本地调试来源。

### 登录或接口 500

先看对应 Java 服务日志：

```bash
journalctl -u iw-core -n 300 --no-pager
```

如果错误和数据库、Redis、第三方 Key 有关，再检查环境变量文件是否存在、权限是否正确，以及服务是否重启加载了最新配置：

```bash
systemctl cat iw-core
systemctl restart iw-core
journalctl -u iw-core -n 200 --no-pager
```

## GitHub Actions 发布失败排查

GitHub Actions 中部署失败时，优先看失败 job 的最后一个 deploy step。服务器上可配合查看：

```bash
journalctl -u iw-core -n 100 --no-pager
journalctl -u iw-external -n 100 --no-pager
```

如果是 SSH 连接失败，先在本地验证：

```bash
ssh iw-public 'whoami'
ssh iw-core 'whoami'
```

如果本地可以但 Actions 不行，检查 GitHub Secrets 中的 `IW_DEPLOY_SSH_KEY` 是否是私钥全文，并确认对应公钥同时存在于外网机器和内网机器发布用户的 `~/.ssh/authorized_keys`。

## 日志空间

查看磁盘空间：

```bash
df -h
```

查看 journal 占用：

```bash
journalctl --disk-usage
```

手动清理较旧的 journal 日志：

```bash
journalctl --vacuum-time=14d
```

生产上如果频繁排查日志空间，建议后续统一配置 `/etc/systemd/journald.conf` 的 `SystemMaxUse` 和 `MaxRetentionSec`，避免 journal 无限增长。
