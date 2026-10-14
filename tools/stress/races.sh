#!/bin/sh
# Runs stress scenarios on a server built with the collection race check (see RaceCheck) and
# fails on any race it reports that tools/stress/race-allowlist.txt does not list.
#   selftest: tools/race/RaceDetectorSelfTest.java, races the checker must and must not report
#   basic:   hopper rings, piston clocks, crops and a mob pen (gen_world.py)
#   misc:    redstone, rails, villages, portals, fluids, fire, sculk and TNT (gen_misc.py)
#   graph:   hoppers, droppers, minecarts and copper golems moving items (gen_graph.py)
#   players: bots joining, leaving, dying and building while commands run (races-players.txt)
# The race build goes to build/install-race, so no other install is replaced. Set BUILD=0 to
# reuse it. Logs are in build/stress-races-<scenario>/.
# usage: tools/stress/races.sh [seconds-of-load, default 10] [scenario...]
set -u
cd "$(dirname "$0")/../.."
mkdir -p build
seconds=${1:-10}
[ "$#" -gt 0 ] && shift
scenarios=${*:-selftest basic misc graph players}
install=build/install-race

if [ "${BUILD:-1}" = 1 ]; then
  if ! ./build.sh installDist -PinstallTo="$install" -PraceCheck > build/races-build.log 2>&1; then
    ./gradlew --stop > /dev/null 2>&1 || true
    tail -20 build/races-build.log
    echo "FAIL: build, see build/races-build.log"
    exit 1
  fi
  ./gradlew --stop > /dev/null 2>&1 || true
fi

failed=
for scenario in $scenarios; do
  case "$scenario" in
    selftest)
      echo "== selftest"
      if ! docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" -w /workspace eclipse-temurin:27-jdk sh -c "
          rm -rf build/races-selftest && mkdir -p build/races-selftest \
          && javac -nowarn -cp '$install/lib/*' -d build/races-selftest tickless-server/src/buildtools/java/RaceTransform.java tools/race/RaceDetectorSelfTest.java \
          && java -Dtickless.raceCheck=true -cp 'build/races-selftest:$install/lib/*' RaceDetectorSelfTest" > build/races-selftest.log 2>&1; then
        failed="$failed selftest"
      fi
      grep -E '^(ok|FAIL|PASS)' build/races-selftest.log
      continue ;;
    basic) python3 tools/stress/gen_world.py build/races-basic.txt "$seconds" > /dev/null; commands=build/races-basic.txt ;;
    misc)
      # A smaller TNT cube with longer to finish: explosions run several times slower when checked.
      python3 tools/stress/gen_misc.py build/races-misc.txt "$seconds" 15 > /dev/null
      sed -i "s/^poll 30 /poll 240 /" build/races-misc.txt
      commands=build/races-misc.txt ;;
    graph)
      python3 tools/stress/gen_graph.py build/races-graph.txt build/races-graph-expected.json $((seconds + 7)) "${GRAPH_SEED:-$(date +%s)}" > /dev/null
      commands=build/races-graph.txt ;;
    players) commands=tools/stress/races-players.txt ;;
    *) echo "unknown scenario: $scenario"; failed="$failed $scenario"; continue ;;
  esac

  # The checked build is several times slower; the TNT cube in misc needs the extra time.
  if ! SERVER_INSTALL=$install BOT_INSTALL=$install TIMEOUT=$((seconds + 480)) LOCK_WAIT_KILL=0 \
      tools/stress/run.sh "races-$scenario" "$commands" "-Dtickless.raceCheck=true" > "build/races-$scenario-harness.log" 2>&1; then
    grep -v '^log:' "build/races-$scenario-harness.log"
    failed="$failed $scenario"
  fi

  echo "== $scenario"
  if ! python3 tools/stress/check_races.py "build/stress-races-$scenario/out.log"; then
    failed="$failed $scenario"
  fi
done

failed=$(echo $failed)
if [ -n "$failed" ]; then
  echo "FAILED: $failed"
  exit 1
fi
echo "ALL PASSED"
