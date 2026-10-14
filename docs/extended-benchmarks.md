# Extended performance and conservation tests

`tools/stress/extended.py` runs five new workloads sequentially, using a fresh
world and a deterministic seed for each. Set up the world while simulation is
frozen, then warm up for 120 seconds and measure a marked 60-second window.
The default runs use a 6 GB maximum heap and 16 world carriers on this host
(server helpers and background threads also use CPU). `--heap 12g` selects a
larger heap, recorded in each manifest; comparisons reject different heap limits.
Manifests also record the installed engine jar checksum and garbage collector.
G1 is the default; `--gc zgc` selects ZGC. Native ZGC crashes occurred in both
builds during these stress runs, so those runs are excluded from results.

| Workload | Default load | Required audit |
| --- | --- | --- |
| crowd | 48,000 mixed mobs with AI, pushing and targeting | Exact mob population, 1,024 circulating hopper items, all 36,864 floor blocks |
| village | 24,000 villagers, beds and workstations | Exact villager population, circulating items and floor blocks |
| sorting | 23,680 containers in closed two-layer conveyors across chunk boundaries | Exact totals for five item types, including unstackable swords, with observed transfers |
| pistons | 800 clocks moving slime, honey and diamond blocks, 4,000 idle cows, hopper rings | Observed extension and sustained non-entity activity; exact mob, payload, piston and item totals, no drops |
| gravity | 65,536 sand blocks falling after support removal | All sand blocks landed and conserved, no falling entities or item drops |

Mobs have increased health and resistance to prevent deaths during measurement
while retaining AI and targeting in the crowd and village. Only the piston
bystanders use `NoAI` on both engines; they still tick and collide. Pistons run at
20 ticks/s by default. `--piston-rate` requests a faster rate for extra stress;
comparisons require the same rate and reject stopped clocks and failed audits.
Gravity is finite: its score is elapsed time
until a successful completion poll, followed by the full block audit. The other
block workloads record CPU at the normal simulation rate. The comparison also
reports the piston bystanders' committed step rate, capped near 20/s at the
default setting. These metrics cannot
be combined into a single overall server speedup.

Block audits replace matching blocks with air in the frozen, drained world and
count every successful replacement. They use `strict` replacement to skip neighbor
side effects: an ordinary destructive count can itself trigger a piston
retraction that cannot finish while block-entity ticking is frozen. Payload and
piston totals must still match exactly, and drops remain a failure.
After stopping the piston clocks, the harness also polls until every base is a
stationary sticky-piston block before freezing. A base in mid-retraction is stored
inside a moving-piston block entity, so a fixed delay alone cannot establish that
the block-only audit is ready. A base that fails to settle within 30 seconds fails
the run before the audit.

Entity throughput comes from committed updates that advance the entity tick
counter, rather than requested timer firings or the game clock. Only complete
statistic intervals inside the measurement window count. Run summaries record
CPU samples, throughput drift, audit results and startup stalls. The comparison
rejects different configurations, missing results and a requested speedup that
was not reached on each selected entity workload (crowd, village and pistons by default). Use
`--workloads village` for an isolated villager comparison; this does not establish
a gain on the mixed crowd or block workloads. It also rejects throughput drift over
15% when a speedup threshold is requested.

`--repeat BEFORE AFTER` adds another matched pair. The comparison reports the
ratio of mean throughput across the pairs and each individual gain. Every repeat
must use the same configuration and engine checksum on its respective side;
every run must pass the audits and drift check. A mean gain does not imply that
each individual repeat reached that gain.

Run the candidate after building into its own installation:

```sh
./build.sh installDist -PinstallTo=build/install/extended-candidate
python3 tools/stress/extended.py run after \
  --install build/install/extended-candidate --warmup 120 --seconds 60
python3 tools/stress/extended.py compare before after
```

To rebuild the starting engine (`a9068bb`) with just the measurement counters:

```sh
git worktree add --detach build/extended-baseline-src a9068bb
git -C build/extended-baseline-src apply ../../tools/stress/completed-steps.patch
git -C build/extended-baseline-src apply ../../tools/stress/novel-steps.patch
cd build/extended-baseline-src
./build.sh installDist
cd ../..
python3 tools/stress/extended.py run before \
  --install build/extended-baseline-src/build/install/minecraft-server \
  --warmup 120 --seconds 60 --allow-startup-waits --gc g1
```

