#!/bin/bash
set -x
BASEDIR=$(dirname "$0")
SCRIPT_DIR=$(cd $BASEDIR && pwd)
PROJECT_DIR=$(dirname $SCRIPT_DIR)
BUILD_DIR=${PROJECT_DIR}/build

. ${BUILD_DIR}/buildinfo

cd ${PROJECT_DIR}

# ----------------------------
# Check the environment
# ----------------------------

required_vars=(
  GRADLE_USER_HOME
  REPOSITORY
  VERSION
)

for var in "${required_vars[@]}"; do
  if [ -z "${!var:-}" ]; then
    echo "ERROR: ${var} is not set or empty" >&2
    exit 2
  fi
done

set -x
echo "GRADLE_USER_HOME=$GRADLE_USER_HOME"
ls -al "$GRADLE_USER_HOME"
ls -al "$PROJECT_DIR"
set +x


# ----------------------------
# Build and publish
# ----------------------------

${PROJECT_DIR}/gradlew publish --info --stacktrace \
    -PrepositoryName=${REPOSITORY} \
    -PprojectVersion=${VERSION}
