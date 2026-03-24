#!/bin/sh

set -eu
set -x

BASEDIR=$(dirname "$0")
SCRIPT_DIR=$(cd "$BASEDIR" && pwd)
SUBPROJECT_DIR=$(dirname "$SCRIPT_DIR")
PROJECT_DIR=$(dirname "$SUBPROJECT_DIR")
BUILD_DIR="${SUBPROJECT_DIR}/build"

. "${BUILD_DIR}/buildinfo"

cd "${SUBPROJECT_DIR}"

# ----------------------------
# Small logging helper
# ----------------------------
log() {
  printf '%s\n' "$*"
}

# ----------------------------
# Check the environment
# ----------------------------
required_vars="
REPOSITORY
VERSION
IMAGE_REGISTRY
IMAGE_NAME
DOCKER_USERNAME
DOCKER_PASSWORD
"

for var in $required_vars; do
  eval "value=\${$var-}"
  if [ -z "$value" ]; then
    log "ERROR: ${var} is not set or empty" >&2
    exit 2
  fi
done

log "DOCKER_USERNAME: $DOCKER_USERNAME, DOCKER_PASSWORD: $DOCKER_PASSWORD"

# ----------------------------
# Image naming
# ----------------------------

if [ -n "${IMAGE_NAMESPACE:-}" ]; then
  IMAGE_REPO="${IMAGE_REGISTRY}/${IMAGE_NAMESPACE}/${IMAGE_NAME}"
else
  IMAGE_REPO="${IMAGE_REGISTRY}/${IMAGE_NAME}"
fi

IMAGE_TAG="${VERSION}"

# Optional convenience tags
EXTRA_TAGS=""
case "${REPOSITORY}" in
  releases)
    EXTRA_TAGS="latest"
    ;;
  integration)
    EXTRA_TAGS="integration"
    ;;
  snapshots)
    EXTRA_TAGS="snapshot"
    ;;
esac

NAMES="${IMAGE_REPO}:${IMAGE_TAG}"
for tag in $EXTRA_TAGS; do
  NAMES="${NAMES},${IMAGE_REPO}:${tag}"
done

log "PROJECT_DIR=${PROJECT_DIR}"
log "BUILD_DIR=${BUILD_DIR}"
log "VERSION=${VERSION}"
log "REPOSITORY=${REPOSITORY}"
log "IMAGE_REPO=${IMAGE_REPO}"
log "IMAGE_TAG=${IMAGE_TAG}"
log "NAMES=${NAMES}"

# ----------------------------
# Preconditions
# ----------------------------
DOCKERFILE_DIR="${SUBPROJECT_DIR}/scripts/files"
DOCKERFILE_PATH="${DOCKERFILE_DIR}/Dockerfile"

if [ ! -f "${DOCKERFILE_PATH}" ]; then
  log "ERROR: Dockerfile not found at ${DOCKERFILE_PATH}" >&2
  exit 1
fi

# Optional: useful warning if later you switch Dockerfile to COPY the fat jar
if [ ! -d "${SUBPROJECT_DIR}/build" ] || [ ! -d "${SUBPROJECT_DIR}/build/libs" ]; then
  log "WARNING: build output directory not found yet."
  log "If the Dockerfile depends on build artifacts, make sure the package/build stage runs first."
fi

# ----------------------------
# Registry auth for BuildKit
# ----------------------------
DOCKER_CONFIG_DIR="${HOME}/.docker"
DOCKER_CONFIG_FILE="${DOCKER_CONFIG_DIR}/config.json"

mkdir -p "${DOCKER_CONFIG_DIR}"
chmod 700 "${DOCKER_CONFIG_DIR}"

AUTH=$(printf '%s:%s' "${DOCKER_USERNAME}" "${DOCKER_PASSWORD}" | base64 | tr -d '\n')

cleanup() {
  rm -f "${DOCKER_CONFIG_FILE}"
}
trap cleanup EXIT HUP INT TERM

case "${IMAGE_REGISTRY}" in
  docker.io)
    cat > "${DOCKER_CONFIG_FILE}" <<EOF
{
  "auths": {
    "docker.io": {
      "auth": "${AUTH}"
    },
    "https://index.docker.io/v1/": {
      "auth": "${AUTH}"
    }
  }
}
EOF
    ;;
  *)
    cat > "${DOCKER_CONFIG_FILE}" <<EOF
{
  "auths": {
    "${IMAGE_REGISTRY}": {
      "auth": "${AUTH}"
    }
  }
}
EOF
    ;;
esac

chmod 600 "${DOCKER_CONFIG_FILE}"

log "Docker auth config written for registry ${IMAGE_REGISTRY}"
log "Docker config path: ${DOCKER_CONFIG_FILE}"

# ----------------------------
# Build + push
# ----------------------------
buildctl-daemonless.sh build \
  --frontend dockerfile.v0 \
  --local context="${SUBPROJECT_DIR}" \
  --local dockerfile="${DOCKERFILE_DIR}" \
  --opt build-arg:VERSION="${VERSION}" \
  --opt build-arg:BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --opt build-arg:VCS_REF="${GIT_COMMIT:-unknown}" \
  --output "type=image,name=${NAMES},push=true"

# ----------------------------
# Write image info for later stages
# ----------------------------
cat > "${BUILD_DIR}/imageinfo" <<EOF
IMAGE_REPO="${IMAGE_REPO}"
IMAGE_TAG="${IMAGE_TAG}"
IMAGE="${IMAGE_REPO}:${IMAGE_TAG}"
EOF

log "imageinfo:"
cat "${BUILD_DIR}/imageinfo"