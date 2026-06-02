#!/usr/bin/env bash
# Download OpenRune Central JAR from GitHub Releases (latest).

OPENRUNE_CENTRAL_UPDATE="${OPENRUNE_CENTRAL_UPDATE:-}"
JAR_UPDATE_REPO="OpenRune/OpenRune-Central-Server"
JAR_UPDATE_INCLUDE_PRERELEASE="${JAR_UPDATE_INCLUDE_PRERELEASE:-0}"
JAR_UPDATE_PROMPT_SEC="${JAR_UPDATE_PROMPT_SEC:-60}"

SERVER_JAR="${SERVER_JAR:-openrune-central-server.jar}"
CONTAINER_ROOT="/home/container"
JAR_PATH="${CONTAINER_ROOT}/${SERVER_JAR}"
STATE_FILE="${CONTAINER_ROOT}/.jarupdate_release"

BLUE='\033[0;34m'
BOLD_BLUE='\033[1;34m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

enabled() { [[ "${1}" =~ ^(true|1|yes|on)$ ]]; }

resolve_update_mode() {
    local raw="${OPENRUNE_CENTRAL_UPDATE}"
    if [[ -z "${raw}" ]]; then
        if enabled "${JAR_UPDATE_DISABLE:-0}" || ! enabled "${JAR_UPDATE_STATUS:-1}"; then
            echo "disabled"
            return
        fi
        if enabled "${JAR_UPDATE_AUTO:-0}" || enabled "${JAR_UPDATE_APPROVE:-0}"; then
            echo "automatic"
            return
        fi
        echo "notification"
        return
    fi
    raw="${raw,,}"
    case "${raw}" in
        disabled|disable|off|0|false|no) echo "disabled" ;;
        automatic|auto|1|true|yes|on) echo "automatic" ;;
        notification|notify|prompt|ask|required|"notification required") echo "notification" ;;
        *)
            echo -e "${YELLOW}[JarUpdate] Unknown OPENRUNE_CENTRAL_UPDATE='${OPENRUNE_CENTRAL_UPDATE}'; using notification.${NC}"
            echo "notification"
            ;;
    esac
}

header() {
    echo -e "${BLUE}───────────────────────────────────────────────${NC}"
    echo -e "${BOLD_BLUE}[JarUpdate] $1${NC}"
}

github_curl() {
    local url="$1"
    local -a args=(
        -fsSL
        --max-time 45
        -H "Accept: application/vnd.github+json"
        -H "User-Agent: OpenRune-Central-JarUpdate"
    )
    if [[ -n "${GITHUB_TOKEN:-}" ]]; then
        args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
    fi
    curl "${args[@]}" "${url}" 2>/dev/null
}

list_release_assets() {
    local json="$1"
    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "${json}" | python3 -c "
import json, sys
for a in json.load(sys.stdin).get('assets', []):
    n = a.get('name')
    if n:
        print(n)
" 2>/dev/null && return 0
    fi
    printf '%s' "${json}" | grep -oE '"name"[[:space:]]*:[[:space:]]*"[^"]+\.jar"' | sed 's/.*"\([^"]*\)"$/\1/' | head -20
}

