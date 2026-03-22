#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/opt/diaries"
SUBPROJECT_DIR="${PROJECT_DIR}/diaries-responder"
COMMON_SUBPROJECT_DIR="${PROJECT_DIR}/diaries-common"

CONFIG_FILE="${CONFIG_FILE:-/config/responder.json}"

CLASSPATH="${SUBPROJECT_DIR}/bin/main:${SUBPROJECT_DIR}/src/main/resources/META-INF:${COMMON_SUBPROJECT_DIR}/bin/main:${SUBPROJECT_DIR}/runtime/*"

exec java ${JAVA_OPTS:-} \
  -classpath "${CLASSPATH}" \
  com.rsmaxwell.diaries.responder.Responder \
  --config "${CONFIG_FILE}"
  