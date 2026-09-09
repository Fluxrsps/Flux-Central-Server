#!/usr/bin/env bash
# Migrate an old-schema central-config.yaml, then write it from Pterodactyl panel
# variables (env).
# Set OPENRUNE_WRITE_CONFIG=0 to use a hand-managed central-config.yaml instead
# (the migration still runs). Set OPENRUNE_MIGRATE_CONFIG=0 to skip the migration.

OPENRUNE_WRITE_CONFIG="${OPENRUNE_WRITE_CONFIG:-1}"
OPENRUNE_MIGRATE_CONFIG="${OPENRUNE_MIGRATE_CONFIG:-1}"

CONFIG_FILE="${OPENRUNE_CONFIG:-/home/container/central-config.yaml}"
TMP="${CONFIG_FILE}.new"

enabled() { [[ "${1}" =~ ^(true|1|yes|on)$ ]]; }

# Old flat schema -> the nested schema CentralConfig.kt decodes. See README.md for the mapping.
needs_migration() {
    local file="$1"
    grep -qE '^  (db|sessionsTtlMs|worldsLink[A-Za-z]*|onlineSampleIntervalSeconds|badWords[A-Z][A-Za-z]*|cloudflared):' "${file}" && return 0
    grep -qE '^[[:space:]]+configProps:[[:space:]]*[|>]' "${file}" && return 0
    grep -qE '^[[:space:]]+param\.[0-9]+:' "${file}" && return 0
    grep -qE '^[[:space:]]+requireCredentials:' "${file}" && return 0
    return 1
}