`--allow-startup-waits` records the starting engine's known visibility scan
stalls during warmup. It does **not** accept a stall during measurement or the
final audit, errors, worker failures, waits outside the resolver graph, failed
polls, missing audit results, unsuccessful exits or conservation failures.
Candidate runs use the normal strict watchdog. The original `check.py` policy
is unchanged.

Logs, manifests, generated commands and validated JSON summaries are under
`build/extended/<label>/` and `build/stress-<label>-<workload>/`. The installed
baseline and candidates used during this change are retained under
`build/install/extended-*`. Timing is based on whole-second log timestamps;
short finite runs have limited precision.

Run focused checks with Java 25, against the packaged, instrumented jar:

```sh
mkdir -p build/checks-extended
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$PWD:/workspace" -w /workspace/build/checks-extended \
  eclipse-temurin:25-jdk sh -c '
    mkdir -p build/checks &&
    javac -cp "/workspace/build/install/extended-candidate/lib/*" -d . \
      /workspace/tools/BrainMemoryCheck.java /workspace/tools/EquipmentObservationCheck.java \
      /workspace/tools/BrainConstructionCheck.java /workspace/tools/BrainLayoutCheck.java \
      /workspace/tools/MemoryPublicationCheck.java /workspace/tools/NearbyTypeCheck.java \
      /workspace/tools/PoiSnapshotCheck.java /workspace/tools/ActiveNavigationCheck.java \
      /workspace/tools/CopperChestObservationCheck.java /workspace/tools/EntitySectionCheck.java \
      /workspace/tools/SectionStorageCheck.java /workspace/tools/PoiChunkCacheCheck.java &&
    for check in BrainMemoryCheck EquipmentObservationCheck BrainConstructionCheck \
      BrainLayoutCheck MemoryPublicationCheck NearbyTypeCheck PoiSnapshotCheck \
      ActiveNavigationCheck CopperChestObservationCheck EntitySectionCheck PoiChunkCacheCheck; do
      java -Xmx2g -XX:+UseG1GC -cp ".:/workspace/build/install/extended-candidate/lib/*" "$check" || exit
    done'
python3 tools/stress/extended_check.py
```

The memory check is a negative control against the saved baseline: its foreign
memory hint blocks on the owner and fails; the candidate completes while that
owner remains locked. Equipment checks compare scalar targeting results with
ordinary owned getters, including multiple modifiers, wrong slots, component
changes, armor coverage, mutable item access and transaction rollback.
`MemoryPublicationCheck`, `NearbyTypeCheck` and `PoiSnapshotCheck` cover coherent
concurrent memory publication, nearest-first type filtering, membership edits and
live POI ticket availability. `BrainConstructionCheck` verifies batched registration,
initial memory values and required behavior cells. `ActiveNavigationCheck` covers
path start/finish/stop, tracking lifetime, navigation replacement and a forced tracking-end race.
`PoiSnapshotCheck` also holds the section monitor while a foreign snapshot reader
must complete. `CopperChestObservationCheck` changes the observed chest during a
yielding block lookup. Both regressions fail on the prior build and pass after
the fixes.
`EntitySectionCheck` also verifies pure-class and exact-class queries against brute force.
The existing clock regression covers freeze/step/rate
changes after avoiding frozen non-player worker requests.

Run the existing conservation, TNT, clock and lock-graph regressions with the
same collector and carrier count:

```sh
BUILD=0 SERVER_INSTALL=build/install/extended-candidate \
  STRESS_GC=g1 STRESS_JAVA_OPTS=-Dtickless.workers=16 \
  GRAPH_SEED=20261008 ./test.sh 15
```

`STRESS_GC` and `STRESS_JAVA_OPTS` configure only the stress harness. The normal
server launcher retains its existing collector default.

The isolated POI query benchmark repeats real range searches against the same
75 records at three heights, checking all 25 in-range records on every round.
It discards the first half of rounds, records both engine checksums and repeats
before/after pairs with the same 2 GB heap and G1. Run it while no other benchmark
or build is running:

```sh
python3 tools/stress/poi_query_bench.py \
  build/install/extended-baseline build/install/extended-candidate \
  --require-speedup 3
```

This gate measures POI lookup speed, not whole-world entity throughput. The world
comparison has its own committed-update gate. Both gates reject incomplete or
incorrect results. The standalone query benchmark is supplementary to the five
world workloads.

## Measured results

Measured on 2026-10-01, AMD Ryzen 9 8940HX (16 cores / 32 hardware threads),
Temurin 25.0.4.1 in Docker. Before is `a9068bb` with only the committed-update
telemetry patch; after is the final optimized workspace installation
`build/install/extended-candidate-v16`. Engine SHA-256 values:

```text
before 57aff69f7f66620a5339411d9167de13c2bf2cecf5ce99970eabdea35b3df973
after  67da76b9326da66d4e8cc7c964e2a13edfad32c097b2874cf260c8ce8236cd2e
```

The full five-workload comparison uses 16 world carriers, a 6 GB heap, G1,
120 seconds of warmup and a 60-second measurement window (gravity completes
when all sand has landed). Builds and profiling ran outside the timing windows.
Every before and after result below passed its item, block and population audits.

| Workload / metric | Before | After | Result |
| --- | ---: | ---: | --- |
| Crowd: committed updates per mob per second | 9.00 | 9.90 | 1.10× throughput |
| Village: committed updates per villager per second | 3.56 | 7.31 | 2.06× throughput |
| Sorting: CPU cores | 0.73 | 0.76 | About 4% more CPU |
| Pistons at 20 Hz: CPU cores | 1.87 | 1.71 | About 9% less CPU; both sustain approximately 20 updates/s |
| Gravity: elapsed seconds | 4 | 3 | Whole-second precision limits conclusions for this short run |

Crowd throughput drift was +3.30% before and +0.86% after; village drift was
−4.02% and +0.99%. Crowd CPU use was 18.21 / 18.51 cores before/after;
village CPU use was 17.67 / 19.39 cores. The gains do not establish a 3×
improvement at 16 carriers or across all workloads.

Validated results are in `build/stress-ext-g1-base-*` for the before suite,
`build/stress-ext-v16-final-{crowd,village,sorting}` and
`build/stress-ext-v16-block-final-{pistons,gravity}` for after. The piston before
result was rerun as `build/stress-ext-v16-block-base-pistons` with the same
side-effect-free audit. The initial after piston audit reported 797 bases;
its saved world contained the other three as moving-piston block entities with
sticky-piston states. The strict audit rerun conserved all 800 bases and all
payloads. The failed audit remains excluded and retained under
`build/stress-ext-v16-final-pistons`.

Three fresh, sequential matched village pairs used the same configuration with
**one world carrier**. Server helpers and GC also consumed CPU. Every run passed
all audits, had zero worker failures or startup lock stalls, and stayed within
the predefined 15% drift limit.

| Pair | Before updates/villager/s | After updates/villager/s | Gain | Before / after drift |
| --- | ---: | ---: | ---: | --- |
| 1 | 0.387806 | 1.125927 | 2.903× | −4.40% / −3.32% |
| 2 | 0.387578 | 1.194271 | 3.081× | −5.41% / −1.80% |
| 3 | 0.385064 | 1.175968 | 3.054× | +2.87% / −10.13% |
| Mean | 0.386816 | 1.165389 | **3.013×** | All within limit |

The **3× mean-throughput gate passed**. One individual pair was below 3×;
the observed range was 2.90–3.08×. These are three samples, not confidence
intervals or a claim about every carrier configuration. Mean CPU use fell
from 2.44 to 1.69 cores. Logs and manifests are retained under
`build/stress-ext-v16-p{1,2,3}-{base,final}-village`.

Reproduce the repeated comparison after preparing the baseline installation:

```sh
for i in 1 2 3; do
  python3 tools/stress/extended.py run "village-r${i}-before" village \
    --install build/install/extended-baseline --workers 1 --gc g1 \
    --warmup 120 --seconds 60 --allow-startup-waits
  python3 tools/stress/extended.py run "village-r${i}-after" village \
    --install build/install/extended-candidate --workers 1 --gc g1 \
    --warmup 120 --seconds 60
done
python3 tools/stress/extended.py compare village-r1-before village-r1-after \
  --repeat village-r2-before village-r2-after \
  --repeat village-r3-before village-r3-after \
  --workloads village --require-speedup 3
```

The supplementary POI benchmark also passed its 3× gate in **both repeats**:

| Repeat | Before ns/query | After ns/query | Gain |
| --- | ---: | ---: | ---: |
| 1 | 9,972.10 | 752.75 | 13.25× |
| 2 | 10,156.50 | 746.30 | 13.61× |

Each repeat used 16 rounds of 100,000 queries, discarded the first eight
rounds, and took the median of the retained rounds. Every populated query round
returned checksum 2,500,000; empty rounds returned zero. Retained samples and
engine hashes are in `build/extended/poi-query-v16/results.json`; raw logs are
in the same directory.
This measures query cost, separately from the world throughput above.

The final build also passed the four existing 15-second regression scenarios
(basic item loops, misc/TNT, clock changes and item-transfer lock graph),
join/rejoin tracker checks, and the focused ownership, rollback, memory,
type-filtering, POI, navigation and monitor-cycle checks. The item-transfer graph
accounted for all 186,634 items across 2,417 holders. The equipment check retains
owned mutable item access and verifies rollback; POI ticket claims remain
exclusive. The two new observation/monitor regression controls fail on the prior
build and pass on the final one. Logs are `build/extended/regress-v16.log`,
`focused-v16.log`, `observation-v16.log` and `tracker-v16.log`.

## Novel workloads

Five more workloads cover paths the first five do not reach. They run on the same harness:
fresh world, setup while frozen, warmup, measured window, then destructive audits on the
frozen world. Each is a closed glass pen around the same spawn area; every mob is persistent
with raised health and resistance, keeps its AI, and is tagged so the audit can count it.

| Workload | Default load | What it exercises | Required audit |
| --- | --- | --- | --- |
| bees | 60,000 bees, 361 hives (1,083 slots), 1,482 flowers under a glass roof | flying navigation, flower and hive searches, bees entering and leaving hives (entity ↔ block entity), flap game events | Bees as entities plus hive occupants equals the population; all hives present |
| endermen | 64,000 endermen at night, 961 loose dirt blocks, `mob_griefing` on | endermen taking and placing blocks (block ↔ entity), tall-mob collision | Dirt in the world plus dirt dropped by killing every enderman equals 961 |
| snowballs | 24,000 snow golems and 24,000 zombies at night | ranged attack goals, thousands of short-lived projectiles, hit detection, retaliation | Exact tagged population, no items |
| jockeys | 16,000 four-high passenger towers (64,000 mobs: cows, pigs, sheep, spiders, chickens, zombies, skeletons, villagers) | vehicles moving their passengers, pushing between towers | Exact population and exactly 48,000 riders |
| aquarium | 64,000 cod, salmon, tropical fish, squid and glow squid in a sealed 158×158×6 tank | swimming navigation, schooling, fluid pushing on every fish | Exact population and all 149,784 water source blocks |

Every workload also audits the stone floor and that no item entities escaped. Endermen do not
expose their carried block in entity NBT (`/data get` shows nothing even for one summoned with
`carriedBlockState`, on vanilla too), so the endermen audit kills them and counts the dirt they
drop, after checking that no items existed before.

The throughput metric is committed entity updates per mob per second, as for crowd and
village. The step counter now counts a vehicle's step once per entity in its stack and ignores
a passenger's own event. Before, a tower's step counted once, plus each passenger event that
happened to observe the vehicle's update. The baseline below carries the same counter
(`tools/stress/novel-steps.patch`). The packaged scheduler identifies this metric as
`committed-entities-and-passengers-v2`; manifests record that version, and comparisons reject
different versions. Jockey runs and comparisons reject missing or legacy versions before
reporting a result. Snowball steps
are included in the snowballs rate, so that workload's rate is not capped at exactly 20.

Run them by name; `run` and `compare` without names keep the original five:

