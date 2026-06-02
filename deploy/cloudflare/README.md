# HTTPS for Central HTTP (Cloudflare Tunnel)

OpenRune Central serves **plain HTTP** locally (default port **8080**). Public **HTTPS** is provided by **Cloudflare** at the edge; `cloudflared` forwards traffic to Central.

## central-config.yaml (no panel)

In `central-config.yaml` (or env `CLOUDFLARED_STATUS` / `CLOUDFLARED_TOKEN`):

```yaml
openrune:
  cloudflared:
    status: "1"
    token: eyJhIjoi...
  http:
    port: 8080
```

Run `cloudflared tunnel run --token …` beside Central. Trust-proxy enables automatically when status + token are set.

## Pterodactyl (optional)

Use the [OpenRune Central egg](../pterodactyl/egg-openrune-central.json):

1. Import the egg, create a server, upload `openrune-central-server.jar` (config is generated unless `OPENRUNE_WRITE_CONFIG=0`).
2. **Reinstall** once (installs `cloudflared` + `start.sh`).
3. In Cloudflare: **Zero Trust → Networks → Tunnels** → create tunnel → copy the **run token**.
4. In the panel:
   - **Enable Cloudflare Tunnel** = `1`
   - **Cloudflared Tunnel Token** = paste token
5. In the tunnel’s **Public Hostname**, point to `http://localhost:8080` (or your `openrune.http.port`).
6. Start the server.

`start.sh` runs modules (**autoupdate** → logcleaner → cloudflared) then the JAR. Script auto-sync from GitHub is described in [deploy/pterodactyl/README.md](../pterodactyl/README.md). Central auto-enables `X-Forwarded-*` when `CLOUDFLARED_STATUS=1` and a token is set.

## Manual / VPS (no panel)

Environment variables (base layer; optional `central-config.yaml` overrides):

| Variable | Property | Purpose |
|----------|----------|---------|
| `CLOUDFLARED_STATUS` | `openrune.cloudflared.status` | `1` / `true` to enable |
| `CLOUDFLARED_TOKEN` | `openrune.cloudflared.token` | Tunnel run token |

```bash
export CLOUDFLARED_STATUS=1
export CLOUDFLARED_TOKEN='eyJhIjoi...'
cloudflared tunnel --no-autoupdate run --token "$CLOUDFLARED_TOKEN" &
java -jar openrune-central-server.jar
```

Override proxy headers: `openrune.http.trustProxy=true|false`.

## Jav config / clients

```yaml
openrune:
  javConfig:
    configProps:
      codebase: https://central.example.com/
```

## World-link TCP

`openrune.worldsLinkPort` (default **9091**) is **not** tunneled by this HTTP setup — use private networking or a separate TCP tunnel.

## Alternatives

Reverse proxy (Caddy / nginx) with Let’s Encrypt in front of `http://127.0.0.1:8080` and `openrune.http.trustProxy=true`.
