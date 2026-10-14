#!/bin/sh
set -eu

cd "$(dirname "$0")"
mkdir -p run

if [ ! -x build/install/minecraft-server/bin/minecraft-server ]; then
  echo 'Build the server first with ./build.sh' >&2
  exit 1
fi

exec docker run --rm -i \
  --user "$(id -u):$(id -g)" \
  -p 25565:25565 \
  -v "$PWD:/workspace" \
  -w /workspace/run \
  -e JAVA_OPTS="-XX:+UseZGC ${JAVA_OPTS:-}" \
  eclipse-temurin:25-jdk \
  /workspace/build/install/minecraft-server/bin/minecraft-server --nogui "$@"