migrate_config_file() {
    local file="$1"
    local migrated="${file}.migrated"

    awk '
        function indent_of(s,   n) { match(s, /^ */); return RLENGTH }
        function yq(s) { gsub(/'"'"'/, "'"'"''"'"'", s); return "'"'"'" s "'"'"'" }
        function stash(key, line,   v) {
            v = line
            sub(/^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*:[[:space:]]*/, "", v)
            flat[key] = v
        }

        skip_block {
            if ($0 ~ /^[[:space:]]*$/) next
            if (indent_of($0) > skip_indent) next
            skip_block = 0
        }

        in_props {
            if ($0 ~ /^[[:space:]]*$/) next
            if (indent_of($0) > props_indent) {
                line = $0
                sub(/^[[:space:]]+/, "", line)
                sub(/[[:space:]]+$/, "", line)
                if (line == "") next
                eq = index(line, "=")
                if (eq < 2) {
                    printf("[Config] WARN: dropping jav_config prop without \"=\": %s\n", line) > "/dev/stderr"
                    next
                }
                k = substr(line, 1, eq - 1)
                v = substr(line, eq + 1)
                printf("%s%s: %s\n", props_pad, k, yq(v))
                next
            }
            in_props = 0
        }

        /^  session:[[:space:]]*$/    { have["session"] = 1 }
        /^  worldLink:[[:space:]]*$/  { have["worldLink"] = 1 }
        /^  analytics:[[:space:]]*$/  { have["analytics"] = 1 }
        /^  badWords:[[:space:]]*$/   { have["badWords"] = 1 }

        /^  db:[[:space:]]*$/                 { print "  database:"; next }
        /^[[:space:]]+requireCredentials:/    { next }
        /^  cloudflared:[[:space:]]*$/        { skip_block = 1; skip_indent = 2; next }

        /^  sessionsTtlMs:/                { stash("ttlMs", $0); next }
        /^  worldsLinkPort:/               { stash("port", $0); next }
        /^  worldsLinkSoBacklog:/          { stash("soBacklog", $0); next }
        /^  worldsLinkReadTimeoutSeconds:/ { stash("readTimeoutSeconds", $0); next }
        /^  onlineSampleIntervalSeconds:/  { stash("onlineSampleIntervalSeconds", $0); next }
        /^  badWordsRemoteUrl:/            { stash("remoteUrl", $0); next }
        /^  badWordsRefreshMinutes:/       { stash("refreshMinutes", $0); next }

        /^[[:space:]]+configProps:[[:space:]]*[|>][-+]?[[:space:]]*$/ {
            props_indent = indent_of($0)
            props_pad = sprintf("%*s", props_indent + 2, "")
            in_props = 1
            printf("%*sconfigProps:\n", props_indent, "")
            next
        }

        /^[[:space:]]+param\.[0-9]+:[[:space:]]*/ {
            pad = sprintf("%*s", indent_of($0), "")
            rest = $0
            sub(/^[[:space:]]+param\./, "", rest)
            colon = index(rest, ":")
            num = substr(rest, 1, colon - 1)
            val = substr(rest, colon + 1)
            sub(/^[[:space:]]+/, "", val)
            sub(/^'"'"'/, "", val); sub(/'"'"'$/, "", val)
            printf("%sparam=%s: %s\n", pad, num, yq(val))
            next
        }

        { print }

        END {
            if ("ttlMs" in flat && !("session" in have)) {
                printf("  session:\n    ttlMs: %s\n", flat["ttlMs"])
            }
            if (!("worldLink" in have) && (("port" in flat) || ("soBacklog" in flat) || ("readTimeoutSeconds" in flat))) {
                printf("  worldLink:\n")
                if ("port" in flat)               printf("    port: %s\n", flat["port"])
                if ("soBacklog" in flat)          printf("    soBacklog: %s\n", flat["soBacklog"])
                if ("readTimeoutSeconds" in flat) printf("    readTimeoutSeconds: %s\n", flat["readTimeoutSeconds"])
            }
            if ("onlineSampleIntervalSeconds" in flat && !("analytics" in have)) {
                printf("  analytics:\n    onlineSampleIntervalSeconds: %s\n", flat["onlineSampleIntervalSeconds"])
            }
            if (!("badWords" in have) && (("remoteUrl" in flat) || ("refreshMinutes" in flat))) {
                printf("  badWords:\n")
                if ("remoteUrl" in flat)      printf("    remoteUrl: %s\n", flat["remoteUrl"])
                if ("refreshMinutes" in flat) printf("    refreshMinutes: %s\n", flat["refreshMinutes"])
            }
        }
    ' "${file}" >"${migrated}" || { rm -f "${migrated}"; return 1; }

    local backup="${file}.old-schema.$(date +%Y%m%d_%H%M%S)"
    cp -a "${file}" "${backup}" || { rm -f "${migrated}"; return 1; }
    mv -f "${migrated}" "${file}"
    echo "[Config] Migrated ${file} from the old flat schema (backup: ${backup})"
}

if enabled "${OPENRUNE_MIGRATE_CONFIG}" && [[ -f "${CONFIG_FILE}" ]] && needs_migration "${CONFIG_FILE}"; then
    migrate_config_file "${CONFIG_FILE}" || echo "[Config] WARN: migration of ${CONFIG_FILE} failed; leaving it unchanged"
fi

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

jav_keys=()
jav_vals=()

jav_put() {
    local key="$1" value="$2" i
    for i in "${!jav_keys[@]}"; do
        if [[ "${jav_keys[i]}" == "${key}" ]]; then
            echo "[Config] WARN: duplicate jav_config prop '${key}'; keeping the last value" >&2
            jav_vals[i]="${value}"
            return
        fi
    done
    jav_keys+=("${key}")
    jav_vals+=("${value}")
}

parse_jav_props() {
    local line key value
    while IFS= read -r line || [[ -n "${line}" ]]; do
        [[ -z "${line//[[:space:]]/}" ]] && continue
        if [[ "${line}" != *"="* ]]; then
            echo "[Config] WARN: ignoring jav_config prop without '=': ${line}" >&2
            continue
        fi
        key="${line%%=*}"
        value="${line#*=}"
        key="${key#"${key%%[![:space:]]*}"}"
        key="${key%"${key##*[![:space:]]}"}"
        # param/msg lines are indexed by a second segment (param=17=<url>, msg=ok=OK):
        # keep both segments in the key so entries do not collide on 'param'.
        if [[ "${key}" == "param" || "${key}" == "msg" ]] && [[ "${value}" == *"="* ]]; then
            key="${key}=${value%%=*}"
            value="${value#*=}"
        fi
        if [[ -z "${key}" ]]; then
            echo "[Config] WARN: ignoring jav_config prop with empty key: ${line}" >&2
            continue
        fi
        jav_put "${key}" "${value}"
    done < <(expand_jav_props_to_lines "$1")
}

echo "[Config] Writing central-config.yaml from panel variables"
require_panel_db

if [[ -n "${OPENRUNE_JAV_CONFIG_PROPS:-}" ]]; then
    parse_jav_props "${OPENRUNE_JAV_CONFIG_PROPS}"
fi

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
    echo "  database:"
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
    echo "    poolSize: ${OPENRUNE_DB_POOL_SIZE:-10}"

    if [[ -n "${OPENRUNE_JDBC_URL:-}" ]]; then
        echo "  jdbc:"
        echo "    url: '$(yaml_quote "${OPENRUNE_JDBC_URL}")'"
    else
        echo "  jdbc:"
        echo "    url: 'jdbc:postgresql://${OPENRUNE_DB_HOST}:${db_port}/${OPENRUNE_DB_NAME}'"
    fi

    if [[ -n "${OPENRUNE_SESSION_TTL_MS:-}" ]]; then
        echo "  session:"
        echo "    ttlMs: ${OPENRUNE_SESSION_TTL_MS}"
    fi

    if [[ -n "${OPENRUNE_WORLD_LINK_PORT:-}" || -n "${OPENRUNE_WORLD_LINK_SO_BACKLOG:-}" ]]; then
        echo "  worldLink:"
        if [[ -n "${OPENRUNE_WORLD_LINK_PORT:-}" ]]; then
            echo "    port: ${OPENRUNE_WORLD_LINK_PORT}"
        fi
        if [[ -n "${OPENRUNE_WORLD_LINK_SO_BACKLOG:-}" ]]; then
            echo "    soBacklog: ${OPENRUNE_WORLD_LINK_SO_BACKLOG}"
        fi
    fi

    if [[ -n "${OPENRUNE_JAV_CONFIG_REVISION:-}" || ${#jav_keys[@]} -gt 0 ]]; then
        echo "  javConfig:"
        if [[ -n "${OPENRUNE_JAV_CONFIG_REVISION:-}" ]]; then
            echo "    revision: ${OPENRUNE_JAV_CONFIG_REVISION}"
        fi
        if [[ ${#jav_keys[@]} -gt 0 ]]; then
            echo "    configProps:"
            for i in "${!jav_keys[@]}"; do
                echo "      ${jav_keys[i]}: '$(yaml_quote "${jav_vals[i]}")'"
            done
        fi
    fi

} >"${TMP}"

mv -f "${TMP}" "${CONFIG_FILE}"
echo "[Config] Wrote ${CONFIG_FILE}"
