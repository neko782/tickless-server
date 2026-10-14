# Benchmarks: world workers vs vanilla

The later five-workload optimization suite, including committed-update throughput,
repeated comparisons and item/block conservation audits, is recorded in
[Extended performance and conservation tests](extended-benchmarks.md).

One sample per configuration, taken 2026-09-29 on an AMD Ryzen 9 8940HX
(32 hardware threads), Temurin 25 in Docker, with 31 world-worker carriers.
Vanilla is the same source tree before the world-worker changes (commit
`4d398a1`), built and run the same way.

## Scenario

`tools/stress/gen_world.py <out> <seconds> <mobs> 4`, 12×12 force-loaded chunks at y=200:

- 529 closed hopper rings holding 135,424 items (the total must not change)
- 552 observer clocks driving sticky pistons (scheduled ticks and block events)
- a wheat farm with `random_tick_speed 100`
- a glass pen with the given number of mixed mobs (cows, sheep, pigs, villagers, zombies, chickens)

Numbers are from the steady part of the run, after setup.

- **Server thread ms/tick** is `/tick query`'s average.
- **Step rate** is how many of the requested per-chunk and per-entity steps per
  second actually ran: 20.00 means nothing fell behind. It is the tickless
  counterpart of TPS.

## Throughput

| Mobs | Build | Server thread ms/tick (avg / P50 / P95 / P99) | Simulation rate | World events/s | Items conserved |
| ---: | --- | --- | --- | ---: | --- |
| 3,000 | vanilla | 41.0 / 40.8 / 44.3 / 48.2 | 20 TPS | – | yes |
| 3,000 | tickless | 2.4 / 2.3 / 3.5 / 6.8 | 20.00 steps/s (chunks and entities) | ~150,000 | yes |
| 8,000 | vanilla | 152.4 / 149.9 / 168.7 / 205.0 | ~6.6 TPS, 31 s (623 ticks) behind | – | yes |
| 8,000 | tickless | 5.7 / 5.4 / 6.9 / 13.0 | 20.00 steps/s | ~250,000 | yes |
| 16,000 | vanilla | not run | – | – | – |
| 16,000 | tickless (virtual threads) | 11.8 / 11.4 / 15.1 / 16.1 | 20.00 steps/s | ~410,000 | yes |
| 16,000 | tickless (platform threads) | ~13.6 (last stats line) | 20.00 chunk / 19.97 entity steps/s | ~410,000 | not checked¹ |

¹ The platform-thread run was stopped early by the harness on an entity-sort
race that has since been fixed, so it has no final item count or `/tick query`.

At 8,000 mobs vanilla runs the whole world at about a third of normal speed.
Tickless keeps every chunk and entity at full rate while the server thread,
which now only advances the clock and dispatches work, stays near 6 ms per tick.

## Fabric + Lithium comparison

Four paired runs on the same host, 2026-09-29. The comparison server used
Minecraft 26.3, Fabric Loader 0.19.5, and Lithium 0.26.2. Each run used a fresh
world with the same seed and server properties, the same generated commands,
and 30 seconds of load after setup. Tickless used 31 world-worker carriers.
These are single samples, so the table is a workload result rather than a
general Minecraft performance claim.

| Mobs | Server | Server thread avg / P50 / P95 / P99 | Sustained simulation rate | Approx. CPU cores¹ |
| ---: | --- | --- | --- | ---: |
| 3,000 | Lithium | 29.8 / 29.2 / 34.3 / 47.6 ms | 20 TPS | 0.9 |
| 3,000 | tickless | 3.3 / 3.1 / 4.8 / 7.9 ms | 20.00 chunk and entity steps/s | 3.1 |
| 8,000 | Lithium | 122.3 / 122.0 / 138.3 / 142.3 ms | ~8.2 TPS² | 1.4 |
| 8,000 | tickless | 7.5 / 7.0 / 9.2 / 16.1 ms | 20.00 chunk and entity steps/s | 8.0 |

¹ Mean of Docker CPU samples in the 15 seconds before `/tick query`, with 100%
equal to one core. Tickless uses more total CPU to keep the larger world at full
simulation rate; server-thread time alone does not describe its total cost.
² Estimated as 1,000 / 122.3 ms. Lithium also logged that it was 264 ticks
behind near the end of the 8,000-mob run.

All four runs finished with all 135,424 hopper items intact, no logged errors,
and the expected entity-presence checks passing. The tickless worker statistics
reported zero failures. The checks do not prove identical world behavior.

To reproduce the paired runs:

