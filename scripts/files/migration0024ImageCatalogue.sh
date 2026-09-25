#!/usr/bin/env bash

set -euo pipefail

# ==============================================================================
# migration0024ImageCatalogue.sh
#
# Reconcile the production Files root with the persistent Image catalogue using
# the responder image and configuration installed by the Diaries playbook.
#
# Usage:
#
#     migration0024ImageCatalogue.sh dry-run [0022_CANDIDATES_CSV]
#     migration0024ImageCatalogue.sh apply   [0022_CANDIDATES_CSV]
#
# Run dry-run first. It scans the configured Files root and database without
# changing them, then writes evidence beneath:
#
#     ${HOME}/temp/dry-run
#
# Review 0024-create-plan.json, 0024-conflicts.csv and the other dry-run
# evidence before running apply. Apply automatically reads the reviewed plan
# from the dry-run directory and writes its evidence beneath:
#
#     ${HOME}/temp/apply
#
# The evidence directory for the requested mode must be empty. Archive and
# empty both directories before starting another complete reconciliation.
# The optional 0022 candidate CSV cross-references legacy embedded-image
# candidates; when supplied for dry-run, supply the same unchanged file for
# apply. DIARIES_MIGRATION0024_EVIDENCE_ROOT can override ${HOME}/temp.
#
# Dry-run may run alongside the responder. Apply refuses to run while the
# deployed responder service is running, ensuring that normal responder writes
# cannot race the reviewed migration. Keep the database service running.
# ==============================================================================


# ------------------------------------------------------------------------------
# Display valid invocations when arguments are missing or invalid.
# ------------------------------------------------------------------------------

usage() {
    cat >&2 <<EOF
Usage: $(basename -- "$0") dry-run [0022_CANDIDATES_CSV]
       $(basename -- "$0") apply [0022_CANDIDATES_CSV]
Evidence: ${DIARIES_MIGRATION0024_EVIDENCE_ROOT:-${HOME}/temp}/{dry-run,apply}
Run dry-run first, review its 0024-create-plan.json, then stop the responder
service and run apply.
EOF
    exit 2
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}


# ------------------------------------------------------------------------------
# Select the requested mode and optional 0022 candidate inventory.
# ------------------------------------------------------------------------------

[[ $# -ge 1 && $# -le 2 ]] || usage

MODE="$1"
case "${MODE}" in
    dry-run | apply)
        ;;
    *)
        usage
        ;;
esac

