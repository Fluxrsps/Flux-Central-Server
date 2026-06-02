#!/usr/bin/env bash
# Cloudflare Tunnel module (token-based). Pattern from pterodactyl-nginx-egg cloudflared module.

CLOUDFLARED_STATUS="${CLOUDFLARED_STATUS:-0}"
CLOUDFLARED_TOKEN="${CLOUDFLARED_TOKEN:-}"
CLOUDFLARED_LOG_FILE="${CLOUDFLARED_LOG_FILE:-/home/container/logs/cloudflared.log}"
CLOUDFLARED_PID_FILE="${CLOUDFLARED_PID_FILE:-/home/container/tmp/cloudflared.pid}"
CLOUDFLARED_MAX_ATTEMPTS="${CLOUDFLARED_MAX_ATTEMPTS:-130}"
CLOUDFLARED_STATUS_TIMES="${CLOUDFLARED_STATUS_TIMES:-5 10 15 30 60 90 120}"

GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
NC='\033[0m'

enabled() { [[ "${1}" =~ ^(true|1|yes|on)$ ]]; }

if ! enabled "${CLOUDFLARED_STATUS}"; then
    exit 0
fi

echo -e "${YELLOW}[Tunnel] Starting Cloudflared Tunnel${NC}"

if [[ -z "${CLOUDFLARED_TOKEN}" ]]; then
    echo -e "${RED}[Tunnel] CLOUDFLARED_TOKEN is not set; skipping Cloudflared startup.${NC}"
    exit 0
fi

cf_bin="cloudflared"
if [[ -x /home/container/cloudflared ]]; then
    cf_bin="/home/container/cloudflared"
elif ! command -v cloudflared >/dev/null 2>&1; then
    echo -e "${RED}[Tunnel] cloudflared binary not found. Reinstall the server.${NC}"
    exit 1
fi

mkdir -p "$(dirname "${CLOUDFLARED_LOG_FILE}")" "$(dirname "${CLOUDFLARED_PID_FILE}")"
: >"${CLOUDFLARED_LOG_FILE}"

"${cf_bin}" tunnel --no-autoupdate run --token "${CLOUDFLARED_TOKEN}" \
    >"${CLOUDFLARED_LOG_FILE}" 2>&1 &

pid=$!
echo "${pid}" >"${CLOUDFLARED_PID_FILE}"

read -ra TIMES <<< "${CLOUDFLARED_STATUS_TIMES}"
echo -e "${YELLOW}[Tunnel] Waiting for Cloudflared to establish connection...${NC}"

for ((i = 1; i <= CLOUDFLARED_MAX_ATTEMPTS; i++)); do
    sleep 1
    for t in "${TIMES[@]}"; do
        if [[ $i -eq $t ]]; then
            echo -e "${YELLOW}[Tunnel] Still waiting... (${i}s)${NC}"
        fi
    done

    if ! kill -0 "${pid}" 2>/dev/null; then
        echo -e "${RED}[Tunnel] Cloudflared process died; check logs: ${CLOUDFLARED_LOG_FILE}${NC}"
        tail -n 10 "${CLOUDFLARED_LOG_FILE}" 2>/dev/null || true
        exit 1
    fi

    if grep -qE 'Registered tunnel connection|Updated to new configuration' "${CLOUDFLARED_LOG_FILE}" 2>/dev/null; then
        echo -e "${GREEN}[Tunnel] Connected after ${i}s${NC}"
        echo -e "${GREEN}[Tunnel] Cloudflared is running successfully.${NC}"
        exit 0
    fi
done

echo -e "${RED}[Tunnel] No successful connection within ${CLOUDFLARED_MAX_ATTEMPTS}s; check ${CLOUDFLARED_LOG_FILE}${NC}"
tail -n 10 "${CLOUDFLARED_LOG_FILE}" 2>/dev/null || true
exit 1
