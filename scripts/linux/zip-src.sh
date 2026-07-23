#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="${1:-.}"
ZIP_NAME="${2:-java-src.zip}"

cd "$PROJECT_DIR"

# Build a pruned file list
find . \
  -path './build'    -prune -o \
  -path './runtime'  -prune -o \
  -path './.gradle'  -prune -o \
  -path './out'      -prune -o \
  -path './target'   -prune -o \
  -path './bin'      -prune -o \
  -path './.idea'    -prune -o \
  -path './.vscode'  -prune -o \
  -path './.git'     -prune -o \
  -type f -print \
| zip -q "$ZIP_NAME" -@


echo "Done: $ZIP_NAME"
