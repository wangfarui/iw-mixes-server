# iw-mixes-server deployment

Production runs two Java services:

- `iw-core`: auth, bookkeeping, eat and points in one JVM. Deploy it to the private 2C/8G server.
- `iw-external`: public external APIs and integrations. Deploy it to the public 2C/2G server.

MySQL, Redis and Nginx stay on the public server. Nacos, RocketMQ broker and the standalone Spring Cloud Gateway are no longer required.

## First server setup

1. Install JDK 17 on both servers.
2. Create directories:

   ```bash
   mkdir -p /opt/iw-mixes-server /etc/iw-mixes /var/log/iw-mixes /opt/iw-mixes-server/iw-external/data/remote-share
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

## Production release from the local workspace

GitHub Actions deployment is retired. Run from the repository root:

```bash
bash scripts/deploy-prod-local.sh deploy core
bash scripts/deploy-prod-local.sh status core
```

Use `external` or `all` only when those services also need updating. The script builds the current local workspace, including uncommitted changes; it does not check out a GitHub revision.

The build uses JDK 17, `/usr/local/maven/apache-maven-3.8.6/bin/mvn`, `/usr/local/maven/repository`, and temporary neutral Maven settings. Repository conflicts in the environment or project `.mvn` configuration stop the build. `clean package` clears historical artifacts, and the selector refuses to choose when more than one release JAR remains. The selected path and SHA-256 appear before upload.

Default SSH aliases are `aliyun87` for core and `aliyun183` for external. Override them with `IW_LOCAL_CORE_HOST` and `IW_LOCAL_EXTERNAL_HOST`. Existing local SSH configuration supplies authentication and jump-host settings.

The script uploads the JAR and `deploy/scripts/deploy-jar.sh`, which updates the server symlink and restarts the chosen service. `restart` only restarts the existing JAR; it does not rebuild or upload code. `status` only checks service/process state; verify the business endpoint separately.

Database scripts are not run automatically. Apply the feature's schema steps before deploying its code. See [LIFE_TOOLS_RELEASE.md](LIFE_TOOLS_RELEASE.md) for the life-tools release and [OPERATIONS.md](OPERATIONS.md) for logs.

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