parse_release_asset() {
    local json="$1"
    local asset_name="$2"

    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "${json}" | python3 -c "
import json, sys
asset = sys.argv[1]
data = json.load(sys.stdin)
tag = data.get('tag_name', '')
for a in data.get('assets', []):
    if a.get('name') == asset:
        print(tag)
        print(a.get('browser_download_url', ''))
        sys.exit(0)
sys.exit(1)
" "${asset_name}" 2>/dev/null && return 0
    fi

    if command -v jq >/dev/null 2>&1; then
        local tag url
        tag=$(printf '%s' "${json}" | jq -r '.tag_name // empty')
        url=$(printf '%s' "${json}" | jq -r --arg n "${asset_name}" '.assets[] | select(.name == $n) | .browser_download_url // empty' | head -1)
        if [[ -n "${tag}" && -n "${url}" ]]; then
            printf '%s\n%s\n' "${tag}" "${url}"
            return 0
        fi
    fi

    # Fallback: match releases/download/.../filename (uploader JSON contains } so [^}]* breaks).
    local tag url
    tag=$(printf '%s' "${json}" | grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
    url=$(printf '%s' "${json}" | grep -oE "https://github.com/[^\"]+/releases/download/[^\"]+/${asset_name}" | head -1)
    if [[ -z "${url}" ]]; then
        url=$(printf '%s' "${json}" | grep -oE '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*'"${asset_name}"'"' | head -1 | sed 's/.*"\(https:[^"]*\)".*/\1/')
    fi
    if [[ -n "${tag}" && -n "${url}" ]]; then
        printf '%s\n%s\n' "${tag}" "${url}"
        return 0
    fi
    return 1
}

get_local_tag() {
    if [[ -f "${STATE_FILE}" ]] && [[ -s "${STATE_FILE}" ]]; then
        tr -d '\n\r' <"${STATE_FILE}" | head -1
        return
    fi
    echo ""
}

is_first_load() {
    [[ ! -f "${JAR_PATH}" ]] || [[ ! -s "${JAR_PATH}" ]] || [[ ! -f "${STATE_FILE}" ]] || [[ ! -s "${STATE_FILE}" ]]
}

prompt_user_yes_no() {
    local message="$1"
    if [[ ! -e /dev/tty ]]; then
        return 1
    fi
    echo -e "${YELLOW}${message}${NC}" >/dev/tty
    echo -e "${CYAN}[JarUpdate] Reply y/yes on the console within ${JAR_UPDATE_PROMPT_SEC}s.${NC}" >/dev/tty
    local answer=""
    if ! read -r -t "${JAR_UPDATE_PROMPT_SEC}" answer </dev/tty; then
        return 1
    fi
    case "${answer,,}" in
        y|yes) return 0 ;;
        *) return 1 ;;
    esac
}

download_jar() {
    local url="$1"
    local tag="$2"
    local tmp="${JAR_PATH}.download"
    local backup_dir="${CONTAINER_ROOT}/.jarupdate_backup_$(date +%Y%m%d_%H%M%S)"

    echo -e "${CYAN}[JarUpdate] Downloading ${SERVER_JAR} (${tag})...${NC}"

    if [[ -f "${JAR_PATH}" ]]; then
        mkdir -p "${backup_dir}"
        cp -a "${JAR_PATH}" "${backup_dir}/" 2>/dev/null || true
        echo -e "${CYAN}[JarUpdate] Backed up previous JAR to ${backup_dir}${NC}"
    fi

    if ! curl -fsSL --max-time 300 -o "${tmp}" "${url}"; then
        echo -e "${RED}[JarUpdate] Download failed.${NC}"
        rm -f "${tmp}"
        return 1
    fi

    if [[ ! -s "${tmp}" ]]; then
        echo -e "${RED}[JarUpdate] Downloaded file is empty.${NC}"
        rm -f "${tmp}"
        return 1
    fi

    mv -f "${tmp}" "${JAR_PATH}"
    printf '%s\n' "${tag}" >"${STATE_FILE}"
    echo -e "${GREEN}[JarUpdate] Installed ${SERVER_JAR} @ ${tag}${NC}"
    return 0
}

UPDATE_MODE=$(resolve_update_mode)

if [[ "${UPDATE_MODE}" == "disabled" ]]; then
    echo -e "${CYAN}[JarUpdate] Disabled (OPENRUNE_CENTRAL_UPDATE=disabled). Upload and manage ${SERVER_JAR} yourself.${NC}"
    if [[ ! -f "${JAR_PATH}" ]]; then
        echo -e "${RED}[JarUpdate] No JAR at ${SERVER_JAR}. Upload one or enable Central update.${NC}"
        exit 1
    fi
    exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
    echo -e "${YELLOW}[JarUpdate] curl not available; skipping JAR update.${NC}"
    exit 0
fi

header "Checking GitHub Releases"

