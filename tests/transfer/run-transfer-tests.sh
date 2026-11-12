#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TEST_CLASSES="$PROJECT_DIR/build/transfer-test-classes"
mkdir -p "$TEST_CLASSES"
TRANSFER_SOURCE="$PROJECT_DIR/app/src/main/java/org/yi4kplus/companion/ReliableHttpTransfer.java"
TEST_SOURCE="$PROJECT_DIR/tests/transfer/ReliableHttpTransferTest.java"
if command -v javac >/dev/null 2>&1; then
  javac --release 17 --add-modules jdk.httpserver -d "$TEST_CLASSES" "$TRANSFER_SOURCE" "$TEST_SOURCE"
else
  TOOLS_DIR=${YI_TEST_TOOLS_DIR:-"$PROJECT_DIR/tools"}
  ECJ_JAR=${YI_ECJ_JAR:-"$TOOLS_DIR/ecj.jar"}
  if [ ! -f "$ECJ_JAR" ]; then
    printf '%s\n' 'Install JDK 17+ or set YI_ECJ_JAR to an Eclipse Java compiler JAR.' >&2
    exit 1
  fi
  java -jar "$ECJ_JAR" -17 -proc:none -warn:none -d "$TEST_CLASSES" "$TRANSFER_SOURCE" "$TEST_SOURCE"
fi
timeout 90s java --add-modules jdk.httpserver -ea \
  -Dyi.test.tmp="$PROJECT_DIR/build" -cp "$TEST_CLASSES" \
  org.yi4kplus.companion.ReliableHttpTransferTest
