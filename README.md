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

**Config file lookup:**

1. `OPENRUNE_CONFIG` — path to your YAML file. If set, the file **must** exist; startup fails otherwise.
2. Otherwise `./central-config.yaml`, relative to the process working directory.

If no file exists at the default path, only environment variables (and built-in defaults for optional settings) apply. Note that this makes `database.host` blank, which starts an **embedded** PostgreSQL rather than connecting to your database — so if Central comes up with an empty database, check that your config file is actually being found.

Every key is defined in [`CentralConfig.kt`](central-app/src/main/kotlin/dev/or2/central/config/CentralConfig.kt) (YAML path + `@ConfigAlias` env var name). Copy [`central-config.example.yaml`](central-config.example.yaml) as a starting point.

Optional HTTP port before engine start: `OPENRUNE_HTTP_PORT` or `openrune.http.port` (otherwise see `openrune-central/src/main/resources/application.yaml`).

### Migrating an old config file

Earlier builds used a flatter YAML schema. Keys that no longer match are **silently ignored** by Hoplite, so a stale file looks fine and then fails at runtime — an old `db:` block leaves `database.host` empty, for example.

On Pterodactyl, [`modules/config/start.sh`](deploy/pterodactyl/modules/config/start.sh) rewrites the file in place on startup before the generator runs, so hand-managed files (`OPENRUNE_WRITE_CONFIG=0`) are fixed too. It backs the original up as `central-config.yaml.old-schema.<timestamp>` and prints `[Config] Migrated …`. It is a no-op once the file is current, and can be turned off with `OPENRUNE_MIGRATE_CONFIG=0`.

| Old | New |
| --- | --- |
| `openrune.db` | `openrune.database` |
| `openrune.db.requireCredentials` | removed — Hikari always sends user/password |
| `openrune.sessionsTtlMs` | `openrune.session.ttlMs` |
| `openrune.worldsLinkPort` | `openrune.worldLink.port` |
| `openrune.worldsLinkSoBacklog` | `openrune.worldLink.soBacklog` |
| `openrune.worldsLinkReadTimeoutSeconds` | `openrune.worldLink.readTimeoutSeconds` |
| `openrune.onlineSampleIntervalSeconds` | `openrune.analytics.onlineSampleIntervalSeconds` |
| `openrune.badWordsRemoteUrl` | `openrune.badWords.remoteUrl` |
| `openrune.badWordsRefreshMinutes` | `openrune.badWords.refreshMinutes` |
| `javConfig.configProps: \|` block text | `javConfig.configProps` map |
| `configProps.param.17: <url>` | `configProps.param=17: <url>` |
| `openrune.cloudflared` | removed — `CLOUDFLARED_*` env only |

Where a file has both forms (`sessionsTtlMs` *and* `session:`), the nested block wins and the flat key is dropped.

### Database

Central **always** needs a reachable Postgres database. It does **not** invent a localhost database for you.

You can connect in two ways:

#### A. Host + database name + credentials (typical)

```yaml
openrune:
  database:
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
  database:
    user: openrune
    password: your-secret
```

Env: `OPENRUNE_JDBC_URL` plus `OPENRUNE_DB_USER` / `OPENRUNE_DB_PASSWORD`.

`openrune.jdbc.url` wins over `openrune.database.host` / `.port` / `.name` when both are set. User and password may also be set under `jdbc:` directly, which then takes priority over `database:`. Hikari always sends a username and password; the defaults are `postgres` and an empty string, so for trust/peer authentication set `user` to the trusted role and leave `password` empty.

| Key | Env | Notes |
| --- | --- | --- |
| `openrune.jdbc.url` | `OPENRUNE_JDBC_URL` | Full JDBC URL |
| `openrune.database.host` | `OPENRUNE_DB_HOST` | Required if JDBC URL omitted |
| `openrune.database.port` | `OPENRUNE_DB_PORT` | Default `5432` |
| `openrune.database.name` | `OPENRUNE_DB_NAME` | Required if JDBC URL omitted |
| `openrune.database.user` | `OPENRUNE_DB_USER` | Default `postgres` |
| `openrune.database.password` | `OPENRUNE_DB_PASSWORD` | Default empty |
| `openrune.database.poolSize` | `OPENRUNE_DB_POOL_SIZE` | Hikari pool size (1–128, default `10`) |

### Deployment examples

**Dedicated Central database (production):**

```yaml
openrune:
  jdbc:
    url: jdbc:postgresql://central-db.internal:5432/openrune_central
  database:
    user: openrune
    password: your-secret
  worldLink:
    port: 9091
```

**Shared Postgres with the game (local dev):**

```yaml
openrune:
  jdbc:
    url: jdbc:postgresql://127.0.0.1:5432/openrune
  database:
    user: openrune
    password: openrune
  worldLink:
    port: 9091
```

Point game worlds at this Central host for world-link auth. Central does not start Postgres; it only connects.

### Other settings

