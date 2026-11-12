#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TOOLS_DIR=${YI_TEST_TOOLS_DIR:-"$PROJECT_DIR/tools"}
if [ ! -f "$TOOLS_DIR/ecj.jar" ] || [ ! -f "$TOOLS_DIR/json.jar" ]; then
  echo 'Set YI_TEST_TOOLS_DIR to a folder containing ecj.jar and json.jar.' >&2
  exit 1
fi
OUT="$PROJECT_DIR/build/host-protocol-tests"
mkdir -p "$OUT"
java -jar "$TOOLS_DIR/ecj.jar" -17 -proc:none -warn:none -cp "$TOOLS_DIR/json.jar" -d "$OUT" \
  "$PROJECT_DIR/tests/protocol/stubs/android/net/Network.java" \
  "$PROJECT_DIR/tests/protocol/stubs/org/yi4kplus/companion/DebugLog.java" \
  "$PROJECT_DIR/app/src/main/java/org/yi4kplus/companion/JsonFramer.java" \
  "$PROJECT_DIR/app/src/main/java/org/yi4kplus/companion/YiCamera.java" \
  "$PROJECT_DIR/tests/protocol/YiCameraTransportTest.java"
timeout 35s java -ea -cp "$OUT:$TOOLS_DIR/json.jar" org.yi4kplus.companion.YiCameraTransportTest
