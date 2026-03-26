#!/bin/bash

set -x

BASEDIR="$(dirname "$0")"
SCRIPT_DIR="$(cd $BASEDIR && pwd)"
SUBPROJECT_DIR="$(dirname $SCRIPT_DIR)"
PROJECT_DIR="$(dirname $SUBPROJECT_DIR)"
BUILD_DIR="${SUBPROJECT_DIR}/build"

. "${BUILD_DIR}/buildinfo"

cd "${PROJECT_DIR}"

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
ls -al "$SUBPROJECT_DIR"
set +x

# ----------------------------
# pre checks
# ----------------------------

echo "=== log4j entries in libs.versions.toml ==="
grep -n "log4j" gradle/libs.versions.toml || grep -n "log4j" libs.versions.toml

echo "=== runtimeClasspath dependency insight ==="
./gradlew :diaries-responder:dependencyInsight --dependency log4j --configuration runtimeClasspath

# ----------------------------
# Build and publish
# ----------------------------

${PROJECT_DIR}/gradlew :diaries-responder:publish --info --stacktrace \
    -PrepositoryName=${REPOSITORY} \
    -PprojectVersion=${VERSION}

# ----------------------------
# post checks
# ----------------------------

echo "=== packaged log4j-api version from fat jar ==="
unzip -p diaries-responder/build/libs/diaries-responder-*-fat.jar \
  META-INF/maven/org.apache.logging.log4j/log4j-api/pom.properties || true

echo "=== packaged log4j-core version from fat jar ==="
unzip -p diaries-responder/build/libs/diaries-responder-*-fat.jar \
  META-INF/maven/org.apache.logging.log4j/log4j-core/pom.properties || true
