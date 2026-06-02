#!/usr/bin/env bash
set -euo pipefail

cd /home/container

SERVER_JAR="${SERVER_JAR:-openrune-central-server.jar}"

# shellcheck source=start-modules.sh
source ./start-modules.sh

if [[ ! -f "${SERVER_JAR}" ]]; then
    echo "[Orchestrator] ERROR: JAR not found: ${SERVER_JAR}"
    echo "[Orchestrator] Upload ${SERVER_JAR} manually, or set Central update to Automatic or Notification required."
    exit 1
fi

exec java -Xms128M -XX:MaxRAMPercentage=95.0 ${JAVA_OPTS:-} ${JVM_EXTRA_FLAGS:-} \
    -Duser.dir=/home/container \
    -jar "${SERVER_JAR}"
