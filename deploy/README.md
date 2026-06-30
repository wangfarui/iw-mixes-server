# iw-mixes-server deployment

Production runs two Java services:

- `iw-core`: auth, bookkeeping, eat and points in one JVM. Deploy it to the private 2C/8G server.
- `iw-external`: public external APIs and integrations. Deploy it to the public 2C/2G server.

MySQL, Redis and Nginx stay on the public server. Nacos, RocketMQ broker and the standalone Spring Cloud Gateway are no longer required.

## First server setup

1. Install JDK 17 on both servers.
2. Create directories:

   ```bash
   mkdir -p /opt/iw-mixes-server /etc/iw-mixes /var/log/iw-mixes
   ```

3. Copy systemd units:

   ```bash
   cp deploy/systemd/iw-core.service /etc/systemd/system/iw-core.service
   cp deploy/systemd/iw-external.service /etc/systemd/system/iw-external.service
   systemctl daemon-reload
   systemctl enable iw-core
   systemctl enable iw-external
   ```

4. Copy env templates to the target server and fill real values:

   ```bash
   cp deploy/env/iw-core.env.example /etc/iw-mixes/iw-core.env
   cp deploy/env/iw-external.env.example /etc/iw-mixes/iw-external.env
   chmod 600 /etc/iw-mixes/*.env
   ```

5. Install the Nginx config on the public server and replace placeholders:

   ```bash
   cp deploy/nginx/iw-mixes-server.conf /etc/nginx/conf.d/iw-mixes-server.conf
   nginx -t
   systemctl reload nginx
   ```

## GitHub Actions secrets

Set these repository secrets:

- `IW_DEPLOY_SSH_KEY`: private key allowed to SSH to both servers. The remote user needs passwordless `sudo` for `systemctl`.
- `IW_PUBLIC_HOST`, `IW_PUBLIC_USER`, `IW_PUBLIC_PORT`: public server SSH.
- `IW_CORE_HOST`, `IW_CORE_USER`, `IW_CORE_PORT`: private Java server SSH address reachable from the public server.

The workflow copies `iw-external` directly to the public server and copies `iw-core` through the public server as an SSH jump host.

Production log and incident commands are documented in [OPERATIONS.md](OPERATIONS.md).

## Manual release command

If GitHub Actions is unavailable, build locally with JDK 17:

```bash
mvn -pl iw-packaging-parent/iw-core,iw-packaging-parent/iw-external -am -DskipTests package
```

Then copy the Jar and run the deploy script on the target server:

```bash
bash deploy/scripts/deploy-jar.sh iw-core /tmp/iw-core.jar
bash deploy/scripts/deploy-jar.sh iw-external /tmp/iw-external.jar
```

## Local Nginx fallback

Local frontend debugging does not need Nginx by default. Use this only when testing `/external-service/wb/**` WebSocket or when you want to mirror the production Nginx entry locally.

On macOS with Homebrew:

```bash
brew install nginx
mkdir -p "$(brew --prefix)/etc/nginx/servers"
cp deploy/nginx/iw-mixes-server.local.conf "$(brew --prefix)/etc/nginx/servers/iw-mixes-server.local.conf"
nginx -t
brew services restart nginx
```

If the Homebrew `nginx.conf` does not include the `servers` directory, add this line inside the `http {}` block:

```nginx
include servers/*;
```

When local Nginx listens on `18000`, do not let `iw-core` also listen on `18000`. Run IDEA with:

- `IwCoreApplication`: Active profiles `dev`, env `IW_CORE_PORT=18080`, `IW_CORE_BASE_URL=http://127.0.0.1:18080`
- `IwExternalApplication`: Active profiles `dev`, env `IW_CORE_BASE_URL=http://127.0.0.1:18080`

`iw-external` still uses `18006`. Web and the old uni-app keep using `http://localhost:18000`.
