#!/usr/bin/env bash
# CodeAgent build + test (Linux / macOS). Zero dependency: needs only JDK 17.
# No API key is required to build or run the test suite.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/out"
SRC="$ROOT/src/main/java"
TST="$ROOT/src/test/java"

echo "[1/2] compiling..."
rm -rf "$OUT"
mkdir -p "$OUT"
find "$SRC" "$TST" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -d "$OUT" @"$OUT/sources.txt"

echo "[2/2] running tests..."
java -cp "$OUT" com.codeagent.test.AllTests
echo "BUILD OK"
