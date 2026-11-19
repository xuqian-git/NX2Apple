#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TASK_BUILD=${TASK_BUILD:-"$PWD/streaming/.build"}
mkdir -p "$TASK_BUILD/java"
javac -d "$TASK_BUILD/java" streaming/android/app/src/main/java/dev/nxstream/Wire.java streaming/tests/WireTest.java
java -cp "$TASK_BUILD/java" dev.nxstream.WireTest
python3 -m py_compile streaming/overlay.py streaming/tests/smoke.py
if [[ $(uname) == Darwin ]]; then
  xcrun --sdk iphoneos clang -fobjc-arc -fmodules -arch arm64 -miphoneos-version-min=26.0 -isysroot "$(xcrun --sdk iphoneos --show-sdk-path)" -fsyntax-only streaming/ios/nx_stream.m
fi
