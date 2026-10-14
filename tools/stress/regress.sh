#!/bin/sh
# Builds the server and runs every regression scenario, printing a summary of each.
#   basic: hopper rings (item conservation), piston clocks, crops, a pen of mobs
#   misc:  redstone, rails, villages, portal, fluids, fire, sculk, TNT chains and a TNT cube
#   clock: freeze, exact stepping and changed simulation rate
#   graph: a random graph of hoppers, droppers, dispensers, hopper minecarts and copper golems
#          moving random items; every item must still exist at the end (GRAPH_SEED picks it)
#   soundness: five extended lifecycle/component edge cases, with exact audits
#              (SOUNDNESS_ROUNDS controls move/ride/reload repetitions, default 2)
#   focused: packaged-engine Java checks, including scheduled-tick edge cases
#   adversarial: item/entity conservation under chunk churn, portals, machines, pickups and
#                server-thread container destruction (ADVERSARIAL_WORKLOADS picks them)
# Exits non-zero if any scenario fails.
# usage: tools/stress/regress.sh [seconds-of-load, default 5] [scenario...]
# Set BUILD=0 to skip the build.
set -u
cd "$(dirname "$0")/../.."
mkdir -p build
seconds=${1:-5}
[ "$#" -gt 0 ] && shift
scenarios=${*:-basic misc clock graph soundness focused adversarial}
build_task=installDist
install_arg=-PinstallTo=build/install-dev
if [ "${TICKLESS_FABRIC:-0}" = 1 ]; then
  export SERVER_INSTALL=${SERVER_INSTALL:-build/install/fabric}
  export STRESS_MODS=${STRESS_MODS:-build/fabric-test-mods}
  build_task=buildFabricProbe
  install_arg=
  if [ "$#" = 0 ]; then scenarios="$scenarios fabric"; fi
fi

if [ "${BUILD:-1}" = 1 ]; then
  # Into a separate install, so a running play server's jar is never replaced.
  if ! ./build.sh "$build_task" ${install_arg:-} > build/regress-build.log 2>&1; then
    ./gradlew --stop > /dev/null 2>&1 || true
    tail -20 build/regress-build.log
    echo "FAIL: build, see build/regress-build.log"
    exit 1
  fi
  ./gradlew --stop > /dev/null 2>&1 || true
fi

python3 tools/stress/gen_world.py build/stress-cmds.txt "$seconds" > /dev/null
python3 tools/stress/gen_misc.py build/stress-misc.txt "$seconds" > /dev/null
python3 tools/stress/gen_graph.py build/stress-graph.txt build/stress-graph-expected.json $((seconds + 7)) "${GRAPH_SEED:-$(date +%s)}" > /dev/null
failed=
run() {
  if ! SERVER_INSTALL=${SERVER_INSTALL:-build/install-dev} TIMEOUT=$((seconds + 60)) "${STRESS_RUNNER:-tools/stress/run.sh}" "$1" "$2" > "build/regress-$1-harness.log" 2>&1; then
    failed="$failed $1"
  fi
  grep -v '^log:' "build/regress-$1-harness.log"
  echo "== $1"
  if ! python3 tools/stress/check.py "build/stress-$1/out.log" "$3" > "build/stress-$1/check.txt"; then
    failed="$failed $1"
  fi
  grep -E '^(hoppers read|error lines|lag warnings|check |PASS|FAIL)' "build/stress-$1/check.txt"
  grep -E '^  ' "build/stress-$1/check.txt" | grep -v 'World workers' | head -10
}
# Scenarios run sequentially so only one server or focused JVM uses memory at a time.
for scenario in $scenarios; do
  case "$scenario" in
    fabric)
      (if ! BUILD=0 tools/fabric/verify.sh > build/regress-fabric.txt 2>&1; then
         failed="$failed fabric"
       fi
       echo "$failed" > build/regress-fabric.failed)
      ;;
    focused)
      (if ! BUILD=0 SERVER_INSTALL=${SERVER_INSTALL:-build/install-dev} tools/check.sh > build/regress-focused.txt 2>&1; then
         failed="$failed focused"
       fi
       echo "$failed" > build/regress-focused.failed)
      ;;
    basic) (run basic build/stress-cmds.txt 65536 > "build/regress-basic.txt"; echo "$failed" > build/regress-basic.failed) ;;
    misc) (run misc build/stress-misc.txt 0 > "build/regress-misc.txt"; echo "$failed" > build/regress-misc.failed) ;;
    clock)
      (run clock tools/stress/clock.txt 0 > "build/regress-clock.txt"
       if ! python3 tools/stress/check_clock.py build/stress-clock/out.log >> "build/regress-clock.txt"; then
         failed="$failed clock"
       fi
       echo "$failed" > build/regress-clock.failed)
      ;;
    graph)
      (run graph build/stress-graph.txt 0 > "build/regress-graph.txt"
       if ! python3 tools/stress/check_graph.py build/stress-graph/out.log build/stress-graph-expected.json >> "build/regress-graph.txt"; then
         failed="$failed graph"
       fi
       echo "$failed" > build/regress-graph.failed)
      ;;
    soundness)
      (if ! python3 tools/stress/soundness.py run regress \
          --install "${SERVER_INSTALL:-build/install-dev}" --seconds "$seconds" \
          --rounds "${SOUNDNESS_ROUNDS:-2}" --workers "${SOUNDNESS_WORKERS:-16}" \
          --gc "${STRESS_GC:-zgc}" > build/regress-soundness.txt 2>&1; then
         failed="$failed soundness"
       fi
       echo "$failed" > build/regress-soundness.failed)
      ;;
    adversarial)
      (if ! STRESS_HEAP=${STRESS_HEAP:-4g} python3 tools/stress/adversarial.py run regress \
          ${ADVERSARIAL_WORKLOADS:-portal_rush churn flicker machines pickup commands xp} \
          --install "${SERVER_INSTALL:-build/install-dev}" --seconds "$seconds" --rounds 1 \
          > build/regress-adversarial.txt 2>&1; then
         failed="$failed adversarial"
       fi
       echo "$failed" > build/regress-adversarial.failed)
      ;;
    *) echo "unknown scenario: $scenario"; failed="$failed $scenario" ;;
  esac
done
for scenario in $scenarios; do
  [ -f "build/regress-$scenario.txt" ] && cat "build/regress-$scenario.txt"
  [ -f "build/regress-$scenario.failed" ] && failed="$failed $(cat "build/regress-$scenario.failed")"
  rm -f "build/regress-$scenario.failed"
done
failed=$(echo $failed)

if [ -n "$failed" ]; then
  echo "FAILED:$failed"
  exit 1
fi
echo "ALL PASSED"
