#!/usr/bin/env bash
# Runs startup modules (logcleaner → cloudflared) before the Java process.
set -euo pipefail

cd /home/container

BLUE='\033[0;34m'
BOLD_BLUE='\033[1;34m'
NC='\033[0m'

header() {
    echo -e "${BLUE}───────────────────────────────────────────────${NC}"
    echo -e "${BOLD_BLUE}[Orchestrator] $1${NC}"
}

enabled() { [[ "${1}" =~ ^(true|1|yes|on)$ ]]; }

jarupdate_enabled() {
    local mode="${OPENRUNE_CENTRAL_UPDATE:-}"
    if [[ -z "${mode}" ]]; then
        if enabled "${JAR_UPDATE_DISABLE:-0}" || ! enabled "${JAR_UPDATE_STATUS:-1}"; then
            return 1
        fi
        return 0
    fi
    case "${mode,,}" in
        disabled|disable|off|0|false|no) return 1 ;;
        notification|notify|prompt|ask|required|"notification required") return 0 ;;
        automatic|auto) return 0 ;;
        *) return 0 ;;
    esac
}

run_module() {
    local name="$1"
    local script="modules/${name}/start.sh"
    if [[ ! -f "${script}" ]]; then
        echo "[Orchestrator] Module '${name}' not found (${script}); skipping."
        return 0
    fi
    if [[ "${name}" == "jarupdate" ]] && ! jarupdate_enabled; then
        echo "[Orchestrator] Central update disabled (OPENRUNE_CENTRAL_UPDATE=disabled); skipping jarupdate."
        return 0
    fi
    header "Running module: ${name}"
    # Run in a subshell: modules use "exit" on success; sourcing would stop the whole server.
    bash "${script}"
}

MODULE_ORDER="${START_MODULES:-autoupdate jarupdate config logcleaner cloudflared}"
for module in ${MODULE_ORDER}; do
    run_module "${module}"
done

header "Starting OpenRune Central (Java)"
