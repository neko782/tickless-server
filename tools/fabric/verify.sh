#!/bin/sh
# Exercise unmodified real mods plus parallel callbacks; also prove two conflict types fail.
set -eu
cd "$(dirname "$0")/../.."
if [ "${BUILD:-1}" = 1 ]; then
  ./build.sh buildFabricProbe
fi
python3 tools/fabric/generate-probe.py build/fabric-probe-commands.txt
SERVER_INSTALL=build/install/fabric STRESS_MODS=build/fabric-test-mods TIMEOUT=100 \
  tools/stress/run.sh fabric-verified build/fabric-probe-commands.txt
python3 - <<'PY'
from pathlib import Path
log = Path('build/stress-fabric-verified/out.log').read_text()
required = ['TICKLESS_PROBE PASS', 'FABRIC_SMOKE_COMPLETE', 'tickless.integration',
            'Successfully enabled.', 'chunky', 'spark', 'Running world events on',
            'Set tickless.integration to true', 'Task finished for minecraft:overworld.',
            'Processed: 9 chunks (100.00%)', 'TPS from last 5s, 10s, 1m, 5m, 15m:']
for marker in required:
    assert marker in log, 'Missing runtime evidence: ' + marker
assert '/ERROR]' not in log and 'Exception in world event' not in log, 'Runtime exception logged'
for line in log.splitlines():
    if any(marker in line for marker in ['TICKLESS_PROBE PASS', 'ownership:', 'Successfully enabled.', 'FABRIC_SMOKE_COMPLETE']): print(line)
print('PASS FabricRealModsAndParallelCallbacks')
PY
printf 'sleep 1\n' > build/fabric-conflict-commands.txt
for kind in optional overwrite; do
  if SERVER_INSTALL=build/install/fabric STRESS_MODS="build/fabric-conflicts/$kind" TIMEOUT=35 \
      tools/stress/run.sh "fabric-conflict-$kind" build/fabric-conflict-commands.txt > "build/fabric-conflict-$kind-harness.log" 2>&1; then
    echo "FAIL FabricConflict-$kind: conflicting mod was accepted" >&2
    exit 1
  fi
  if ! grep -q 'Tickless Fabric mixin conflict' "build/stress-fabric-conflict-$kind/out.log"; then
    cat "build/stress-fabric-conflict-$kind/out.log"
    echo "FAIL FabricConflict-$kind: unrelated startup failure" >&2
    exit 1
  fi
  echo "PASS FabricConflict-$kind (refused with explicit conflict message)"
done