```sh
./build.sh installDist
./tools/stress/setup_lithium.sh
python3 tools/stress/gen_world.py build/lithium-3k.txt 30 3000 4
python3 tools/stress/gen_world.py build/lithium-8k.txt 30 8000 4
SERVER_INSTALL=build/install/lithium TIMEOUT=360 tools/stress/run.sh lithium3k build/lithium-3k.txt
TIMEOUT=360 tools/stress/run.sh tickless3k build/lithium-3k.txt
SERVER_INSTALL=build/install/lithium TIMEOUT=360 tools/stress/run.sh lithium8k build/lithium-8k.txt
TIMEOUT=360 tools/stress/run.sh tickless8k_lithiumcmp build/lithium-8k.txt
python3 tools/stress/check.py build/stress-lithium8k/out.log 135424
```

The setup script pins the [Fabric server launcher](https://fabricmc.net/use/?page=server)
and [Lithium 0.26.2](https://modrinth.com/mod/lithium/version/xS0Q8LSi)
downloads by SHA-256. Run logs and CPU samples are under `build/stress-*/`.

### 24,000-mob saturation run

The same 529 hopper rings, 552 observer clocks, and wheat farm, with 24,000
mixed mobs in the pen. A fresh world was used for each server. All three used
the same script and a 30-second timed window after the setup commands. Vanilla
used the official Mojang 26.3 server bundle; Lithium and tickless used the
versions above. The machine has 32 hardware threads.

| Server | Simulation rate during timed window | Approx. CPU cores¹ | Hopper items / errors |
| --- | ---: | ---: | --- |
| Official vanilla | 25 ticks / 30 s = 0.83 TPS | 1.4 | 135,424 / 0 |
| Fabric + Lithium | 40 ticks / 30 s = 1.33 TPS | 1.5 | 135,424 / 0 |
| Tickless | ~12.9 entity steps/s; ~12.7 chunk steps/s² | 29.4 | 135,424 / 0 |

On entity simulation rate, tickless was approximately **15.5× vanilla** and
**9.7× Lithium** in this run. It used nearly all available CPU threads but
could not keep the 24,000-mob world at the full 20 steps/s. An earlier tickless
run of the same 30-second workload ended at about 14.7 steps/s, showing some
run-to-run variation. These are single samples per server, not confidence
intervals.

¹ Docker CPU percentages averaged over the timed window after its first five
seconds, with 100% equal to one core.
² Mean of six worker-stat windows overlapping the timed window. Tickless's game
clock still advances at 20 ticks/s here, so its game-time difference would
overstate completed world simulation. Vanilla and Lithium rates come from
`/time query gametime` at the start and end. `/tick query`'s 100-tick sample
includes setup at this low TPS, so its average tick time is not used for the
speedup calculation.

```sh
./build.sh installDist
./tools/stress/setup_lithium.sh
./tools/stress/setup_vanilla.sh
python3 tools/stress/gen_world.py build/stress-24k-30.txt 30 24000 4
python3 - <<'PY'
from pathlib import Path
p = Path('build/stress-24k-30.txt')
s = p.read_text()
s = s.replace('sleep 30\n', 'say BENCH_START\ntime query gametime\nsleep 30\nsay BENCH_END\ntime query gametime\n')
Path('build/stress-24k-marked.txt').write_text(s)
PY
TIMEOUT=1200 tools/stress/run.sh tickless24k_marked build/stress-24k-marked.txt
SERVER_INSTALL=build/install/lithium TIMEOUT=1200 tools/stress/run.sh lithium24k build/stress-24k-marked.txt
SERVER_INSTALL=build/install/vanilla TIMEOUT=1200 tools/stress/run.sh vanilla24k build/stress-24k-marked.txt
```

All three runs also passed `tools/stress/check.py` with 135,424 expected items.

## Profile-driven fixes (24,000 mobs)

Same 24,000-mob workload as above, 25-second window, single samples. A JFR profile of the
saturated run showed where worker CPU went:

- 58% in entity lookups by box: movement collision asked every overlapping mob whether it can
  be collided with, although only boats, shulkers and happy ghasts ever can. Entity sections now
  keep a separate list of those (`EntityAccess.mayBeCollidedWith`), as Lithium does.
- `ConcurrentHashMap<Long, ...>` for chunk visibility, load status and POI sections: boxing on
  every lookup, and `Long.hashCode` folds x and z together, so bins became trees whose lookups
  reflect on the key class. Replaced with `ConcurrentLong2ObjectMap` (fastutil + optimistic
  `StampedLock`).
- A method reference allocated on every ticking-range check, biome temperature caches rebuilt
  on every virtual thread, and copy-on-write entity lists iterated through wrapper iterators.
- The server thread spent most of its 40 ms in `ServerEntity.sendChanges` for every tracked
  entity; that now runs on helper threads while the server thread waits.

| Build | Entity / chunk steps/s | Server thread ms/tick |
| --- | --- | --- |
| before | 14.62 / 12.36 | 39.6 |
| after | 19.66 / 19.93 | 17.4 |

Items were conserved (135,424) and no errors were logged. Against vanilla's 0.83 TPS on this
workload that is about 24× (was about 17.6×).

### Scaling before the fixes

`-Dtickless.workers=N`, same workload:

| Workers | Entity steps/s | Per worker |
| ---: | ---: | ---: |
| 4 | 3.03 | 0.76 |
| 8 | 6.50 | 0.81 |
| 16 | 11.44 | 0.71 |
| 31 | 14.62 | 0.47 |

The machine has 16 physical cores with SMT. Scaling is nearly linear to 16 workers; the next 15
hardware threads are SMT siblings and add about 28%. A 29× speedup on 29 busy hardware threads
is not reachable here; per-core overhead against vanilla (0.83 per core) was about 10-25%.

## Lag machines

`tools/stress/gen_lag.py`: piston-gated water and lava streams, dropped items that cannot merge
or despawn, minecart piles on single rails, a redstone dust field over redstone lamps, and
smelting furnaces. Scale 3: 18,000 items, 66 piles of 180 carts, 9,000 furnaces, 156 fluid
gates, 121 redstone clocks; 30-second window.

| Server | Simulation rate | Approx. CPU cores |
| --- | --- | ---: |
| Official vanilla | 64 ticks / 30 s = 2.1 TPS | ~3.4 |
| Tickless | 20.00 entity and chunk steps/s | ~19 |

At scale 1 vanilla ran at 16.3 TPS and tickless at 20.00 steps/s. All checks (items and carts
kept, fluids flowing) passed on both.

```sh
python3 tools/stress/gen_lag.py build/stress-lag3.txt 30 3
TIMEOUT=400 tools/stress/run.sh lag3 build/stress-lag3.txt
SERVER_INSTALL=build/install/vanilla TIMEOUT=400 tools/stress/run.sh lag3vanilla build/stress-lag3.txt
python3 tools/stress/check.py build/stress-lag3/out.log 0
```

## Sleeping block entities

The `idle` zone of `gen_lag.py`, scale 4: 16,000 block entities with nothing to do. A quarter
each are furnaces with input but no fuel, fuel but no input, or a full output slot, and the
last quarter are hoppers holding items, locked by redstone. 30-second window, Temurin 27,
2026-10-14, single samples.

| Sleeping block entities | World events/s | Server CPU (one core = 100%) |
| --- | ---: | ---: |
| off (`-Dtickless.sleepingBlockEntities=false`) | 321,064 | 57% |
| on (default) | 1,075 | 14% |

Both kept 20 steps/s. After the window the script gives one sleeper of each kind work (coal,
raw iron, an emptied output slot, an unlocked hopper with a chest under it). Each one restarted
within 3 s, and an untouched furnace stayed unlit.

Busy block entities pay no measurable cost for the wake bookkeeping. 9,000 smelting furnaces
(`furnaces` zone, scale 3) used 36% and 52% CPU with sleeping off and 42% and 38% with it on,
at 181,000 events/s and 20 steps/s either way. On the saturated scale-3 lag machine, sleeping
off gave 18.5, 14.0 and 17.0 chunk steps/s and sleeping on gave 15.8, 14.1 and 14.0. Identical
runs on this machine vary by about ±2.5, so those samples cannot tell the two apart.

```sh
python3 tools/stress/gen_lag.py build/idle4.txt 30 4 idle
TIMEOUT=400 tools/stress/run.sh idle-on build/idle4.txt -Dtickless.statsIntervalSeconds=5
TIMEOUT=400 tools/stress/run.sh idle-off build/idle4.txt "-Dtickless.sleepingBlockEntities=false -Dtickless.statsIntervalSeconds=5"
python3 tools/stress/bench_heavy.py build/stress-idle-on
```

## Heavy non-mob workloads

`tools/stress/heavy.sh <label> [workload...]` runs `gen_heavy.py` workloads; vanilla is the
official 26.3 server (`SERVER_INSTALL=build/install/vanilla`). Single samples.

| Workload | Vanilla | Tickless |
| --- | --- | --- |
| TNT: 32×32×32 cube chain reaction | 80 s, clock at ~3.6 ticks/s | 12 s |
| Sand: 160×160×4 sheet falls at once (102,400 blocks) | 38 s | 5–6 s |
| Chunkgen: 1,024 new chunks force-loaded | 6 s | 4 s |
| Light: 1,200 piston clocks moving glowstone, 30 s | 20 TPS at ~44% CPU | 20 steps/s at ~95% CPU |

On the light workload tickless first used 164% CPU: a virtual thread per moving-piston tick
and a dispatcher task per light check woke carriers tens of thousands of times per second.
Batching block entities per phase-wheel slot and collecting light checks brought it to ~95%.

## Profile-driven fixes (32,000 mobs)

Same world as above with 32,000 mobs, steady window:

| Build | World events/s | Entity / chunk steps/s |
| --- | ---: | --- |
| before | ~460,000 | ~12.7 / ~16.5 |
| after | ~665,000 | ~17.8 / ~18.7 |

JFR showed 38% of worker time in `LivingEntity.pushEntities` scanning every entity of a crowded
section for each mob; see "Deliberate races" in `concurrency.md` for the box cache and the
other shortcuts.

## Spatial cache and lazy collision contexts (32,000 mobs)

Two sequential before/after pairs on the same host, 2026-09-29. The baseline is
`8c2feee`. Each run creates a fresh world with the 529 hopper rings, piston
clocks, farm and 32,000 mixed mobs above. After 15 seconds of warmup, a marked
45-second window is measured without JFR. Only complete five-second worker-stat
windows are included (eight per run).

| Pair | Build | Entity steps/s | Chunk steps/s | World events/s | Approx. CPU cores |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | before | 14.53 | 17.72 | 566,650 | 26.89 |
| 1 | after | 18.26 | 18.54 | 682,925 | 28.09 |
| 2 | before | 16.31 | 18.68 | 621,225 | 29.26 |
| 2 | after | 19.27 | 18.82 | 711,181 | 28.80 |

Across both pairs, mean entity simulation improves from **15.42 to 18.76
steps/s (+22%)**, and world-event throughput from **593,938 to 697,053/s
(+17%)**. Mean CPU use changes from 28.08 to 28.45 cores (about +1%). The
individual entity-rate gains are 26% and 18%; two pairs are not a confidence
interval or a general performance guarantee. All four runs preserved 135,424
hopper items and passed the entity-presence checks, with no logged errors or
worker failures. The world still falls short of 20 entity steps/s.

A separate JFR profile found concurrent readers rebuilding and sorting the
same entity-section cache. Changes:

- Index cached boxes in two-block horizontal cells instead of sorting along
  x. Small queries inspect neighbors along both axes, and rebuilds are linear.
- Allow only one cache builder per section and publish immutable arrays.
  Readers use the previous snapshot during a rebuild if membership matches;
  otherwise they query the live membership snapshot. Small sections and
  unusually spread-out boxes fall back to live scans. The existing allowance
  for stale boxes remains; every candidate is checked against its live box.
- Resolve held items and descending state on first use in entity collision
  contexts, following [Lithium's lazy shape-context technique](https://github.com/CaffeineMC/lithium/blob/develop/lithium-fabric-mixin-config.md).
  Explicit placement and empty contexts retain their supplied values.

`EntitySectionCheck` compares cached queries with brute-force results, covering
typed queries, wide boxes, cell boundaries, negative and extreme coordinates,
movement followed by a clock advance, membership changes, early aborts,
fallbacks, and concurrent rebuilds. `EntitySectionBench` measures a stationary
900-entity section, with one simulated clock advance per 900 queries and a
result checksum, so query speed can be measured separately from server load.
An isolated final run measured a median **155.5 ns/query before and 99.5
ns/query after (1.56× faster)** across the last five rounds, with identical
checksums. This microbenchmark uses stationary boxes and one querying thread;
it does not measure concurrent rebuilding or moving entities. The full build,
focused entity-section check, scheduled-tick check, and 20-second basic, misc,
and clock regression scenarios all passed.

Reproduction (the baseline installation was saved before editing; rebuild
`8c2feee` in a separate checkout to recreate it):

```sh
./build.sh installDist
python3 tools/stress/gen_world.py build/opt-next-32k.txt 45 32000 4
python3 - <<'PY'
from pathlib import Path
s = Path('build/opt-next-32k.txt').read_text()
s = s.replace('sleep 45\n', 'sleep 15\nsay BENCH_START\nsleep 45\nsay BENCH_END\n')
Path('build/opt-next-marked.txt').write_text(s)
PY
SERVER_INSTALL=build/install/opt-next-baseline TIMEOUT=600 tools/stress/run.sh opt-next-before2 build/opt-next-marked.txt
TIMEOUT=600 tools/stress/run.sh opt-next-after2 build/opt-next-marked.txt
SERVER_INSTALL=build/install/opt-next-baseline TIMEOUT=600 tools/stress/run.sh opt-next-before3 build/opt-next-marked.txt
TIMEOUT=600 tools/stress/run.sh opt-next-after3 build/opt-next-marked.txt
python3 tools/stress/bench_entities.py build/stress-opt-next-{before2,after2,before3,after3}/out.log
```

Run the focused check and benchmark with Java 25:

```sh
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" -w /workspace eclipse-temurin:25-jdk sh -c '
  mkdir -p build/checks &&
  javac -cp "build/install/minecraft-server/lib/*" -d build/checks tools/EntitySectionCheck.java tools/EntitySectionBench.java &&
  java -cp "build/checks:build/install/minecraft-server/lib/*" EntitySectionCheck &&
  java -cp "build/checks:build/install/opt-next-baseline/lib/*" EntitySectionBench &&
  java -cp "build/checks:build/install/minecraft-server/lib/*" EntitySectionBench'
```

## Cache expiry and finer cells (48,000 mobs)

A new saturation workload uses the same world with 48,000 mixed mobs, 15 seconds
of warmup and a marked 45-second measurement. Baseline: `897dd9e`, saved in
`build/install/opt-scale-baseline`. A 35-second steady-load JFR recording found
about 9% of worker execution samples at the spatial-query loop and 4% at cache
construction. The experimental changes use one-block horizontal cells and
expire caches from their build time instead of combining game time with an
independently changing wall-clock phase. The latter avoids redundant rebuilds
within a simulation step while keeping the frozen-clock age bound at about
67 ms.

One clean sequential pair, without JFR or a concurrent build:

| Build | Entity steps/s | Chunk steps/s | World events/s |
| --- | ---: | ---: | ---: |
| baseline (`opt-scale-before2`) | 8.44 | 16.63 | 500,762 |
| experiment (`opt-scale-after2`) | 8.84 | 16.63 | 515,328 |

That is a 4.7% entity-rate gain and a 2.9% event-throughput gain. Eight complete
five-second windows were included per run. Both conserved 135,424 hopper items,
passed entity-presence checks, and logged no errors or worker failures. An
earlier pair measured 7.08 versus 8.18 steps/s, but a build overlapped part of
the baseline measurement, so that pair is excluded. One clean pair does not
establish a reliable gain or isolate the contribution of either change.

`EntitySectionCheck` now also tests movement and cache expiry with a frozen
game clock. The existing query and concurrent-rebuild checks pass.

The next verification is a saturated worker-count sweep. The reusable runner
creates a fresh world per worker count and checks each result before comparing
rates:

```sh
./build.sh installDist
BENCH_SECONDS=45 WARMUP=30 sh tools/stress/scaling.sh cache-scaling 1 4 8 16 31
```

`bench_scaling.py 1=path/to/out.log 4=path/to/out.log ...` reports speedup
relative to the measured one-worker rate and flags runs at the 20-step cap.
Its linear-efficiency column compares against N times one-worker throughput;
this is a reference, not a physical hardware ceiling. This host has 16
physical cores and 32 SMT threads, and clock speeds and memory bandwidth also
affect the attainable speedup. Current evidence does **not** prove that the
server is close to that ceiling.

### Short-warmup sweep and completed calibration

The first 48,000-mob sweep used 30 seconds of warmup and a 45-second window:

| Workers | Entity steps/s |
| ---: | ---: |
| 1 | 0.38 |
| 4 | 2.48 |
| 8 | 4.98 |
| 16 | 7.98 |
| 31 | 8.41 |

The 16-to-31 gain is only 5.4% in these samples. Ratios against one worker
are **not a verified scaling result**: a separate one-worker run with 360
seconds of warmup measured 1.29 steps/s across 23 complete windows in a
120-second window. Its wrapper was disrupted after measurement by editing
the running shell script; final checks and graceful shutdown were not
verified, and the artifact is marked failed. This observation establishes
that the short warmup was inadequate, not a valid reference for a speedup
claim. `bench_scaling.py` now warns about throughput drift.

The next calibration runs saved baseline `opt-ai-baseline` (cache changes
above) and candidate `opt-cal-candidate` at 1, 16 and 31 workers with
360-second warmups and 120-second windows. `STABLE_ENTITIES=1` makes the test
mobs invulnerable while keeping AI, pushing and pathfinding enabled. Each run
must pass an exact 48,000-mob count and the hopper checks. Scripts and
installed distributions are saved before running. Artifacts:
`build/stress-opt-cal-{base,current}-w{1,16,31}/`.

Candidate changes requiring full-server performance verification:

- Cache Brain traversal as ordered arrays, invalidated on add/clear.
  Running behaviors retain snapshot membership and duplicate occurrences.
  `BrainLayoutCheck` compares with map traversal under changing status and
  reconfiguration.
- Stable radix sorting for large float-key distance lists, preserving float
  rounding and one read per key. `SnapshotSortCheck` covers signed values,
  zeros, infinities, NaNs, duplicates and the threshold. Its stationary-list
  microbenchmark measured about 1.8–2× at 8,192 entries with identical
  checksums. One full-server AI-only pair measured 8.12 before and 7.90 after;
  it does **not** establish an overall gain.
- Skip discarded packet preparation for non-player entities without observers,
  retaining gameplay effect updates and item-frame map updates. Pairing resumes
  from live state. `tracker.txt` and `check_tracker.py` verify current spawn
  position, rotation and the first metadata packet on join and rejoin after
  unobserved changes.

A misc regression hung in the normal tick wait during shutdown, while the old
harness/checker reported PASS. Shutdown now uses a local bounded drain
deadline. `run.sh` records watchdog failures and exit status; the checker and
regression runner reject them. A forced one-second timeout was verified to
fail both runner and checker. Missing named-check results also fail. The
candidate subsequently passed basic, misc and clock regressions with exit
code zero, plus the join/rejoin packet test. The frozen six-case calibration
also completed, with exact 48,000-mob counts, conserved hopper items, zero
logged errors and successful exits in every case:

| Workers | Baseline entity steps/s | Candidate entity steps/s | Change | Candidate CPU / 100% |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 0.45 | 0.45 | -1.4% | 1.85 |
| 16 | 8.37 | 9.17 | +9.6% | 18.43 |
| 31 | 8.84 | 9.85 | +11.5% | 28.30 |

These are one sample per build and worker count, with 22–23 complete windows
per run. The baseline's 31-worker measurement overlapped a helper briefly
and was not repeated to completion; its change remains provisional. The 16-worker pair
supports a gain for the combined Brain, sorting and tracker changes, without
isolating their contributions. The one-worker pair shows no gain. Apparent
speedup relative to one worker exceeds the physical-core count, so it cannot
establish proximity to a hardware ceiling. Logged step rates are estimates
from timer requests, skips and clock rate; direct completed-step counters
would improve accuracy for bursty or starved workloads.

Reproduction with the current installation:

```sh
STABLE_ENTITIES=1 WARMUP=360 BENCH_SECONDS=120 TIMEOUT=900 \
  sh tools/stress/scaling.sh calibration 1 16 31
BOT_OPTS=-Dbot.entities=true TIMEOUT=180 \
  tools/stress/run.sh tracker tools/stress/tracker.txt
python3 tools/stress/check_tracker.py build/stress-tracker/bots.log
```

## POI lookup and wide entity-query experiments

A 20-second JFR recording during the one-worker calibration warmup found
substantial POI stream and section-map lookup cost. It is a diagnostic profile,
not a measured throughput window (`build/opt-cal-w1-warm.jfr`). POI chunk queries
now reuse a versioned array of section objects instead of repeating the vertical
section-map traversal. Additions, removals and ticket changes read live section
records; newly created and loaded sections invalidate the array.

`SectionStorageCheck` exposed two existing concurrency faults: 64 simultaneous
creators returned 64 different objects, and a tick tried to unpack a completed
load already owned by a synchronous reader. Creation is now serialized by chunk
stripe, and pending-load processing skips loads with an unpack owner. Both
negative controls fail against `opt-ai-baseline` and pass against
`opt-poi-candidate`. `PoiChunkCacheCheck` passes empty/new/existing sections,
live record and ticket changes, concurrent additions, persistence and reload.
Public chunk streams bind section membership at consumption; the square-query
stream already defers its internal lookup. A delayed-stream check prevents
an addition between query creation and consumption from being missed.
The checks shut down Minecraft's utility executors after completion.
`PoiChunkCacheBench` queries a 48-block radius over empty chunks and then 75
records at three heights. In later rounds, empty searches fell from roughly
27 microseconds to 2–6, and populated searches from roughly 57–60 to 16–23,
with equal result counts. This ran during a separate server's warmup, so it
shows the local lookup benefit rather than an isolated full-server gain.
Artifact: `build/opt-poi-lookup-bench.txt`.

Entity box caches also record their combined bounds. Queries containing those
bounds scan live membership directly, avoiding grid traversal and duplicate
cached-box reads. `EntitySectionCheck` passes brute-force, typed, boundary,
wide-box, membership, fallback, expiry and concurrent rebuild comparisons.
With 1,500 stationary entities, 80 steps and six rounds, queries inflated by
16 blocks measured about 6.5 microseconds before and 3.1 after in later rounds,
with identical checksums. Four alternating small-query runs with 500 steps
and 12 rounds had overlapping timings (roughly 120–150 ns after warmup).
These microbenchmarks ran alongside calibration warmup and do not establish a
full-server gain. Artifacts: `build/opt-poi-{wide,small}-bench.txt`.

The initial frozen baseline calibration passed exact mob counts, hopper item
conservation and exit-status checks at all three worker counts. Means were
0.45, 8.37 and 8.84 entity steps/s at 1, 16 and 31 workers. The 31-worker window
briefly overlapped a helper check, so it requires a clean repeat. The apparent
18.47x gain at 16 workers is not a hardware efficiency estimate: one-worker
scheduling and wall-clock AI retries can change work per entity step. The
candidate sweep has finished; a full-server POI comparison remains outstanding.
The saved follow-up suite verified all six calibration exits and checks.
`opt-poi-candidate-v2` then passed basic, misc and clock regressions. At the
user's request to wrap up, the suite was stopped during the clean baseline
repeat's warmup; the repeat is marked failed and the POI scaling runs were
not started. Full-server throughput gains from the POI and wide-query
changes remain unverified. State: `build/opt-poi-suite.log`.

The heavy-workload wrapper now preserves runner failures before printing
benchmark summaries and validates each completed log. A synthetic runner with
a successful-looking log but exit status 1 is rejected; an exit-zero control
passes. `STRESS_RUNNER` supports saved harnesses for these runs too. Polls
now ignore interleaved log lines and results from earlier directives, and a
poll deadline without success records a harness failure. `poll_check.py`
covers successful, failed, pending and interleaved responses. A real server
with a deliberately impossible poll stops before the next command and fails
both harness and checker; a passing poll continues and exits successfully.

Scaling summaries now include sampled CPU use (100% equals one logical CPU).
CPU timestamps are aligned from local time to the server timeline and
unwrapped across midnight. `bench_timing_check.py` covers both UTC and local
midnight, timezone offsets, and strict sample boundaries. Heavy summaries use
the same CPU alignment and unwrap server timestamps across midnight; they
reject recorded failures and exclude statistics after finite completion.
The timing check covers a finite completion across midnight and verifies
that a later statistics row cannot affect the result. The provisional
baseline used about 2.15, 19.04 and 27.90 logical CPUs at 1, 16 and 31 workers;
CPU use includes the server, timer and background work as well as carriers.

## Deadlock lending

| Mobs | Lends during the steady window |
| ---: | --- |
| 3,000 | ~2,500/s (dense mob pen) |
| 8,000 | ~60–400/s |
| 16,000 | ~15/s |

A lend is one broken wait cycle. None of these runs had a failed event or a lock
wait over 10 s. At 8,000 and 16,000 mobs entity pushes no longer lock, which is
why those runs lend less than the 3,000-mob run.

## Player actions

20 headless bots (`tools/BotClient.java`) on an idle stone platform, each walking
and alternately placing and breaking a block every 10 client ticks. The latency
is the time from sending the action to receiving the server's block-change ack.

| Build | Joined | Actions / acks | Action latency p50 / p95 / p99 / max |
| --- | ---: | --- | --- |
| vanilla | 19 of 20² | 415 / 322 | 20.7 / 24.3 / 24.3 / 24.3 ms |
| tickless | 20 of 20 | 441 / 432 | 37.6 / 40.1 / 40.2 / 40.2 ms |

² One bot did not finish joining within the short run window.

Both builds are within one to two game ticks. The extra ~15 ms on tickless has
not been investigated. The bot does not simulate client movement fully, so both
builds log "moved too quickly" for it.

## Lock stress

`tools/WorldLockCheck.java`: 32 virtual threads, each running 20,000
transactions that lock 1–4 random locks out of 12, so wait cycles are constant.

| Transactions | Time | Lends | Overlapping use | Locks left owned |
| ---: | ---: | ---: | ---: | ---: |
| 640,000 | 9.74 s | 426,363 | 0 | 0 |

## Reproducing

```sh
./build.sh installDist
python3 tools/stress/gen_world.py build/stress-8k.txt 60 8000 4
tools/stress/run.sh t8k build/stress-8k.txt
python3 tools/stress/check.py build/stress-t8k/out.log 135424
```

To run vanilla, build a checkout of `4d398a1` and point `SERVER_INSTALL` at its
`build/install/minecraft-server`, relative to this repository.

## Rollbacks instead of lending (32,000 mobs, TNT cube)

Deadlocks are now broken by rolling the youngest transaction in the cycle back and running it
again, instead of lending a lock (see `docs/concurrency.md`, "Rollbacks"). Same host,
2026-09-30, alternating runs of the previous commit (`116ee42`, lending) and this change.

32,000 mobs with the hopper rings, clocks and farm of `gen_world.py`: 15 s warmup, then a marked
20 s window (three complete stat windows per run). Both builds report the same pre-existing
10 s lock waits (mobs reading each other's equipment in visibility checks), so
`LOCK_WAIT_KILL=0` was set for all four runs.

| Build | Events/s | Chunk steps/s | Entity steps/s | CPU |
| --- | ---: | ---: | ---: | ---: |
| lending | 335,637 | 19.15 | 7.69 | 1520% |
| rollbacks | 472,796 | 19.00 | 11.79 | 1895% |
| lending | 334,264 | 19.31 | 7.63 | 1516% |
| rollbacks | 459,399 | 18.82 | 11.39 | 1778% |

Entity steps went from 7.66 to 11.59/s (+51%) on average; workers spend less time parked, so
more CPU is used. About 1,000 rollbacks per run.

TNT cube (`heavy.sh <label> tnt`): 12 s with lending (one of two runs never finished its TNT:
the poll timed out), 12 s and 11 s with rollbacks, with 143 rollbacks where lending made 23,000
lends. The item graph test conserved every item (192,550) with about 1,200 rollbacks.

```sh
python3 tools/stress/gen_world.py build/undo-32k.txt 20 32000 4
python3 - <<'PY'
from pathlib import Path
s = Path('build/undo-32k.txt').read_text()
s = s.replace('sleep 20\n', 'sleep 15\nsay BENCH_START\nsleep 20\nsay BENCH_END\n', 1)
Path('build/undo-32k-marked.txt').write_text(s)
PY
LOCK_WAIT_KILL=0 SERVER_INSTALL=build/install-base TIMEOUT=400 tools/stress/run.sh ub32-base1 build/undo-32k-marked.txt
LOCK_WAIT_KILL=0 SERVER_INSTALL=build/install-dev TIMEOUT=400 tools/stress/run.sh ub32-dev2 build/undo-32k-marked.txt
python3 tools/stress/bench_entities.py build/stress-ub32-*/out.log
```

## Fabric mod support

The 2026-10-14 performance review found a 36% entity-rate regression against the reviewed
`build/install/main` artifact. Bisecting `15552f5`, `17d66f0` and `3099eeb` reproduced it in all
three (12.93, 13.12 and 13.05 entity steps/s). The docs commit had identical runtime classes;
removing the Fabric scheduler bridges, UndoFields changes and transaction hot-method changes
individually did not restore speed.

The reviewed main artifact contained older generated Minecraft classes: its entity-unload
method lacked the exclusive drain in `2b17e71`. A clean build of `2b17e71` also reproduced the
slowdown: 12.70 entity / 19.80 chunk steps/s, using 19.2 CPU cores. After closing admission, the server could leave a completed drain waiting until the
next 50 ms network slot. Servicing drained exclusive work from idle task polling, with a
100 microsecond wait cap while admission is closed, restored 19.97 entity steps/s in the
isolating run. The unload protection stays enabled. `IdleDrainCheck` covers idle completion;
all unload and promotion checks remain in the full suite. The promotion negative control
against clean main fails with `Entity promotion notified before block preparation`.

Final comparisons used Temurin 27+35, ZGC, a 4 GB heap, 31 carriers, fresh worlds,
24,000 mobs, 15 s warmup and a 30 s measurement window. The second paired run reversed
server order. These are two samples, not confidence intervals. "Main" below is the reviewed
installation with older generated classes, retained unchanged as the requested fast baseline.

| Server | Run 1 entity / chunk steps/s | Run 2 entity / chunk steps/s | CPU cores, run 1 / 2 |
| --- | ---: | ---: | ---: |
| Reviewed main install | 20.00 / 19.07 | 19.94 / 19.02 | 28.8 / 29.9 |
| Plain branch | 19.96 / 19.62 | 19.96 / 19.30 | 28.4 / 29.1 |
| Fabric, production mods | 19.06 / 19.48 | 19.18 / 18.85 | 29.6 / 29.8 |
| Fabric, same mods plus diagnostic probe | 19.23 / 19.45 | 19.28 / 19.39 | 29.7 / 29.8 |

Plain entity throughput differs from main by at most 0.2%. Production Fabric entity throughput
is 4.3% lower on average. The probe samples are slightly faster within this small sample's
variation; they do not establish that adding the probe improves performance. Every one of
these eight runs conserved exactly 135,424 items and passed the log/error checks. Reproduce
with `STRESS_HEAP=4g STRESS_MODS=build/fabric-benchmark-mods tools/stress/compare.sh LABEL
24000 15 30 main plain fabric`; repeat with reversed server order. Production mods are the
unmodified Modrinth Fabric API 0.162.0+26.3, Chunky 1.5.3, LuckPerms 5.5.85 and spark 1.10.187.
The benchmark mod directory contains those four pinned jars, without the diagnostic probe.

`BUILD=0 TICKLESS_FABRIC=1 STRESS_HEAP=4g GRAPH_SEED=20261014 ./test.sh 5` passed after
building both distributions: all 32 focused checks, all 221 soundness checks and five inventory
audits; basic conserved 65,536 items and graph conserved 193,872. Smoke loaded 47 mods, completed
Chunky's nine-chunk task, wrote/read a LuckPerms permission and returned spark TPS/tick-duration
output with no errors. Both overwrite and zero-match optional injection controls were refused.
Selected output, verbatim:

```text
TICKLESS_PROBE PASS ticks=1280 damage_mixins=2560 damage_events=64 death_mixins=64 death_events=64 block_mixins=64 chunk_unload_events=2 worker_threads=1265 concurrent_ticks=53 legacy_main_ticks=16
ALL FIVE SOUNDNESS WORKLOADS PASSED
PASS IdleDrainCheck
PASS FabricRealModsAndParallelCallbacks
PASS FabricConflict-optional (refused with explicit conflict message)
PASS FabricConflict-overwrite (refused with explicit conflict message)
ALL PASSED
```

Logs remain local under ignored `build/`: `r2-test-final.log`, `stress-fabric-verified/out.log`,
`r2-production-{a,b}.txt`, `r2-probe-overhead-{a,b}.txt` and their `stress-cmp-*/` worlds/logs.
