# OpenRune Central — Pterodactyl egg

Import `egg-openrune-central.json` into your panel nest.

**Install:** Reinstall once after importing the egg (downloads startup scripts + `cloudflared`). To refresh later, **Reinstall** or run `sh install.sh` in the container.

## Configuration from the panel

On each start the **config** module writes `central-config.yaml` from Startup variables (`OPENRUNE_WRITE_CONFIG=1` by default). Set **`OPENRUNE_WRITE_CONFIG=0`** to use your own `central-config.yaml` (same as running without the egg). Env vars are the base layer; YAML overrides when set.

**Database** must be reachable: set `OPENRUNE_JDBC_URL` or `OPENRUNE_DB_HOST` + `OPENRUNE_DB_NAME`. Password is optional when host is `127.0.0.1` (local trust/peer). Remote hosts still need user + password.

| Variable | Example |
|----------|---------|
| `OPENRUNE_HTTP_PORT` | `9090` |
| `OPENRUNE_DB_HOST` | `db.example.com` |
| `OPENRUNE_DB_PORT` | `5432` |
| `OPENRUNE_DB_NAME` | `openrune_central` |
| `OPENRUNE_DB_USER` / `OPENRUNE_DB_PASSWORD` | … |
| `OPENRUNE_DB_POOL_SIZE` | `10` |
| `OPENRUNE_JDBC_URL` | *(optional full override)* |
| `OPENRUNE_WORLD_LINK_PORT` | `9091` |
| `OPENRUNE_JAV_CONFIG_PROPS` | Multiline jav lines (see below) |

Set `OPENRUNE_WRITE_CONFIG=0` if you upload your own `central-config.yaml` and do not want it overwritten.

### Jav config overrides

Pterodactyl Startup is usually **one line** for this field. Use **semicolons** between entries (the config module splits them on start):

```
title=Fluxious;codebase=http://w1.example.com/;cachedir=fluxious;param=25=238;param=17=http://central.example.com:9090/worldslist.ws;msg=ok=OK
```

You can also paste multiple `key=value` pairs on one line separated by spaces (`title=Foo codebase=http://…`). If your panel shows a multiline box (`textarea`), one entry per line still works.

These map to `openrune.javConfig.configProps` and override the downloaded remote jav config.

## Script update & Central update

Both use the same Startup dropdown (Pterodactyl `in:` rule): **Notification**, **Disabled**, **Automatic**. Egg import uses `field_type: text` + `rules: required|string|in:Notification,Disabled,Automatic` — do not add an `options` array (that breaks import).

| Mode | Script update (`OPENRUNE_SCRIPT_UPDATE`) | Central update (`OPENRUNE_CENTRAL_UPDATE`) |
|------|----------------------------------------|-------------------------------------------|
| **Notification** | Log when `main` has new scripts; set Automatic + restart to apply | Console `y/yes` when a newer JAR exists (default) |
| **Disabled** | No GitHub script sync | No GitHub JAR checks; upload JAR yourself |
| **Automatic** | Download script changes on start (default) | Install latest release when newer |

Legacy `AUTOUPDATE_STATUS` / `AUTOUPDATE_FORCE` still work if the new variable is unset.

**First start** (no JAR yet): always downloads the latest release unless Disabled.

Repo: [OpenRune/OpenRune-Central-Server](https://github.com/OpenRune/OpenRune-Central-Server/releases). Optional: `GITHUB_TOKEN` for API rate limits. Advanced: `JAR_UPDATE_INCLUDE_PRERELEASE=1` env only.

## Modules (startup order)

`autoupdate` → `jarupdate` → `config` → `logcleaner` → `cloudflared` → Java

## Console

`!help`, `stop`, `refreshcache`, `refresh all`, `refresh worlds`, …
