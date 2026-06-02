# OpenRune Central Server

Kotlin/Ktor service for accounts, world-link auth, sessions, activity logs, and HTTP APIs used by OpenRune game worlds.

## Quick start

1. Copy [`central-config.example.yaml`](central-config.example.yaml) → `central-config.yaml` (next to the JAR or set `OPENRUNE_CONFIG`).
2. Configure Postgres (see [Database](#database) below).
3. Build and run:

```bash
./gradlew :openrune-central:shadowJar
java -jar openrune-central/build/libs/openrune-central-server.jar
```

Docker: see [Docker](#docker). Pterodactyl: see [Pterodactyl](#pterodactyl).

## Configuration

Central uses **two layers** merged into one effective config:

| Layer | Source | Role |
| --- | --- | --- |
| 1 (base) | Environment variables | Panel, Docker, shell — typical place for secrets |
| 2 (override) | `central-config.yaml` | Overrides any key that is set in the file |

When YAML changes a value that was already set in the environment, startup prints `[Config]` lines to the console.

**Config file lookup** (first match wins):

1. `OPENRUNE_CONFIG` — path to your YAML file
2. `./central-config.yaml` — process working directory
3. `central-config.yaml` — directory containing the running JAR

If no file exists, only environment variables (and built-in defaults for optional settings) apply.

Every key is listed in `CentralConfigKey.kt` (env var name + YAML path). Copy [`central-config.example.yaml`](central-config.example.yaml) as a starting point.

Optional HTTP port before engine start: `OPENRUNE_HTTP_PORT` or `openrune.http.port` (otherwise see `openrune-central/src/main/resources/application.yaml`).

### Database

Central **always** needs a reachable Postgres database. It does **not** invent a localhost database for you.

You can connect in three ways:

#### A. Host + database name + credentials (typical)

```yaml
openrune:
  db:
    host: db.example.com
    port: 5432
    name: openrune_central
    user: openrune
    password: your-secret
```

Env: `OPENRUNE_DB_HOST`, `OPENRUNE_DB_NAME`, `OPENRUNE_DB_USER`, `OPENRUNE_DB_PASSWORD` (optional `OPENRUNE_DB_PORT`).

#### B. Full JDBC URL + separate user/password

```yaml
openrune:
  jdbc:
    url: jdbc:postgresql://db.example.com:5432/openrune_central
  db:
    user: openrune
    password: your-secret
```

Env: `OPENRUNE_JDBC_URL` plus `OPENRUNE_DB_USER` / `OPENRUNE_DB_PASSWORD`.

When a JDBC URL is set, **user and password are not required by default** (see C).

#### C. JDBC URL only — no separate user/password

Use this for **trust / peer authentication**, **credentials embedded in the URL**, or other setups where Hikari should not send a separate username/password:

```yaml
openrune:
  jdbc:
    url: jdbc:postgresql:///openrune_central?user=openrune
  db:
    requireCredentials: false
```

Or only env:

```bash
export OPENRUNE_JDBC_URL='jdbc:postgresql://127.0.0.1:5432/openrune_central'
export OPENRUNE_DB_REQUIRE_CREDENTIALS=false
```

Explicit override: `openrune.db.requireCredentials: true` forces user/password even when using a JDBC URL.

| Key | Env | Notes |
| --- | --- | --- |
| `openrune.jdbc.url` | `OPENRUNE_JDBC_URL` | Full JDBC URL |
| `openrune.db.host` | `OPENRUNE_DB_HOST` | Required if JDBC URL omitted |
| `openrune.db.port` | `OPENRUNE_DB_PORT` | Default `5432` |
| `openrune.db.name` | `OPENRUNE_DB_NAME` | Required if JDBC URL omitted |
| `openrune.db.user` | `OPENRUNE_DB_USER` | Required unless credentials not required |
| `openrune.db.password` | `OPENRUNE_DB_PASSWORD` | Required unless credentials not required |
| `openrune.db.requireCredentials` | `OPENRUNE_DB_REQUIRE_CREDENTIALS` | Default `true` without JDBC URL; `false` when JDBC URL is set |
| `openrune.db.poolSize` | `OPENRUNE_DB_POOL_SIZE` | Hikari pool size (1–100, default `10`) |

### Deployment examples

**Dedicated Central database (production):**

```yaml
openrune:
  jdbc:
    url: jdbc:postgresql://central-db.internal:5432/openrune_central
  db:
    user: openrune
    password: your-secret
  worldsLinkPort: 9091
```

**Shared Postgres with the game (local dev):**

```yaml
openrune:
  jdbc:
    url: jdbc:postgresql://127.0.0.1:5432/openrune
  db:
    user: openrune
    password: openrune
  worldsLinkPort: 9091
```

Point game worlds at this Central host for world-link auth. Central does not start Postgres; it only connects.

### Other settings

| YAML path | Environment variable | Purpose |
| --- | --- | --- |
| `openrune.sessionsTtlMs` | `OPENRUNE_SESSION_TTL_MS` | Session sweep TTL (ms) |
| `openrune.worldsLinkPort` | `OPENRUNE_WORLD_LINK_PORT` | World-link TCP; `false` / `0` disables |
| `openrune.worldsLinkSoBacklog` | `OPENRUNE_WORLD_LINK_SO_BACKLOG` | TCP listen backlog |
| `openrune.worldsLinkReadTimeoutSeconds` | `OPENRUNE_WORLD_LINK_READ_TIMEOUT_SEC` | World connection read timeout |
| `openrune.worldsLinkMaxConnectionsPerIp` | `OPENRUNE_WORLD_LINK_MAX_CONN_PER_IP` | Per-IP cap |
| `openrune.worldsLinkMaxConnectionsTotal` | `OPENRUNE_WORLD_LINK_MAX_CONN_TOTAL` | Global cap |
| `openrune.worldsLinkHandlerThreads` | `OPENRUNE_WORLD_LINK_HANDLER_THREADS` | Worker threads |
| `openrune.worldsLinkHandlerQueueSize` | `OPENRUNE_WORLD_LINK_HANDLER_QUEUE` | Handler queue |
| `openrune.worldsLinkMaxFramesPerSecond` | `OPENRUNE_WORLD_LINK_MAX_FRAMES_PER_SEC` | Rate limit |
| `openrune.worldsLinkMaxFrameBurst` | `OPENRUNE_WORLD_LINK_MAX_FRAME_BURST` | Rate burst |
| `openrune.onlineSampleIntervalSeconds` | `OPENRUNE_ONLINE_SAMPLE_INTERVAL_SEC` | `online_samples` interval |
| `openrune.http.port` | `OPENRUNE_HTTP_PORT` | HTTP port |
| `openrune.http.trustProxy` | `OPENRUNE_HTTP_TRUST_PROXY` | Trust `X-Forwarded-*` from proxy/tunnel |
| `openrune.cloudflared.status` | `CLOUDFLARED_STATUS` | Cloudflare Tunnel enabled |
| `openrune.cloudflared.token` | `CLOUDFLARED_TOKEN` | Tunnel run token |
| `openrune.javConfig.revision` | `OPENRUNE_JAV_CONFIG_REVISION` | Remote jav revision |
| `openrune.javConfig.remoteUrlTemplate` | `OPENRUNE_JAV_CONFIG_URL_TEMPLATE` | Download URL (`%d` = revision) |
| `openrune.javConfig.configProps` / `configProps.*` | `OPENRUNE_JAV_CONFIG_PROPS` | Jav config overrides |
| `openrune.javConfig.refreshMinutes` | `OPENRUNE_JAV_CONFIG_REFRESH_MINUTES` | Jav cache refresh |
| `openrune.javConfig.httpTimeoutSeconds` | `OPENRUNE_JAV_CONFIG_HTTP_TIMEOUT_SEC` | Jav fetch timeout |
| `openrune.badWordsRemoteUrl` | `OPENRUNE_BAD_WORDS_URL` | Remote bad-word list URL |
| `openrune.badWordsRefreshMinutes` | `OPENRUNE_BAD_WORDS_REFRESH_MINUTES` | Bad-word refresh |
| `openrune.badWordsHttpTimeoutSeconds` | `OPENRUNE_BAD_WORDS_HTTP_TIMEOUT_SEC` | Bad-word fetch timeout |

## Without Pterodactyl

- Copy `central-config.example.yaml` → `central-config.yaml`, or set variables in the environment only.
- **HTTPS / Cloudflare:** `openrune.cloudflared.*` or `CLOUDFLARED_*` — see [deploy/cloudflare/README.md](deploy/cloudflare/README.md).
- **Trust proxy:** `openrune.http.trustProxy: true` behind nginx, tunnel, etc.
- **Jav overrides:** multiline `openrune.javConfig.configProps` or per-key `openrune.javConfig.configProps.*`.

## HTTPS (Cloudflare Tunnel / reverse proxy)

Central serves **plain HTTP** locally. Public HTTPS is handled by Cloudflare or another reverse proxy. Tunnel env/status + token auto-enable trust-proxy unless `openrune.http.trustProxy` is set explicitly. World-link TCP (`openrune.worldsLinkPort`) is separate from HTTP tunneling.

## HTTP API

Examples (adjust port to your deployment):

- `GET /worldslist.ws`, `GET /worlds.js` — world list payloads
- `GET /jav_config.ws` — proxied jav config with optional `javConfig.configProps` overrides

## Admin website (`/admin`)

Bundled SPA for local inspection and demos — **not** a supported production console. Use SQL or proper ops tooling for production. See README disclaimer in repo history; use at your own risk.

## Building

```bash
./gradlew :openrune-central:build
```

Java 21+. Some tests use embedded Postgres.

## Docker

Runtime-only image — build the JAR first:

```bash
./gradlew :openrune-central:shadowJar
cp openrune-central/build/libs/openrune-central-server.jar .
docker build -t openrune-central .
docker run --rm -p 8080:8080 -p 9091:9091 \
  -e OPENRUNE_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/openrune_central \
  -e OPENRUNE_DB_USER=openrune \
  -e OPENRUNE_DB_PASSWORD=openrune \
  openrune-central
```

Trust-auth example (no separate user/password):

```bash
docker run --rm -p 8080:8080 \
  -e OPENRUNE_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/openrune_central \
  -e OPENRUNE_DB_REQUIRE_CREDENTIALS=false \
  openrune-central
```

Custom JAR name: `docker build --build-arg JAR_FILE=my.jar -t openrune-central .`  
JVM tuning: `-e JAVA_OPTS=-Xmx512m`

## Pterodactyl

Import [`deploy/pterodactyl/egg-openrune-central.json`](deploy/pterodactyl/egg-openrune-central.json). Details: **[deploy/pterodactyl/README.md](deploy/pterodactyl/README.md)**.

Startup: **autoupdate → config → logcleaner → cloudflared → Java**. Panel console: `!help`, `stop`, `refresh …`. Ready when you see **`OpenRune Central is online`**.

With `OPENRUNE_WRITE_CONFIG=1` (default), the egg writes `central-config.yaml` from panel variables. Set `OPENRUNE_WRITE_CONFIG=0` to manage YAML yourself.

**JAR from GitHub Releases:** the egg **Central update** setting (Disabled / Automatic / Notification required). See **[deploy/pterodactyl/README.md](deploy/pterodactyl/README.md)**. Tag a release with `git tag v1.0.0 && git push origin v1.0.0` to publish the JAR via GitHub Actions.