```sh
git worktree add --detach build/novel-baseline-source ae435af
git -C build/novel-baseline-source apply ../../tools/stress/novel-steps.patch
(cd build/novel-baseline-source && ./build.sh installDist)
./build.sh build -PinstallTo=build/install/novel-candidate
python3 tools/stress/extended.py run novel-before bees endermen snowballs jockeys aquarium \
  --install build/novel-baseline-source/build/install/minecraft-server --warmup 60 --seconds 40
python3 tools/stress/extended.py run novel-after bees endermen snowballs jockeys aquarium \
  --install build/install/novel-candidate --warmup 60 --seconds 40
python3 tools/stress/extended.py compare novel-before novel-after \
  --workloads bees endermen snowballs jockeys aquarium
```

### What the profiles found

JFR profiles of saturated baseline runs (30 s, entity-event samples only) pointed at these.
Each change is a race a player cannot see, or a bug fix; `docs/concurrency.md` lists them under
"Deliberate races".

| Workload | Baseline hotspot | Change |
| --- | --- | --- |
| bees | 46% of bee updates posting flap game events: every post created or looked up a listener registry for each section in range under the chunk's latch | Registries are created only when a listener registers; posting reads them without a latch |
| bees, all | ~15% in the shared set of monitor holders: every outermost `synchronized` added and removed the transaction | A transaction joins on its first monitor and leaves when its event ends |
| jockeys | Three of four entity events were passengers locking their whole tower, then returning because the vehicle moves them | A passenger's event checks its vehicle without a lock and returns before starting a transaction |
| jockeys, crowds | 7% looking up scoreboard teams per push candidate | Team rules are skipped while nobody is on a team |
| aquarium, bees | 12–14% in synced data writes that rewrite the same air supply, flags and frozen ticks every step, each locking the owner first | Unchanged writes return before locking or logging |
| snowballs, bees | 7–8% rebuilding entity box caches: snowballs and flying bees change section membership many times per step, and every change forced a rebuild | The cache is built once per step; join stamps keep membership exact (departed entities are dropped, newcomers are a live-scanned tail) |
| bees | 6% writing the shared 4,096-entry path-type cache, which flying bees kept evicting; two arrays could also pair a position with another's type | 65,536 one-long entries holding the position hash and type together |
| all crowds | 3% in a `System.nanoTime()` age check on every box-cache query | The box clock advances with world maintenance while frozen, so the cache needs no age check |
| endermen | 8% in `ClassInstanceMultiMap.group`: long-range class queries (endermen look for endermites 64 blocks out) checked `isAssignableFrom` for every section before the map lookup | The check runs only on a miss |

Writing the endermen workload also turned up a conservation race in the code. Entity updates
read blocks without locks, so two endermen could both see the same dirt block and both take it,
or overwrite each other's placement. No baseline run failed the dirt audit, so the window is
narrow, but a duplicated block is visible; endermen now lock the block and read it again first.

The packed path cache uses opaque `VarHandle` reads and writes to guarantee atomic long entries.
`PathTypeCacheCheck` verifies repeated hits, invalidation, colliding slots and 800,000 concurrent
queries with distinct expected types. `EntitySectionCheck` covers same-step joins, departures,
rejoins, moves between sections, class proofs and concurrent membership churn. The extended
harness controls reject a failed or omitted audit on each novel workload and incompatible
passenger counter versions.


### Verified novel results

Measured on 2026-10-01 on the AMD Ryzen 9 8940HX (16 cores / 32 threads),
Temurin 25.0.4.1 in Docker. Before is `ae435af` with only `novel-steps.patch`;
after includes the optimizations above and the soundness fixes from `15174fb`.
Both installations identify the counter as `committed-entities-and-passengers-v2`.
Engine SHA-256 values:

```text
before 21bd9f5736a28f29aa5a5cdcd2110bf4e654657efcdfe4c6f5b93b3ef5545f84
after  625c1feef69e45aacd7f1f9bcd119daecbe3c33a32c1da82d15c414421d160a7
```

Each row is one sequential before/after pair at full default population, with
16 world carriers, G1, a 6 GB heap, 60 seconds of warmup and a 40-second window.
No builds, profiles or other test servers ran in the measurement windows. Every
run passed its population, floor, item and workload-specific audits, with zero
worker failures or startup lock stalls. The drift column is last-third throughput
relative to first-third throughput; all eight runs stayed within 15%.

