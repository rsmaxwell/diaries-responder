#!/usr/bin/env bash
set -euo pipefail

APP_JAR="${APP_JAR:-/opt/diaries/lib/diaries-responder.jar}"
CONFIG_FILE="${CONFIG_FILE:-/config/diaries-responder.json}"

exec java ${JAVA_OPTS:-} -jar "${APP_JAR}" --config "${CONFIG_FILE}"

