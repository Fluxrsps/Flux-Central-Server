#!/usr/bin/env bash
# Runs at container start BEFORE other modules (cleans previous run artifacts).

LOGCLEANER_STATUS="${LOGCLEANER_STATUS:-1}"

enabled() { [[ "${1}" =~ ^(true|1|yes|on)$ ]]; }

if ! enabled "${LOGCLEANER_STATUS}"; then
    exit 0
fi

echo "[Logcleaner] Starting log cleanup"
mkdir -p /home/container/logs /home/container/tmp

echo "[Logcleaner] Removing stale temporary files"
for f in /home/container/tmp/*.pid /home/container/tmp/*.sock; do
    [[ -e "${f}" ]] || continue
    echo "[Logcleaner] Deleting: ${f}"
    rm -f "${f}"
done

# Truncate large logs from a previous run (keep files for the panel log viewer)
for log in /home/container/logs/*.log; do
    [[ -f "${log}" ]] || continue
    : >"${log}"
done

echo "[Logcleaner] Done"