if enabled "${JAR_UPDATE_INCLUDE_PRERELEASE}"; then
    api_url="https://api.github.com/repos/${JAR_UPDATE_REPO}/releases?per_page=5"
    releases_json=$(github_curl "${api_url}") || releases_json=""
    if command -v python3 >/dev/null 2>&1 && [[ -n "${releases_json}" ]]; then
        release_json=$(printf '%s' "${releases_json}" | python3 -c "
import json, sys
releases = json.load(sys.stdin)
if not releases:
    sys.exit(1)
print(json.dumps(releases[0]))
" 2>/dev/null) || release_json=""
    else
        release_json=""
    fi
else
    api_url="https://api.github.com/repos/${JAR_UPDATE_REPO}/releases/latest"
    release_json=$(github_curl "${api_url}") || release_json=""
fi

if [[ -z "${release_json}" ]]; then
    echo -e "${YELLOW}[JarUpdate] Could not fetch releases from ${JAR_UPDATE_REPO}; using existing JAR.${NC}"
    if [[ ! -f "${JAR_PATH}" ]]; then
        echo -e "${RED}[JarUpdate] No JAR at ${SERVER_JAR}. Upload one or fix GitHub access.${NC}"
        exit 1
    fi
    exit 0
fi

parsed=$(parse_release_asset "${release_json}" "${SERVER_JAR}") || parsed=""
remote_tag=$(echo "${parsed}" | sed -n '1p')
download_url=$(echo "${parsed}" | sed -n '2p')

if [[ -z "${remote_tag}" || -z "${download_url}" ]]; then
    echo -e "${YELLOW}[JarUpdate] Could not parse release asset '${SERVER_JAR}' (API may be rate-limited or parser failed).${NC}"
    echo -e "${CYAN}[JarUpdate] Assets on latest release:${NC}"
    while IFS= read -r asset_line; do
        [[ -n "${asset_line}" ]] && echo -e "${CYAN}  - ${asset_line}${NC}"
    done < <(list_release_assets "${release_json}")
    echo -e "${CYAN}[JarUpdate] Upload the JAR manually, set GITHUB_TOKEN, or publish a release with asset name exactly '${SERVER_JAR}'.${NC}"
    if [[ ! -f "${JAR_PATH}" ]]; then
        exit 1
    fi
    exit 0
fi

local_tag=$(get_local_tag)
echo -e "${CYAN}[JarUpdate] Mode: ${UPDATE_MODE}${NC}"
echo -e "${CYAN}[JarUpdate] Repository: ${JAR_UPDATE_REPO}${NC}"
echo -e "${CYAN}[JarUpdate] Asset: ${SERVER_JAR}${NC}"
echo -e "${CYAN}[JarUpdate] Installed: ${local_tag:-<none>}${NC}"
echo -e "${CYAN}[JarUpdate] Latest:   ${remote_tag}${NC}"

if [[ "${local_tag}" == "${remote_tag}" ]] && [[ -f "${JAR_PATH}" ]]; then
    echo -e "${GREEN}[JarUpdate] JAR is up to date (${remote_tag}).${NC}"
    exit 0
fi

if is_first_load; then
    echo -e "${YELLOW}[JarUpdate] First install — downloading latest release automatically.${NC}"
    download_jar "${download_url}" "${remote_tag}" || exit 1
    exit 0
fi

if [[ "${UPDATE_MODE}" == "automatic" ]]; then
    echo -e "${YELLOW}[JarUpdate] Automatic — updating to ${remote_tag}.${NC}"
    download_jar "${download_url}" "${remote_tag}" || exit 0
    exit 0
fi

echo -e "${YELLOW}[JarUpdate] New release available: ${local_tag} → ${remote_tag}${NC}"
if prompt_user_yes_no "[JarUpdate] Download and replace ${SERVER_JAR}? [y/N]"; then
    download_jar "${download_url}" "${remote_tag}" || true
else
    echo -e "${CYAN}[JarUpdate] Skipped update; still running ${local_tag:-previous build}.${NC}"
    echo -e "${CYAN}[JarUpdate] Set Central update to Automatic in Startup and restart to apply without a prompt.${NC}"
fi

exit 0
