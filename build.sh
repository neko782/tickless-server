#!/bin/sh
# Applies the patches if needed and runs Gradle. The Gradle daemon provisions JDK 25 itself
# (gradle/gradle-daemon-jvm.properties); the host only needs Java 17+ to start the wrapper.
# usage: ./build.sh [gradle args...]   (default: build)
set -eu

cd "$(dirname "$0")"
if [ "$#" -eq 0 ]; then
  set -- build
fi
if [ ! -d tickless-server/src/minecraft/java ]; then
  ./gradlew applyPatches
fi
exec ./gradlew "$@"
