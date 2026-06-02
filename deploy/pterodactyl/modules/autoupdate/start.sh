#!/usr/bin/env bash
# Sync Pterodactyl scripts when the GitHub main branch tip changes.

OPENRUNE_SCRIPT_UPDATE="${OPENRUNE_SCRIPT_UPDATE:-}"
AUTOUPDATE_REPO="OpenRune/OpenRune-Central-Server"
AUTOUPDATE_BRANCH="main"

COMMIT_STATE_FILE="/home/container/.autoupdate_commit"
CONTAINER_ROOT="/home/container"

BLUE='\033[0;34m'
BOLD_BLUE='\033[1;34m'
WHITE='\033[0;37m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

enabled() { [[ "${1}" =~ ^(true|1|yes|on)$ ]]; }

resolve_script_update_mode() {
    local raw="${OPENRUNE_SCRIPT_UPDATE}"
    if [[ -z "${raw}" ]]; then
        if ! enabled "${AUTOUPDATE_STATUS:-1}"; then
            echo "disabled"
            return
        fi
        if enabled "${AUTOUPDATE_FORCE:-1}"; then
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
            echo -e "${YELLOW}[AutoUpdate] Unknown OPENRUNE_SCRIPT_UPDATE='${OPENRUNE_SCRIPT_UPDATE}'; using notification.${NC}"
            echo "notification"
            ;;
    esac
}

header() {
    echo -e "${BLUE}───────────────────────────────────────────────${NC}"
    echo -e "${BOLD_BLUE}[AutoUpdate] $1${NC}"
}

short_sha() {
    local sha="$1"
    echo "${sha:0:7}"
}

get_local_commit() {
    if [[ -f "${COMMIT_STATE_FILE}" ]] && [[ -s "${COMMIT_STATE_FILE}" ]]; then
        tr -d '\n\r' <"${COMMIT_STATE_FILE}" | head -1
        return
    fi
    echo ""
}

fetch_remote_commit_sha() {
    local api_url="https://api.github.com/repos/${AUTOUPDATE_REPO}/commits/${AUTOUPDATE_BRANCH}"
    local -a curl_args=(
        -fsSL
        --max-time 30
        -H "Accept: application/vnd.github+json"
        -H "User-Agent: OpenRune-Central-AutoUpdate"
    )
    if [[ -n "${GITHUB_TOKEN:-}" ]]; then
        curl_args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
    fi

    local json
    json=$(curl "${curl_args[@]}" "${api_url}" 2>/dev/null) || return 1
    echo "${json}" | grep -oE '[0-9a-f]{40}' | head -1
}

fetch_text() {
    local url="$1"
    curl -fsSL --max-time 30 "${url}" 2>/dev/null
}

SCRIPT_UPDATE_MODE=$(resolve_script_update_mode)

if [[ "${SCRIPT_UPDATE_MODE}" == "disabled" ]]; then
    exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
    echo -e "${YELLOW}[AutoUpdate] curl not available; skipping script sync.${NC}"
    exit 0
fi

header "Checking for script updates"

LOCAL=$(get_local_commit)
REMOTE=$(fetch_remote_commit_sha)

echo -e "${CYAN}[AutoUpdate] Mode: ${SCRIPT_UPDATE_MODE}${NC}"
echo -e "${CYAN}[AutoUpdate] Repository: ${AUTOUPDATE_REPO} @ ${AUTOUPDATE_BRANCH}${NC}"
echo -e "${CYAN}[AutoUpdate] Installed commit: ${LOCAL:-<none>}$( [[ -n "${LOCAL}" ]] && echo " ($(short_sha "${LOCAL}"))" )${NC}"
echo -e "${CYAN}[AutoUpdate] Remote commit:  ${REMOTE:-<unavailable>}$( [[ -n "${REMOTE}" ]] && echo " ($(short_sha "${REMOTE}"))" )${NC}"

if [[ -z "${REMOTE}" ]]; then
    echo -e "${YELLOW}[AutoUpdate] Could not read latest commit from GitHub API; using local scripts.${NC}"
    exit 0
fi

if [[ "${LOCAL}" == "${REMOTE}" ]]; then
    echo -e "${GREEN}[AutoUpdate] Scripts are up to date (commit $(short_sha "${REMOTE}")).${NC}"
    exit 0
fi

if [[ -n "${LOCAL}" ]]; then
    echo -e "${YELLOW}[AutoUpdate] New commit on ${AUTOUPDATE_BRANCH}: $(short_sha "${LOCAL}") → $(short_sha "${REMOTE}")${NC}"
else
    echo -e "${YELLOW}[AutoUpdate] First sync to commit $(short_sha "${REMOTE}")${NC}"
fi

if [[ "${SCRIPT_UPDATE_MODE}" == "notification" ]]; then
    echo -e "${CYAN}[AutoUpdate] Script update available. Set Script update to Automatic in Startup and restart to apply.${NC}"
    exit 0
fi

RAW_BASE="https://raw.githubusercontent.com/${AUTOUPDATE_REPO}/${REMOTE}/deploy/pterodactyl"

MANIFEST=$(fetch_text "${RAW_BASE}/autoupdate-files.txt")
if [[ -z "${MANIFEST}" ]]; then
    echo -e "${RED}[AutoUpdate] Could not fetch autoupdate-files.txt at ${REMOTE}${NC}"
    exit 0
fi

BACKUP_DIR="${CONTAINER_ROOT}/.autoupdate_backup_$(date +%Y%m%d_%H%M%S)"
mkdir -p "${BACKUP_DIR}"

echo -e "${WHITE}[AutoUpdate] Automatic — downloading scripts from commit $(short_sha "${REMOTE}")...${NC}"

updated=0
while IFS= read -r rel || [[ -n "${rel}" ]]; do
    rel="${rel//$'\r'/}"
    rel=$(echo "${rel}" | xargs)
    [[ -z "${rel}" ]] && continue
    [[ "${rel}" == \#* ]] && continue

    dest="${CONTAINER_ROOT}/${rel}"
    mkdir -p "$(dirname "${dest}")"

    if [[ -f "${dest}" ]]; then
        cp -a "${dest}" "${BACKUP_DIR}/" 2>/dev/null || cp -a "${dest}" "${BACKUP_DIR}/$(basename "${dest}")"
    fi

    if curl -fsSL --max-time 60 "${RAW_BASE}/${rel}" -o "${dest}.new"; then
        mv -f "${dest}.new" "${dest}"
        if [[ "${rel}" == *.sh ]] || [[ "${rel}" == start.sh ]] || [[ "${rel}" == start-modules.sh ]]; then
            chmod +x "${dest}"
        fi
        echo -e "${GREEN}[AutoUpdate]   ✓ ${rel}${NC}"
        updated=$((updated + 1))
    else
        echo -e "${RED}[AutoUpdate]   ✗ ${rel} (download failed)${NC}"
        rm -f "${dest}.new"
    fi
done <<<"${MANIFEST}"

printf '%s\n' "${REMOTE}" >"${COMMIT_STATE_FILE}"
echo -e "${GREEN}[AutoUpdate] Updated ${updated} file(s); tracking commit ${REMOTE}${NC}"
echo -e "${CYAN}[AutoUpdate] Backup of replaced files: ${BACKUP_DIR}${NC}"
echo -e "${CYAN}[AutoUpdate] Re-import the egg in the panel when new Startup variables are added.${NC}"
