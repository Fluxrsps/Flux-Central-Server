package dev.or2.central.util.config

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.or2.central.config.cloudflared")

/**
 * Cloudflare Tunnel via env (Pterodactyl-style), e.g. [CLOUDFLARED_STATUS] + [CLOUDFLARED_TOKEN].
 * The tunnel process is started outside Central (see `deploy/pterodactyl/start.sh`).
 */
internal object CloudflaredTunnelEnv {

    fun isConfigured(cfg: CentralMergedConfig): Boolean {
        if (!parseEnabled(cfg.raw(CentralConfigKey.CLOUDFLARED_STATUS))) return false

        if (cfg.raw(CentralConfigKey.CLOUDFLARED_TOKEN).isNullOrBlank()) {
            log.warn(
                "Cloudflare Tunnel enabled (CLOUDFLARED_STATUS) but token missing; " +
                    "HTTP trust-proxy not enabled",
            )
            return false
        }

        return true
    }
}

internal fun parseEnabled(raw: String?): Boolean =
    when (raw?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        else -> false
    }
