# Lithium and Paper inspired AI optimizations

These changes adapt three upstream techniques to Tickless's existing source tree
and parallel world workers. They do not install either project or change AI tick
rates.

- **Goal selection:** inspired by [Paper's flag-set optimization](https://github.com/PaperMC/Paper/blob/ff3655a7842b8830c0bedd5ef4e35522aa9a1914/paper-server/patches/features/0019-Optimize-GoalSelector-Goal.Flag-Set-operations.patch).
  Disabled flags use a bit mask, occupied flags use an ordinal-indexed array, and
  flag iteration visits set bits without allocating enum iterators. The public
  mutable `EnumSet` is retained: masks are read from the live set rather than
  cached. Empty flags, registration order, priorities and callback order retain
  the previous behavior, including flags changed inside `canUse()`.
- **Secondary POIs:** follows [Lithium's empty-profession shortcut](https://github.com/CaffeineMC/lithium/blob/38104b18fa0f21d8e04e0599ce3ee2bb0566bd58/common/src/main/java/net/caffeinemc/mods/lithium/mixin/ai/sensor/secondary_poi/SecondaryPoiSensorMixin.java).
  A profession with no secondary POI blocks clears its old memory and returns,
  avoiding all 405 block reads per scan. Farmers still scan the same positions
  in the same order. A scan-local mutable position replaces 405 temporary
  positions; only matches are copied into immutable memory entries.
- **Tempting players:** inspired by [Lithium's single-pass player search](https://github.com/CaffeineMC/lithium/blob/38104b18fa0f21d8e04e0599ce3ee2bb0566bd58/common/src/main/java/net/caffeinemc/mods/lithium/mixin/ai/sensor/replace_streams/tempting/TemptingSensorMixin.java).
  `SnapshotSort.firstByKey` selects the first matching player without collecting
  and sorting candidates. It retains Tickless's stable float-distance ordering,
  including equal distances, signed zeros, infinities and NaNs. Filters retain
  their order and short circuiting; each accepted distance is read once so
  movement on another worker cannot invalidate a comparator.

## Verification

`GoalSelectorCheck` embeds the selector from baseline `d2d53e5` as an independent
reference. It compares every callback and running state over 48,000 deterministic
rounds, covering all 16 flag combinations, equal and maximum priorities,
noninterruptible goals, disabled flags, direct set mutation, callback mutation,
removal, duplicates and re-addition.

`SecondaryPoiSensorCheck` executes the real sensor on isolated fixtures, checking
farmer search bounds/order, no matches, immutable published positions, and zero
block reads with stale-memory removal for every profession with empty secondary
POIs. `SnapshotSortCheck` compares first-match selection to stable sorting,
including rejected candidates and one key read per accepted candidate.

Build and run the focused checks with Java 25:

```sh
./build.sh build -PinstallTo=build/install/mod-opt-candidate
mkdir -p build/mod-opt-checks build/mod-opt-check-run
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" \
  -w /workspace/build/mod-opt-check-run eclipse-temurin:25-jdk sh -c '
  javac -cp "/workspace/build/install/mod-opt-candidate/lib/*" \
    -d /workspace/build/mod-opt-checks /workspace/tools/GoalSelectorCheck.java \
    /workspace/tools/SecondaryPoiSensorCheck.java /workspace/tools/SnapshotSortCheck.java \
    /workspace/tools/GoalSelectorBench.java &&
  for check in GoalSelectorCheck SecondaryPoiSensorCheck SnapshotSortCheck; do
    java -cp "/workspace/build/mod-opt-checks:/workspace/build/install/mod-opt-candidate/lib/*" "$check" || exit
  done'
BUILD=0 SERVER_INSTALL=build/install/mod-opt-candidate STRESS_GC=g1 \
  STRESS_JAVA_OPTS=-Dtickless.workers=16 GRAPH_SEED=20261002 ./test.sh 15
```

Run `GoalSelectorBench` separately against the baseline and candidate installations
with the same JVM. It uses 1,024 selectors with 12 goals each and a fixed mixture
of flags and disabled controls. Every round must have the same callback checksum.
This isolates selector cost; it does not establish a whole-server speedup.

## Results, 2026-10-02

On the Ryzen 9 8940HX host with Temurin 25 in Docker, two sequential comparisons
ran in opposite orders, with no builds or regression servers running during the
measurement. Each JVM ran 12 rounds of 1,024,000 selector ticks; the first six
rounds were discarded and the retained rounds' median is reported.

| Pair | Before ns/selector tick | After ns/selector tick | Speedup |
| --- | ---: | ---: | ---: |
| 1 | 122.475 | 61.965 | 1.98× |
| 2 | 124.890 | 64.000 | 1.95× |

Every round had the same checksum of 4,864,000 goal callbacks. These results
measure this fixed selector mixture, not overall Minecraft throughput. Logs are
`build/mod-opt-goals-{before1,after1,before2,after2}.log` and the retained results
are `build/mod-opt-goals-results.json`. The initial benchmark that overlapped
regression setup is excluded.

Packaged engine SHA-256 values:

```text
before 0f3e1761c3e09d5882e9b69181b2892211b62e12581a1e23debd9aded1f23083
after  fc15310127750ff8e5a417ce9847126c2a83e013c8cd7cc9147fdf4f48036a31
```

The full build, three focused checks, brain layout/construction/memory checks,
scheduled-tick check and concurrent path-cache check passed. The complete
15-second default regression suite passed with 16 workers and G1, including all
five soundness workloads. The basic scenario conserved all 65,536 hopper items;
the transfer graph accounted for every item. Logs are `build/mod-opt-focused.log`,
`build/mod-opt-checks-final.log`, `build/mod-opt-compat.log` and
`build/mod-opt-regress.log`.

Reproduce the selector measurement after building `d2d53e5` into
`build/install/mod-opt-baseline` and preparing the candidate and checks above:

```sh
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" \
  -w /workspace/build/mod-opt-check-run eclipse-temurin:25-jdk sh -c '
  for run in before1 after1 after2 before2; do
    case "$run" in
      before*) install=mod-opt-baseline ;;
      after*) install=mod-opt-candidate ;;
    esac
    java -Xmx1g -cp "/workspace/build/mod-opt-checks:/workspace/build/install/$install/lib/*" \
      GoalSelectorBench > "/workspace/build/mod-opt-goals-$run.log" || exit
  done'
```

## Follow-up: behavior gates and moving pistons

Behavior gates now use indexed child traversal for starting, ticking and stopping,
following [Paper's stream removal](https://github.com/PaperMC/Paper/blob/ff3655a7842b8830c0bedd5ef4e35522aa9a1914/paper-server/patches/features/0011-Remove-streams-from-hot-code.patch)
and [Lithium's gate iteration](https://github.com/CaffeineMC/lithium/blob/38104b18fa0f21d8e04e0599ce3ee2bb0566bd58/common/src/main/java/net/caffeinemc/mods/lithium/mixin/ai/task/replace_streams/GateBehaviorMixin.java).
The gate retains its separate final status scan: a later child can reactivate an
earlier child, or stop one that was running. A running flag collected during
ticking alone misses these changes. `RUN_ONE` still stops at the first successful
start, while `TRY_ALL` tries every eligible child. The existing public stream
overload remains available.

`ShufflingList` exposes indexed data access and reuses its random-order comparator.
Shuffle consumes the same random draws, uses the same weight formula and stable
sort, and retains iteration and stream order.

Moving pistons now memoize their most recent collision shape, inspired by
[Lithium's moving-block shape cache](https://github.com/CaffeineMC/lithium/blob/38104b18fa0f21d8e04e0599ce3ee2bb0566bd58/common/src/main/java/net/caffeinemc/mods/lithium/mixin/block/moving_block_shapes/PistonMovingBlockEntityMixin.java).
Each memo matches the actual base and moving shape identities, direction and
exact float offset. A hit avoids moving and re-optimizing the same voxel geometry.
The memo retains one result per block entity, rather than an unbounded world map.
Dynamic block shapes still query their context and rebuild each time. No-clip is
checked before lookup. A volatile immutable record safely publishes the result;
the `@Unowned` cache write neither acquires a world lock nor requires rollback.
Changed progress, direction or state automatically misses when the derived
geometry inputs differ.

`GateBehaviorCheck` compares callbacks, final child/gate states, brain reads and
entry/exit memories against the gate from `0c45f7d` over 32,000 deterministic
lifecycles. It covers both policies, ordered and seeded shuffled gates, empty
gates, and callbacks that start or stop siblings. A separate 3,200-shuffle check
compares the original weighted formula and draw order, including unusual weights.
`PistonShapeCacheCheck` compares exact boxes and movement clipping with the
original formula over 15,048 cases: all directions, source and payload pistons,
extension/retraction, fractional and out-of-range progress, state changes, and
no-clip. It also checks shulker boxes with a changing world context, cache hits,
input restoration, and 32,000 concurrent queries with thread-local no-clip.

Build and reproduce these checks:

```sh
./build.sh build -PinstallTo=build/install/mod-opt2-candidate
mkdir -p build/mod-opt2-checks build/mod-opt2-check-run
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" \
  -w /workspace/build/mod-opt2-check-run eclipse-temurin:25-jdk sh -c '
  javac -cp "/workspace/build/install/mod-opt2-candidate/lib/*" \
    -d /workspace/build/mod-opt2-checks /workspace/tools/GateBehaviorCheck.java \
    /workspace/tools/PistonShapeCacheCheck.java /workspace/tools/GateBehaviorBench.java \
    /workspace/tools/PistonShapeBench.java &&
  for check in GateBehaviorCheck PistonShapeCacheCheck; do
    java -cp "/workspace/build/mod-opt2-checks:/workspace/build/install/mod-opt2-candidate/lib/*" "$check" || exit
  done'
BUILD=0 SERVER_INSTALL=build/install/mod-opt2-candidate STRESS_GC=g1 \
  STRESS_JAVA_OPTS=-Dtickless.workers=16 GRAPH_SEED=20261003 ./test.sh 15
```

The full build, both focused checks, brain layout/construction checks and complete
15-second default regression suite passed, including all five soundness workloads.
Disassembly of the packaged collision method confirms that the geometry memo
write has no ownership-lock call. Logs are `build/mod-opt2-focused-final.log`,
`build/mod-opt2-regress.log` and `build/mod-opt2-piston-bytecode.txt`.

`GateBehaviorBench` measures 1,024 eight-child gates split between the two running
policies. `PistonShapeBench` advances 192 pistons through their three normal stages
and issues 32 collision queries per stage. Both benchmark programs run unchanged
against baseline `0c45f7d` and the candidate. They report elapsed time, callback or
geometry checksums, and bytes allocated by the benchmark thread. These isolate
the affected operations; they do not establish a whole-server speedup.

### Follow-up measurements, 2026-10-02

Two sequential comparisons per program ran in opposite orders on the Ryzen 9
8940HX host with Temurin 25 in Docker, `-Xmx1g` and G1. No builds or Minecraft
test servers ran during measurement. Each process ran 12 rounds; the table
reports the median of the final six. A round has 1,024,000 gate ticks or 221,184
piston collision queries. Piston timing and allocation include the cold query
after every stage transition, followed by 31 queries at that stage.

| Operation / pair | Before ns/op | After ns/op | Speedup | Before / after bytes/op |
| --- | ---: | ---: | ---: | ---: |
| Gate / 1 | 73.40 | 15.86 | 4.63× | 496.03 / 0.03 |
| Gate / 2 | 73.31 | 15.73 | 4.66× | 496.03 / 0.03 |
| Piston / 1 | 1,944.70 | 88.48 | 21.98× | 4,124.89 / 142.40 |
| Piston / 2 | 1,919.57 | 88.19 | 21.77× | 4,196.89 / 142.40 |

All rounds returned identical checksums: 4,608,000 child callbacks for gates and
231,936.0000 for piston geometry. The small remaining gate allocation comes from
the benchmark's list iteration. These samples measure stable gate traversal and
repeated collision queries, not full AI updates, piston ticking or server TPS.

Raw logs are `build/mod-opt2-{GateBehaviorBench,PistonShapeBench}-{before1,after1,before2,after2}.log`;
rounds and medians are in `build/mod-opt2-benchmark-results.json`. Packaged engine
SHA-256 values:

```text
before fc15310127750ff8e5a417ce9847126c2a83e013c8cd7cc9147fdf4f48036a31
after  85a4c6c734c837fb2cc5b218e56d9848e2384fc9d07474f111d34c8940ab8e60
```

After building `0c45f7d` into `build/install/mod-opt2-baseline` and preparing the
candidate and benchmark classes above:

```sh
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" \
  -w /workspace/build/mod-opt2-check-run eclipse-temurin:25-jdk sh -c '
  for benchmark in GateBehaviorBench PistonShapeBench; do
    for run in before1 after1 after2 before2; do
      case "$run" in
        before*) install=mod-opt2-baseline ;;
        after*) install=mod-opt2-candidate ;;
      esac
      java -Xmx1g -XX:+UseG1GC \
        -cp "/workspace/build/mod-opt2-checks:/workspace/build/install/$install/lib/*" \
        "$benchmark" > "/workspace/build/mod-opt2-$benchmark-$run.log" || exit
    done
  done'
```
