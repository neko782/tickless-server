#!/bin/sh
# Runs the gen_heavy.py workloads and prints one summary line each.
# usage: tools/stress/heavy.sh <label> [workload...]
# SERVER_INSTALL selects the server (e.g. build/install/vanilla); the build is not rebuilt.
set -eu
cd "$(dirname "$0")/../.."
label=$1
shift
workloads=${*:-tnt sand light chunkgen}
failed=
for w in $workloads; do
  python3 tools/stress/gen_heavy.py "$w" "build/heavy-$w.txt" > /dev/null
  if ! TIMEOUT=${TIMEOUT:-240} "${STRESS_RUNNER:-tools/stress/run.sh}" "$label-$w" "build/heavy-$w.txt" "${EXTRA_OPTS:-}" > "build/heavy-$label-$w-harness.log" 2>&1; then
    failed="$failed $w"
  fi
  cat "build/heavy-$label-$w-harness.log"
  if ! python3 tools/stress/check.py "build/stress-$label-$w/out.log" 0 > "build/stress-$label-$w/check.txt"; then
    failed="$failed $w"
  fi
  cat "build/stress-$label-$w/check.txt"
  printf '%-10s %-9s ' "$label" "$w"
  if ! python3 tools/stress/bench_heavy.py "build/stress-$label-$w" > "build/stress-$label-$w/bench.txt"; then
    failed="$failed $w"
  fi
  paste -sd' ' "build/stress-$label-$w/bench.txt"
done
if [ -n "$failed" ]; then
  echo "FAILED:$failed"
  exit 1
fi
