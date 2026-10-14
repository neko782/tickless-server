#!/bin/sh
# Install the pinned Minecraft 26.3 Fabric + Lithium benchmark server under build/.
set -eu

cd "$(dirname "$0")/../.."
mkdir -p build/lithium-server/mods build/install/lithium/bin

curl -fsSL 'https://meta.fabricmc.net/v2/versions/loader/26.3/0.19.5/1.1.2/server/jar' \
  -o build/lithium-server/fabric-server-launch.jar
curl -fsSL 'https://cdn.modrinth.com/data/gvQqBUqZ/versions/xS0Q8LSi/lithium-fabric-0.26.2%2Bmc26.3.jar' \
  -o 'build/lithium-server/mods/lithium-fabric-0.26.2+mc26.3.jar'

printf '%s\n' \
  '0b56ad54d762172e8b8748e467f584f071e4dedecd93dc336cf2c68837e790be  build/lithium-server/fabric-server-launch.jar' \
  '0be5b17ef9f6e9244cd07b24b227553a73aec0cf93158cf28099f3094c167f1b  build/lithium-server/mods/lithium-fabric-0.26.2+mc26.3.jar' \
  | sha256sum --check

cat > build/install/lithium/bin/minecraft-server <<'EOF'
#!/bin/sh
set -eu
ln -sfn /workspace/build/lithium-server/mods mods
exec java ${JAVA_OPTS:-} -jar /workspace/build/lithium-server/fabric-server-launch.jar "$@"
EOF
chmod +x build/install/lithium/bin/minecraft-server
