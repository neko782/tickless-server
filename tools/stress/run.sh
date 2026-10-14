#!/bin/sh
# Runs a dedicated server in Docker, feeds it a command script, and stops it.
#
# usage: tools/stress/run.sh <name> <commands-file> [extra JAVA_OPTS]
# Lines in the commands file are sent to the server console, except "sleep N",
# "bots COUNT SECONDS", which starts tools/BotClient.java inside the container
# (output in bots.log), and "poll SECONDS COMMAND", which repeats COMMAND every POLL_INTERVAL seconds (default 0.5)
# (preceded by "say poll", followed by a game time query) until it passes.
# Output goes to build/stress-<name>/out.log, CPU samples to cpu.log.
# The run is killed after TIMEOUT seconds (default 300), as soon as a world
# lock wait exceeds 10s (unless LOCK_WAIT_KILL=0, for workloads whose events legitimately hold
# locks that long; waits outside the deadlock resolver's graph still kill), or (unless FAIL_FAST=0) on the first ERROR or
# exception line; a thread dump is written to threads.txt first.
# DATAPACK=<dir> copies that datapack into the world before the server starts.
# With TEMPLATE=1 the world is copied from build/stress-template-world, which
# skips terrain generation.
# SERVER_INSTALL selects another installed server, relative to the repository.
# STRESS_GC selects zgc (default) or g1; STRESS_JAVA_OPTS adds explicit test JVM options.
set -eu

cd "$(dirname "$0")/../.."
name=$1
commands=$2
java_opts="${3:-} ${STRESS_JAVA_OPTS:-}"
case "${STRESS_GC:-zgc}" in
  zgc) gc_opts=-XX:+UseZGC ;;
  g1) gc_opts=-XX:+UseG1GC ;;
  *) echo 'STRESS_GC must be zgc or g1' >&2; exit 2 ;;
esac
dir=build/stress-$name
rm -rf "$dir"
mkdir -p "$dir"
echo eula=true > "$dir/eula.txt"
cat > "$dir/server.properties" <<PROPS
online-mode=false
level-seed=123456789
view-distance=8
simulation-distance=8
spawn-protection=0
max-tick-time=-1
sync-chunk-writes=false
gamemode=creative
max-players=500
white-list=false
enforce-whitelist=false
network-compression-threshold=-1
pause-when-empty-seconds=-1
PROPS
mkfifo "$dir/in"
if [ "${TEMPLATE:-0}" = 1 ] && [ -d build/stress-template-world ]; then
  cp -r build/stress-template-world "$dir/world"
fi
if [ -n "${DATAPACK:-}" ]; then
  mkdir -p "$dir/world/datapacks"
  cp -r "$DATAPACK" "$dir/world/datapacks/"
fi

docker run --rm -i --name "tickless-$name" \
  --user "$(id -u):$(id -g)" \
  -e JAVA_OPTS="-Xmx6g $gc_opts -Dtickless.statsIntervalSeconds=5 $java_opts" \
  -v "$PWD:/workspace" -w "/workspace/$dir" \
  eclipse-temurin:25-jdk \
  "/workspace/${SERVER_INSTALL:-build/install/minecraft-server}/bin/minecraft-server" --nogui < "$dir/in" > "$dir/out.log" 2>&1 &
server=$!

dump_and_kill() {
  echo "$1" >&2
  printf '%s\n' "$1" > "$dir/failure.txt"
  # Give the server a moment to log the world lock wait chain, which is more reliable than the
  # dump below: with thousands of virtual threads, Thread.dump_to_file can crash JDK 25.
  sleep 1
  # Thread.dump_to_file includes virtual threads; Thread.print does not.
  # Platform threads (including the server thread) first: that dump is safe.
  docker exec "tickless-$name" sh -c 'jcmd $(pgrep java | head -1) Thread.print' > "$dir/threads-platform.txt" 2>&1 || true
  docker exec "tickless-$name" sh -c 'jcmd $(pgrep java | head -1) Thread.dump_to_file -overwrite /tmp/threads.txt > /dev/null && cat /tmp/threads.txt' > "$dir/threads.txt" 2>&1 || true
  docker kill "tickless-$name" > /dev/null 2>&1 || true
}

