#!/bin/sh
# Saturated, sequential worker sweep; no simultaneous servers or profiler.
# Usage: sh tools/stress/scaling.sh label [worker counts...]
# Override MOBS, BENCH_SECONDS, WARMUP, STABLE_ENTITIES or SERVER_INSTALL.
set -eu
cd "$(dirname "$0")/../.."
label=$1
shift
case "$label" in
  ''|*[!a-zA-Z0-9_-]*) echo "Use letters, digits, underscores or hyphens in the label" >&2; exit 1 ;;
esac
if [ "$#" -eq 0 ]; then set -- 1 4 8 16 31; fi
seconds=${BENCH_SECONDS:-60}
warmup=${WARMUP:-30}
commands=build/scaling-$label.txt
python3 tools/stress/gen_world.py "$commands" "$seconds" "${MOBS:-48000}" 4
python3 - "$commands" "$seconds" "$warmup" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
prefix, marker, suffix = p.read_text().rpartition(f"sleep {sys.argv[2]}\n")
if not marker:
    raise SystemExit("Missing timed window")
text = prefix + f"sleep {sys.argv[3]}\nsay BENCH_START\n" + marker + "say BENCH_END\n" + suffix
import os
if os.environ.get("STABLE_ENTITIES") == "1":
    text = text.replace("{PersistenceRequired:1b}", '{PersistenceRequired:1b,Invulnerable:1b,Tags:["scaling_mob"]}')
    text = text.replace("say BENCH_END\n", f'say BENCH_END\nsay check stable-population-{os.environ.get("MOBS", "48000")}\nexecute if entity @e[tag=scaling_mob]\n')
p.write_text(text)
PY
for workers do
  TIMEOUT=${TIMEOUT:-600} "${STRESS_RUNNER:-tools/stress/run.sh}" "$label-w$workers" "$commands" "-Dtickless.workers=$workers"
  python3 tools/stress/check.py "build/stress-$label-w$workers/out.log" 135424
done
set -- $(for workers do printf '%s=build/stress-%s-w%s/out.log\n' "$workers" "$label" "$workers"; done)
python3 tools/stress/bench_scaling.py "$@"
