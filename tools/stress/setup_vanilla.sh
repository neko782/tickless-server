#!/bin/sh
# Make the official Minecraft 26.3 server usable with tools/stress/run.sh
# (SERVER_INSTALL=build/install/vanilla).
set -eu

cd "$(dirname "$0")/../.."
mkdir -p build/downloads build/install/vanilla/bin
jar=build/downloads/server-26.3.jar
if [ ! -f "$jar" ]; then
  curl -fL -o "$jar" https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar
fi
printf '%s\n' "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c  $jar" | sha1sum --check
cat > build/install/vanilla/bin/minecraft-server <<'SH'
#!/bin/sh
set -eu
exec java ${JAVA_OPTS:-} -jar /workspace/build/downloads/server-26.3.jar "$@"
SH
chmod +x build/install/vanilla/bin/minecraft-server