(
  deadline=$(( $(date +%s) + ${TIMEOUT:-300} ))
  while kill -0 "$server" 2>/dev/null; do
    if grep -qE 'OUTSIDE THE WAIT GRAPH|World wait outside the lock graph' "$dir/out.log" 2>/dev/null; then
      dump_and_kill "world wait the deadlock resolver cannot see, see $dir/out.log"
      break
    fi
    if [ "${LOCK_WAIT_KILL:-1}" = 1 ] && grep -q 'has waited 10s for a world lock' "$dir/out.log" 2>/dev/null; then
      dump_and_kill "world lock wait over 10s, see $dir/threads.txt"
      break
    fi
    if [ "${FAIL_FAST:-1}" = 1 ] && grep -qE '/ERROR\]|Exception in world event|Encountered an unexpected exception' "$dir/out.log" 2>/dev/null; then
      sleep 1
      dump_and_kill "error logged, see $dir/out.log"
      break
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      dump_and_kill "timed out, see $dir/threads.txt"
      break
    fi
    sleep 0.5
  done
) &
watchdog=$!

# CPU samples: wall-clock time and container CPU in percent of one core (3200% = 32 cores).
(
  while kill -0 "$server" 2>/dev/null; do
    cpu=$(docker stats --no-stream --format '{{.CPUPerc}}' "tickless-$name" 2>/dev/null) || { sleep 0.5; continue; }
    printf '%s %s\n' "$(date +%T)" "${cpu%\%}" >> "$dir/cpu.log"
  done
) &
sampler=$!

exec 3>"$dir/in"
until grep -q 'Done (' "$dir/out.log" 2>/dev/null; do
  if ! kill -0 "$server" 2>/dev/null; then
    echo "server exited before startup finished" >&2
    exit 1
  fi
  sleep 0.3
done

while IFS= read -r line && kill -0 "$server" 2>/dev/null; do
  case "$line" in
    sleep\ *) sleep "${line#sleep }" ;;
    bots\ *)
      set -- ${line#bots }
      docker exec "tickless-$name" sh -c "mkdir -p /tmp/bot && javac -nowarn -cp '/workspace/${SERVER_INSTALL:-build/install/minecraft-server}/lib/*' -d /tmp/bot /workspace/tools/BotClient.java && java -Xmx2g ${BOT_OPTS:-} -cp '/tmp/bot:/workspace/${SERVER_INSTALL:-build/install/minecraft-server}/lib/*' BotClient 127.0.0.1 25565 $1 $2" >> "$dir/bots.log" 2>&1 &
      ;;
    poll\ *)
      # poll SECONDS COMMAND: runs COMMAND (then a game time query) every POLL_INTERVAL s until it passes.
      set -f
      set -- ${line#poll }
      set +f
      poll_end=$(( $(date +%s) + $1 ))
      poll_begin=$(wc -l < "$dir/out.log")
      poll_ok=0
      shift
      while [ "$(date +%s)" -lt "$poll_end" ] && kill -0 "$server" 2>/dev/null; do
        printf 'say poll\n%s\ntime query gametime\n' "$*" >&3
        sleep "${POLL_INTERVAL:-0.5}"
        if awk -v begin="$poll_begin" -f tools/stress/poll_check.awk "$dir/out.log"; then
          poll_ok=1
          break
        fi
      done
      if [ "$poll_ok" != 1 ]; then
        printf 'poll did not pass before its deadline: %s\n' "$*" > "$dir/failure.txt"
        cat "$dir/failure.txt" >&2
        break
      fi
      ;;
    ''|\#*) ;;
    *) printf '%s\n' "$line" >&3 ;;
  esac
done < "$commands"

printf 'stop\n' >&3 2>/dev/null || true
server_status=0
wait "$server" || server_status=$?
kill "$watchdog" "$sampler" 2>/dev/null || true
exec 3>&-
echo "log: $dir/out.log"
printf '%s\n' "$server_status" > "$dir/exit-code.txt"
if [ -f "$dir/failure.txt" ]; then
  exit 1
fi
exit "$server_status"