| Workload | Before steps/mob/s | After steps/mob/s | Gain | CPU cores before / after | Drift before / after |
| --- | ---: | ---: | ---: | ---: | ---: |
| jockeys | 10.39 | 12.80 | 1.23× | 17.80 / 17.65 | +10.78% / +1.05% |
| snowballs | 8.89 | 11.91 | 1.34× | 17.69 / 18.16 | +13.05% / +2.43% |
| aquarium | 15.01 | 16.93 | 1.13× | 17.81 / 17.93 | +3.19% / +4.59% |
| endermen | 13.30 | 15.34 | 1.15× | 17.55 / 17.62 | +0.37% / +0.24% |

These are single pairs, not confidence intervals or an overall server speedup.
The passenger result uses the corrected counter on both engines. Earlier
`fin-r1-*` jockey results used incompatible counters and are excluded; the
comparison now rejects them. No 3× improvement is established by this suite.
Logs and validated JSON are under `build/stress-novel-verified-{before,after}-*`;
commands and manifests are in `build/extended/novel-verified-{before,after}`.
The comparison is retained in `build/novel-verified-comparison.md`.

The final bee workload conserved all 60,000 bees (entities plus hive residents),
361 hives and 35,382 stone floor blocks, with no item drops or worker failures.
It used 120 seconds of warmup and a 60-second window; mean throughput was
1.595 updates/mob/s at 18.74 CPU cores, with **−70.97% drift**. This is an audit
pass, not a stable performance result. The matching baseline crashed before
measurement with native `SIGSEGV` in G1's
`G1ParScanThreadState::trim_queue_to_threshold`, as did two earlier saved-baseline
attempts. There is no valid bee speedup comparison. The final candidate and
baseline logs are `build/novel-verified-bees{,-baseline}.log`; the native report
is `build/stress-novel-verified-before-bees/hs_err_pid1.log`.

Validation also passed the 22 focused Java checks and the full 15-second default
regression suite (G1, 16 workers, graph seed `20261001`), including all five
soundness workloads. The graph conserved all 190,558 items across 2,391 holders;
clock changes were 0, 1, 3, 0, 30. Those runs used `novel-final`
(`3614dee47c1b7e03b0900bc994f77d057b785f02de231b5932fddee41de8b67c`);
`novel-verified` then added the metric marker. Disassembly confirmed identical
method instructions and referenced members; the packaged differences are the
new constant field and debug metadata. Logs are `build/novel-final-{focused,regress}.log`.

The final installation passed current position, rotation and metadata checks on
join/rejoin (`build/novel-tracker-observed.log`) and all five original extended
workloads at scale 0.02, with 10-second warmup and a 25-second window
(`build/novel-compat.log`, `build/novel-compat-settled.log`). The first piston
attempt counted 15 of 16 bases; its saved world retained the last base inside a
moving-piston block entity. That failed result stays excluded. Polling for
stationary bases before freezing made the subsequent conservation audit pass.
The Python conservation, poll, timing and metric-version controls also passed.
The corrected full-size piston audit then conserved all 800 bases, 800 payload
blocks, 4,000 cows and 1,024 hopper items, with zero worker failures or stalls.
Its non-entity activity lower estimate was 37,095 events/s against the required
9,600; logs are `build/novel-pistons-full.log` and
`build/stress-novel-pistons-full-pistons/`.

To reproduce the four matched comparisons after preparing the baseline above:

```sh
for workload in jockeys snowballs aquarium endermen; do
  python3 tools/stress/extended.py run novel-before "$workload" \
    --install build/novel-baseline-source/build/install/minecraft-server \
    --warmup 60 --seconds 40
  python3 tools/stress/extended.py run novel-after "$workload" \
    --install build/install/novel-candidate --warmup 60 --seconds 40
done
python3 tools/stress/extended.py compare novel-before novel-after \
  --workloads jockeys snowballs aquarium endermen
python3 tools/stress/extended.py run novel-bees bees \
  --install build/install/novel-candidate --warmup 120 --seconds 60
```
