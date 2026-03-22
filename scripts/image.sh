#!/bin/bash

set -euo pipefail
set -x

BASEDIR="$(dirname "$0")"
SCRIPT_DIR="$(cd $BASEDIR && pwd)"
SUBPROJECT_DIR="$(dirname $SCRIPT_DIR)"
PROJECT_DIR="$(dirname $SUBPROJECT_DIR)"
BUILD_DIR="${SUBPROJECT_DIR}/build"

. "${BUILD_DIR}/buildinfo"

cd "${SUBPROJECT_DIR}"

# ----------------------------
# Check the environment
# ----------------------------
required_vars=(
  REPOSITORY
  VERSION
  IMAGE_REGISTRY
  IMAGE_NAME
  DOCKER_USERNAME
  DOCKER_PASSWORD
)

for var in "${required_vars[@]}"; do
  if [ -z "${!var:-}" ]; then
    echo "ERROR: ${var} is not set or empty" >&2
    exit 2
  fi
done

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
EXTRA_TAGS=()

case "${REPOSITORY}" in
  releases)
    EXTRA_TAGS+=("latest")
    ;;
  integration)
    EXTRA_TAGS+=("integration")
    ;;
  snapshots)
    EXTRA_TAGS+=("snapshot")
    ;;
esac

echo "PROJECT_DIR=${PROJECT_DIR}"
echo "BUILD_DIR=${BUILD_DIR}"
echo "VERSION=${VERSION}"
echo "REPOSITORY=${REPOSITORY}"
echo "IMAGE_REPO=${IMAGE_REPO}"
echo "IMAGE_TAG=${IMAGE_TAG}"

# ----------------------------
# Preconditions
# ----------------------------

if [ ! -f "${SUBPROJECT_DIR}/files/Dockerfile" ]; then
  echo "ERROR: Dockerfile not found at ${SUBPROJECT_DIR}/files/Dockerfile" >&2
  exit 1
fi

# Optional: if the Dockerfile copies a packaged distribution built earlier
# then make sure it exists before building the image.
if [ ! -d "${SUBPROJECT_DIR}/build" ] || [ ! -f "${SUBPROJECT_DIR}/build/libs/diaries-responder.jar" ]; then
  echo "WARNING: expected responder build output not found yet."
  echo "Make sure the package/build stage runs before image.sh if the Dockerfile depends on built artifacts."
fi

# ----------------------------
# Build
# ----------------------------

docker build \
  --pull \
  --tag "${IMAGE_REPO}:${IMAGE_TAG}" \
  --build-arg VERSION="${VERSION}" \
  --build-arg BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --build-arg VCS_REF="${GIT_COMMIT:-unknown}" \
  "${SUBPROJECT_DIR}/files"

for tag in "${EXTRA_TAGS[@]}"; do
  docker tag "${IMAGE_REPO}:${IMAGE_TAG}" "${IMAGE_REPO}:${tag}"
done

# ----------------------------
# Write image info for later stages
# ----------------------------

cat > "${BUILD_DIR}/imageinfo" <<EOF
IMAGE_REPO="${IMAGE_REPO}"
IMAGE_TAG="${IMAGE_TAG}"
IMAGE="${IMAGE_REPO}:${IMAGE_TAG}"
EOF

echo "imageinfo:"
cat "${BUILD_DIR}/imageinfo"

# ----------------------------
# Push the image to the repository
# ----------------------------

echo "${DOCKER_PASSWORD}" | docker login "${IMAGE_REGISTRY}" \
  --username "${DOCKER_USERNAME}" \
  --password-stdin

docker push "${IMAGE_REPO}:${IMAGE_TAG}"
for tag in "${EXTRA_TAGS[@]}"; do
  docker push "${IMAGE_REPO}:${tag}"
done
