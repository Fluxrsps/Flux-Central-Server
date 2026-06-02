#!/bin/sh
# Full install for /mnt/server (POSIX sh — Alpine install container or manual reinstall).

INSTALL_DIR="/mnt/server"
RAW="https://raw.githubusercontent.com/OpenRune/OpenRune-Central-Server/main/deploy/pterodactyl"

cd "${INSTALL_DIR}" 2>/dev/null || {
  echo "[Install] ERROR: cannot cd ${INSTALL_DIR}"
  exit 1
}

if ! command -v curl >/dev/null 2>&1; then
  echo "[Install] installing curl..."
  apk add --no-cache curl || exit 1
fi

get() {
  path="$1"
  echo "[Install] GET ${path}"
  mkdir -p "$(dirname "${path}")"
  if curl -fsSL --connect-timeout 15 --max-time 300 "${RAW}/${path}" -o "${path}.part"; then
    mv -f "${path}.part" "${path}"
    echo "[Install] ok ${path}"
    return 0
  fi
  rm -f "${path}.part"
  echo "[Install] FAILED ${path}"
  return 1
}

get start.sh || exit 1
get start-modules.sh || exit 1
chmod +x start.sh start-modules.sh

get modules/config/start.sh || exit 1
get modules/autoupdate/start.sh || exit 1
get modules/logcleaner/start.sh || exit 1
get modules/jarupdate/start.sh || exit 1
get modules/cloudflared/start.sh || exit 1
chmod +x modules/*/start.sh

get autoupdate-files.txt || exit 1

ARCH=$(uname -m)
case "${ARCH}" in
  x86_64|amd64) CF=amd64 ;;
  aarch64|arm64) CF=arm64 ;;
  *)
    echo "[Install] WARNING: unsupported arch ${ARCH}, skipping cloudflared binary"
    CF=
    ;;
esac

if [ -n "${CF}" ]; then
  echo "[Install] GET cloudflared (${CF})"
  if curl -fsSL --connect-timeout 15 --max-time 300 \
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF}" \
    -o cloudflared.part; then
    mv -f cloudflared.part cloudflared
    chmod +x cloudflared
    echo "[Install] ok cloudflared"
  else
    echo "[Install] WARNING: cloudflared download failed"
  fi
fi

echo "[Install] done"
