#!/bin/sh
# Make Paper 26.3 (build 181) usable with tools/stress/run.sh. Two installs:
#   build/install/paper       Paper's default configuration
#   build/install/paper-noear entity activation range off, so far-away mobs run full AI as on
#                             vanilla and tickless (the stress worlds have no players nearby)
set -eu

cd "$(dirname "$0")/../.."
mkdir -p build/downloads build/install/paper/bin build/install/paper-noear/bin
jar=build/downloads/paper-26.3-181.jar
if [ ! -f "$jar" ]; then
  curl -fL -o "$jar" https://fill-data.papermc.io/v1/objects/cada5af7f14363bc8545c0e973130de9431f6dc678dc612759187695027c27d9/paper-26.3-181.jar
fi
printf '%s\n' "cada5af7f14363bc8545c0e973130de9431f6dc678dc612759187695027c27d9  $jar" | sha256sum --check
cat > build/install/paper/bin/minecraft-server <<'SH'
#!/bin/sh
set -eu
exec java ${JAVA_OPTS:-} -jar /workspace/build/downloads/paper-26.3-181.jar "$@"
SH
cat > build/install/paper-noear/bin/minecraft-server <<'SH'
#!/bin/sh
set -eu
cat > spigot.yml <<'YML'
config-version: 12
world-settings:
  default:
    entity-activation-range:
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
      water: 0
      villagers: 0
      flying-monsters: 0
      wake-up-inactive:
        animals-max-per-tick: 0
        monsters-max-per-tick: 0
        villagers-max-per-tick: 0
        flying-monsters-max-per-tick: 0
YML
exec java ${JAVA_OPTS:-} -jar /workspace/build/downloads/paper-26.3-181.jar "$@"
SH
chmod +x build/install/paper/bin/minecraft-server build/install/paper-noear/bin/minecraft-server