| YAML path | Environment variable | Purpose |
| --- | --- | --- |
| `openrune.serverName` | `OPENRUNE_SERVER_NAME` | Realm name in the login welcome message |
| `openrune.auth.passwordHasher` | `OPENRUNE_AUTH_PASSWORD_HASHER` | `bcrypt` or `argon2` |
| `openrune.auth.bcryptCost` | `OPENRUNE_AUTH_BCRYPT_COST` | bcrypt cost (default `12`) |
| `openrune.auth.argon2Iterations` | `OPENRUNE_AUTH_ARGON2_ITERATIONS` | Argon2 time cost for new hashes |
| `openrune.auth.argon2MemoryKib` | `OPENRUNE_AUTH_ARGON2_MEMORY_KIB` | Argon2 memory cost for new hashes |
| `openrune.session.ttlMs` | `OPENRUNE_SESSIONS_TTL_MS` | Session sweep TTL (ms) |
| `openrune.worldLink.port` | `OPENRUNE_WORLDS_LINK_PORT` | World-link TCP port |
| `openrune.worldLink.soBacklog` | `OPENRUNE_WORLDS_LINK_SO_BACKLOG` | TCP listen backlog |
| `openrune.worldLink.readTimeoutSeconds` | `OPENRUNE_WORLDS_LINK_READ_TIMEOUT_SEC` | World connection read timeout |
| `openrune.analytics.onlineSampleIntervalSeconds` | `OPENRUNE_ONLINE_SAMPLE_INTERVAL_SEC` | `online_samples` interval |
| `openrune.http.port` | `OPENRUNE_HTTP_PORT` | HTTP port |
| `openrune.http.trustProxy` | `OPENRUNE_HTTP_TRUST_PROXY` | Trust `X-Forwarded-*` from proxy/tunnel |
| `openrune.javConfig.revision` | `OPENRUNE_JAV_CONFIG_REVISION` | Remote jav revision |
| `openrune.javConfig.remoteUrlTemplate` | `OPENRUNE_JAV_CONFIG_URL_TEMPLATE` | Download URL (`%d` = revision) |
| `openrune.javConfig.configProps` | `OPENRUNE_JAV_CONFIG_PROPS` | Jav config overrides (YAML map) |
| `openrune.javConfig.refreshMinutes` | `OPENRUNE_JAV_CONFIG_REFRESH_MINUTES` | Jav cache refresh |
| `openrune.javConfig.httpTimeoutSeconds` | `OPENRUNE_JAV_CONFIG_HTTP_TIMEOUT_SEC` | Jav fetch timeout |
| `openrune.badWords.remoteUrl` | `OPENRUNE_BAD_WORDS_URL` | Remote bad-word list URL |
| `openrune.badWords.refreshMinutes` | `OPENRUNE_BAD_WORDS_REFRESH_MINUTES` | Bad-word refresh |
| `openrune.devWorld.autoCreate` | `OPENRUNE_DEV_WORLD_AUTO_CREATE` | Insert dev realm/world 255 on startup |
| `openrune.diagnostics.loginTimingLogs` | `OPENRUNE_LOGIN_TIMING_LOGS` | Per-phase login timing lines |
| `openrune.diagnostics.socialPmTraceLogs` | `OPENRUNE_SOCIAL_PM_TRACE_LOGS` | PM delivery trace lines |

Cloudflare Tunnel is **not** a `CentralConfig` key: `CLOUDFLARED_STATUS` and `CLOUDFLARED_TOKEN` are read from the environment by [`deploy/pterodactyl/modules/cloudflared/start.sh`](deploy/pterodactyl/modules/cloudflared/start.sh) only.

`javConfig.configProps` is a `Map<String, String>` merged into the remote `jav_config.ws` as `key=value`. `param` and `msg` lines are indexed by their second segment, so keep that index in the key — `param=17: https://host/worldslist.ws` replaces the remote `param=17=…` line and leaves `param=25`, `msg=ok`, etc. untouched. Any number of `param`/`msg` entries can be set this way. The legacy form `param: '17=<url>'` is still accepted, but only one such entry fits in the map.

## Without Pterodactyl

- Copy `central-config.example.yaml` → `central-config.yaml`, or set variables in the environment only.
- **HTTPS / Cloudflare:** `CLOUDFLARED_*` environment variables — see [deploy/cloudflare/README.md](deploy/cloudflare/README.md).
- **Trust proxy:** `openrune.http.trustProxy: true` behind nginx, tunnel, etc.
- **Jav overrides:** per-key entries under `openrune.javConfig.configProps`.

## HTTPS (Cloudflare Tunnel / reverse proxy)

Central serves **plain HTTP** locally. Public HTTPS is handled by Cloudflare or another reverse proxy. Set `openrune.http.trustProxy: true` (or `OPENRUNE_HTTP_TRUST_PROXY=true`) when behind a tunnel or proxy so `X-Forwarded-*` is honoured — it is **off** by default and is not enabled automatically by the tunnel module. World-link TCP (`openrune.worldLink.port`) is separate from HTTP tunneling.

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
