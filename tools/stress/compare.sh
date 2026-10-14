#!/bin/sh
# Runs the same gen_world.py workload on several servers, one after another, checks each run
# (item conservation, errors) and prints a comparison table (bench_compare.py).
#
# usage: tools/stress/compare.sh <label> <mobs> [warmup-seconds] [window-seconds] [server...]
# Servers are install names: tickless (build/install/minecraft-server), vanilla, paper and
# paper-noear (set up by setup_vanilla.sh and setup_paper.sh), plain and fabric.
# Default: vanilla, paper, paper-noear and tickless.
set -eu

cd "$(dirname "$0")/../.."
label=$1
mobs=$2
warmup=${3:-15}
window=${4:-30}
shift $(( $# < 4 ? $# : 4 ))
servers=${*:-vanilla paper paper-noear tickless}

script=build/compare-$label.txt
python3 tools/stress/gen_world.py "$script.raw" "$window" "$mobs" 4 > /dev/null
python3 - "$script.raw" "$script" "$warmup" "$window" <<'PY'
import sys
src, dst, warmup, window = sys.argv[1:]
s = open(src).read()
marked = (f"sleep {warmup}\nsay BENCH_START\ntime query gametime\nsleep {window}\n"
          "say BENCH_END\ntime query gametime\n")
assert f"sleep {window}\n" in s
open(dst, "w").write(s.replace(f"sleep {window}\n", marked, 1))
PY

logs=
failed=0
for server in $servers; do
  case "$server" in
    tickless) install=build/install/minecraft-server ;;
    *) install=build/install/$server ;;
  esac
  name=cmp-$label-$server
  echo "== $server"
  if ! SERVER_INSTALL=$install TIMEOUT=${TIMEOUT:-600} tools/stress/run.sh "$name" "$script"; then
    echo "run failed: $server"
    failed=1
  fi
  if python3 tools/stress/check.py "build/stress-$name/out.log" 135424 > "build/stress-$name/check.txt"; then
    tail -1 "build/stress-$name/check.txt"
  else
    cat "build/stress-$name/check.txt"
    failed=1
  fi
  logs="$logs build/stress-$name/out.log"
done
python3 tools/stress/bench_compare.py $logs

exit "$failed"