CANDIDATES=""
if [[ $# -eq 2 ]]; then
    [[ -f "$2" && -r "$2" ]] || fail "0022 candidate inventory not found or unreadable: $2"
    CANDIDATES="$(realpath -- "$2")"
fi


# ------------------------------------------------------------------------------
# Locate the installed Diaries project and its generated runtime files.
#
# The playbook installs this script beneath <project>/scripts, so the parent of
# the script directory is the production Compose project directory.
# ------------------------------------------------------------------------------

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"
ENV_FILE="${PROJECT_DIR}/.env"
HOST_CONFIG="${PROJECT_DIR}/config/responder/responder.json"

[[ -f "${COMPOSE_FILE}" ]] || fail "Compose file not found: ${COMPOSE_FILE}"
[[ -f "${ENV_FILE}" ]] || fail "Environment file not found: ${ENV_FILE}"
[[ -f "${HOST_CONFIG}" ]] || fail "Responder configuration not found: ${HOST_CONFIG}"
command -v docker >/dev/null 2>&1 || fail "docker was not found on PATH."
command -v realpath >/dev/null 2>&1 || fail "realpath was not found on PATH."


# ------------------------------------------------------------------------------
# Prepare the fixed host evidence directories.
# ------------------------------------------------------------------------------

EVIDENCE_ROOT="${DIARIES_MIGRATION0024_EVIDENCE_ROOT:-${HOME}/temp}"
DRY_RUN_OUTPUT="${EVIDENCE_ROOT}/dry-run"
APPLY_OUTPUT="${EVIDENCE_ROOT}/apply"

mkdir -p -- "${DRY_RUN_OUTPUT}" "${APPLY_OUTPUT}"
EVIDENCE_ROOT="$(realpath -- "${EVIDENCE_ROOT}")"
DRY_RUN_OUTPUT="${EVIDENCE_ROOT}/dry-run"
APPLY_OUTPUT="${EVIDENCE_ROOT}/apply"

if [[ "${MODE}" == "dry-run" ]]; then
    OUTPUT="${DRY_RUN_OUTPUT}"
else
    OUTPUT="${APPLY_OUTPUT}"
fi

if [[ -n "$(find "${OUTPUT}" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    fail "Evidence directory must be empty: ${OUTPUT}"
fi

PLAN=""
if [[ "${MODE}" == "apply" ]]; then
    PLAN="${DRY_RUN_OUTPUT}/0024-create-plan.json"
    [[ -f "${PLAN}" && -r "${PLAN}" ]] || fail "Reviewed dry-run plan not found: ${PLAN}"
fi


# ------------------------------------------------------------------------------
# Validate the deployed Compose project and required services.
# ------------------------------------------------------------------------------

RESPONDER_SERVICE="${DIARIES_RESPONDER_SERVICE:-diaries-responder}"
DB_SERVICE="${DIARIES_DB_SERVICE:-diaries-db}"
RESPONDER_CONFIG="${DIARIES_RESPONDER_CONFIG:-/config/responder.json}"
RESPONDER_JAR="${DIARIES_RESPONDER_JAR:-/opt/diaries/lib/diaries-responder.jar}"
MAIN_CLASS="com.rsmaxwell.diaries.responder.migration.migration0024.Migration0024ImageCatalogue"
COMPOSE=(docker compose --file "${COMPOSE_FILE}" --env-file "${ENV_FILE}")

cd -- "${PROJECT_DIR}"
"${COMPOSE[@]}" config --quiet

SERVICES="$("${COMPOSE[@]}" config --services)"
grep -Fxq "${RESPONDER_SERVICE}" <<<"${SERVICES}" ||
    fail "Responder service '${RESPONDER_SERVICE}' is not present in this deployment."
grep -Fxq "${DB_SERVICE}" <<<"${SERVICES}" ||
    fail "Database service '${DB_SERVICE}' is not present in this deployment."

RUNNING_SERVICES="$("${COMPOSE[@]}" ps --status running --services)"
grep -Fxq "${DB_SERVICE}" <<<"${RUNNING_SERVICES}" ||
    fail "Database service '${DB_SERVICE}' is not running."

if [[ "${MODE}" == "apply" ]] && grep -Fxq "${RESPONDER_SERVICE}" <<<"${RUNNING_SERVICES}"; then
    fail "Responder service '${RESPONDER_SERVICE}' is running. Stop it before apply."
fi


# ------------------------------------------------------------------------------
# Run the migration in a disposable container created from the deployed
# responder service. Compose supplies the production network, responder config,
# database identity and Files-root mounts. The host evidence root is mounted at
# a stable container path so the reviewed dry-run plan is reused by apply.
# ------------------------------------------------------------------------------

CONTAINER_EVIDENCE_ROOT="/migration-evidence"
CONTAINER_OUTPUT="${CONTAINER_EVIDENCE_ROOT}/${MODE}"
RUN_ARGS=(
    run
    --rm
    --no-deps
    --no-tty
    --pull never
    --entrypoint java
    --volume "${EVIDENCE_ROOT}:${CONTAINER_EVIDENCE_ROOT}"
)

MIGRATION_ARGS=(
    -cp "${RESPONDER_JAR}"
    "${MAIN_CLASS}"
    --config "${RESPONDER_CONFIG}"
    --output "${CONTAINER_OUTPUT}"
    --mode "${MODE}"
)

if [[ -n "${PLAN}" ]]; then
    MIGRATION_ARGS+=(--plan "${CONTAINER_EVIDENCE_ROOT}/dry-run/0024-create-plan.json")
fi

if [[ -n "${CANDIDATES}" ]]; then
    RUN_ARGS+=(--volume "${CANDIDATES}:/migration-input/candidates.csv:ro")
    MIGRATION_ARGS+=(--0022-candidates /migration-input/candidates.csv)
fi

RUN_ARGS+=("${RESPONDER_SERVICE}")

echo "Running 0024 Image catalogue reconciliation in ${MODE} mode..."
echo "Compose project: ${PROJECT_DIR}"
echo "Evidence:        ${OUTPUT}"

if "${COMPOSE[@]}" "${RUN_ARGS[@]}" "${MIGRATION_ARGS[@]}"; then
    RESULT=0
else
    RESULT=$?
fi

if [[ ${RESULT} -ne 0 ]]; then
    echo "ERROR: Reconciliation failed with exit code ${RESULT}." >&2
    echo "Review any evidence written beneath: ${OUTPUT}" >&2
    exit "${RESULT}"
fi

[[ -f "${OUTPUT}/0024-summary.json" ]] ||
    fail "Reconciliation returned success without 0024-summary.json in ${OUTPUT}."
[[ -f "${OUTPUT}/SHA256SUMS.txt" ]] ||
    fail "Reconciliation returned success without SHA256SUMS.txt in ${OUTPUT}."

echo "0024 Image catalogue reconciliation completed."
echo "Review the evidence in: ${OUTPUT}"
