#!/usr/bin/env bash
# Write central-config.yaml from Pterodactyl panel variables (env).
# Set OPENRUNE_WRITE_CONFIG=0 to use a hand-managed central-config.yaml instead.

OPENRUNE_WRITE_CONFIG="${OPENRUNE_WRITE_CONFIG:-1}"

enabled() { [[ "${1}" =~ ^(true|1|yes|on)$ ]]; }

if ! enabled "${OPENRUNE_WRITE_CONFIG}"; then
    exit 0
fi

is_local_db_host() {
    case "${OPENRUNE_DB_HOST:-}" in
        127.0.0.1|127.0.1|localhost|::1) return 0 ;;
    esac
    return 1
}

require_credentials() {
    case "${OPENRUNE_DB_REQUIRE_CREDENTIALS:-}" in
        false|0|no|off) return 1 ;;
        true|1|yes|on) return 0 ;;
    esac
    if [[ -n "${OPENRUNE_JDBC_URL:-}" ]]; then
        return 1
    fi
    if is_local_db_host; then
        return 1
    fi
    return 0
}

# Panel startup vars are usually a single line — expand to jav_config.ws lines for YAML.
expand_jav_props_to_lines() {
    local raw="$1"
    [[ -z "${raw}" ]] && return 0
    raw="${raw//$'\r'/}"
    raw="${raw//\\n/$'\n'}"

    if [[ "${raw}" == *$'\n'* ]]; then
        printf '%s\n' "${raw}"
        return 0
    fi

    if [[ "${raw}" == *";"* ]]; then
        local IFS=';'
        local part
        for part in ${raw}; do
            part="${part#"${part%%[![:space:]]*}"}"
            part="${part%"${part##*[![:space:]]}"}"
            [[ -n "${part}" ]] && printf '%s\n' "${part}"
        done
        return 0
    fi

    # e.g. title=Foo codebase=http://example/ cachedir=bar
    printf '%s' "${raw}" | sed -E 's/[[:space:]]+([A-Za-z_][A-Za-z0-9_.]*=)/\
\1/g'
}

require_panel_db() {
    if [[ -n "${OPENRUNE_JDBC_URL:-}" ]]; then
        :
    elif [[ -n "${OPENRUNE_DB_HOST:-}" && -n "${OPENRUNE_DB_NAME:-}" ]]; then
        :
    else
        echo "[Config] ERROR: set OPENRUNE_JDBC_URL or both OPENRUNE_DB_HOST and OPENRUNE_DB_NAME in the panel"
        exit 1
    fi
    if require_credentials; then
        if [[ -z "${OPENRUNE_DB_USER:-}" ]]; then
            echo "[Config] ERROR: OPENRUNE_DB_USER is required (or set OPENRUNE_DB_REQUIRE_CREDENTIALS=false with OPENRUNE_JDBC_URL)"
            exit 1
        fi
        if [[ -z "${OPENRUNE_DB_PASSWORD:-}" ]]; then
            echo "[Config] ERROR: OPENRUNE_DB_PASSWORD is required (or set OPENRUNE_DB_REQUIRE_CREDENTIALS=false with OPENRUNE_JDBC_URL)"
            exit 1
        fi
    fi
}

echo "[Config] Writing central-config.yaml from panel variables"
require_panel_db

CONFIG_FILE="/home/container/central-config.yaml"
TMP="${CONFIG_FILE}.new"

yaml_quote() {
    printf '%s' "$1" | sed "s/'/''/g"
}

{
    echo "# Generated on container start from Pterodactyl variables. Set OPENRUNE_WRITE_CONFIG=0 to manage this file manually."
    echo "openrune:"

    if [[ -n "${OPENRUNE_HTTP_PORT:-}" || -n "${OPENRUNE_HTTP_TRUST_PROXY:-}" ]]; then
        echo "  http:"
        if [[ -n "${OPENRUNE_HTTP_PORT:-}" ]]; then
            echo "    port: ${OPENRUNE_HTTP_PORT}"
        fi
        if [[ -n "${OPENRUNE_HTTP_TRUST_PROXY:-}" ]]; then
            echo "    trustProxy: ${OPENRUNE_HTTP_TRUST_PROXY}"
        fi
    fi

    db_port="${OPENRUNE_DB_PORT:-5432}"
    echo "  db:"
    if [[ -z "${OPENRUNE_JDBC_URL:-}" ]]; then
        echo "    host: '$(yaml_quote "${OPENRUNE_DB_HOST}")'"
        echo "    port: ${db_port}"
        echo "    name: '$(yaml_quote "${OPENRUNE_DB_NAME}")'"
    fi
    if [[ -n "${OPENRUNE_DB_USER:-}" ]]; then
        echo "    user: '$(yaml_quote "${OPENRUNE_DB_USER}")'"
    fi
    if [[ -n "${OPENRUNE_DB_PASSWORD:-}" ]]; then
        echo "    password: '$(yaml_quote "${OPENRUNE_DB_PASSWORD}")'"
    fi
    if [[ -n "${OPENRUNE_DB_REQUIRE_CREDENTIALS:-}" ]]; then
        echo "    requireCredentials: ${OPENRUNE_DB_REQUIRE_CREDENTIALS}"
    elif is_local_db_host && [[ -z "${OPENRUNE_JDBC_URL:-}" ]]; then
        echo "    requireCredentials: false"
    fi
    echo "    poolSize: ${OPENRUNE_DB_POOL_SIZE:-10}"

    if [[ -n "${OPENRUNE_JDBC_URL:-}" ]]; then
        echo "  jdbc:"
        echo "    url: '$(yaml_quote "${OPENRUNE_JDBC_URL}")'"
    else
        echo "  jdbc:"
        echo "    url: 'jdbc:postgresql://${OPENRUNE_DB_HOST}:${db_port}/${OPENRUNE_DB_NAME}'"
    fi

    if [[ -n "${OPENRUNE_SESSION_TTL_MS:-}" ]]; then
        echo "  sessionsTtlMs: ${OPENRUNE_SESSION_TTL_MS}"
    fi
    if [[ -n "${OPENRUNE_WORLD_LINK_PORT:-}" ]]; then
        echo "  worldsLinkPort: ${OPENRUNE_WORLD_LINK_PORT}"
    fi
    if [[ -n "${OPENRUNE_WORLD_LINK_SO_BACKLOG:-}" ]]; then
        echo "  worldsLinkSoBacklog: ${OPENRUNE_WORLD_LINK_SO_BACKLOG}"
    fi

    if [[ -n "${OPENRUNE_JAV_CONFIG_REVISION:-}" || -n "${OPENRUNE_JAV_CONFIG_PROPS:-}" ]]; then
        echo "  javConfig:"
        if [[ -n "${OPENRUNE_JAV_CONFIG_REVISION:-}" ]]; then
            echo "    revision: ${OPENRUNE_JAV_CONFIG_REVISION}"
        fi
        if [[ -n "${OPENRUNE_JAV_CONFIG_PROPS:-}" ]]; then
            echo "    configProps: |"
            while IFS= read -r jav_line || [[ -n "${jav_line}" ]]; do
                [[ -z "${jav_line//[[:space:]]/}" ]] && continue
                echo "      ${jav_line}"
            done < <(expand_jav_props_to_lines "${OPENRUNE_JAV_CONFIG_PROPS}")
        fi
    fi

    if [[ -n "${CLOUDFLARED_STATUS:-}" || -n "${CLOUDFLARED_TOKEN:-}" ]]; then
        echo "  cloudflared:"
        if [[ -n "${CLOUDFLARED_STATUS:-}" ]]; then
            echo "    status: '${CLOUDFLARED_STATUS}'"
        fi
        if [[ -n "${CLOUDFLARED_TOKEN:-}" ]]; then
            echo "    token: '$(yaml_quote "${CLOUDFLARED_TOKEN}")'"
        fi
    fi
} >"${TMP}"

mv -f "${TMP}" "${CONFIG_FILE}"
echo "[Config] Wrote ${CONFIG_FILE}"
