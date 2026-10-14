# Extended soundness tests

`tools/stress/soundness.py` adds five deterministic tests on fresh dedicated-server
worlds. They test exact correctness and lifecycle progress, rather than throughput.
They also run as the `soundness` scenario in `./test.sh` (included by default).

| Test | Unusual behavior | Required results |
| --- | --- | --- |
| `components` | Competing hopper pickups, full output backpressure, split item entities, 16-item stacks and damaged nonstackable tools at positive/negative chunk borders | Output starts full, intake and output transfers occur, every item and its complete nested components survive exactly |
| `container_moves` | Repeated `clone ... move` of ticking hopper rings across section boundaries | Old positions become air, relocated hoppers consume a fresh feeder item, exact component totals survive and no container drops appear |
| `frozen_entities` | 192 cached entities repeatedly teleport between distant sections while frozen; mounting, dismounting, removal and replacement | Every new/old-region count is exact, passenger links attach/detach, each surviving tag is unique, game time remains frozen and population survives resume |
| `scheduled_moves` | Move unsupported sand with pending ticks, replace the original blocks, step and change rates; replace a frozen pending water source | All 64 sand blocks settle exactly once, all 64 replacement blocks survive, obsolete fluid ticks cannot spread water, no falling blocks or item drops remain |
| `chunk_reload` | Repeated actual unload/reload of an active border-spanning hopper ring and a passenger tree far from spawn | Both chunks become unloaded, entity storage empties, exactly one vehicle/passenger returns with its link, every snapshot conserves items/components and all four reloaded hoppers consume fresh feeder items |

Inventory audits run while frozen. The checker decodes SNBT, including nested
components, typed numeric arrays, quoted braces, quotes and backslashes. It compares
counts separately for every item ID **and complete component value**, preserving
numeric widths and array/list distinctions inside custom NBT. It verifies the
coordinates and number of container dumps, and rejects duplicate slots or invalid stack sizes.
Every named check and inventory snapshot must appear in manifest order before the
single completion marker. A missing result, worker failure, command syntax error,
10-second lock stall, timeout, unsuccessful exit or incomplete shutdown fails the run.
Rechecking a failed run deletes any stale success result.

Run all regressions, including the five new cases:

```sh
./test.sh 15
```

Run just the five extended cases with more repetitions against an isolated build:

```sh
./build.sh installDist -PinstallTo=build/install/soundness-candidate
python3 tools/stress/soundness.py run verify \
  --install build/install/soundness-candidate --seconds 15 --rounds 8 \
  --workers 16 --gc g1
```

Select cases by adding their names after the label. Use `--workers 1` or `--gc zgc`
to repeat under another worker count or collector. Durations, carrier count, collector
and the SHA-256 of the packaged ownership-instrumented engine are recorded with every
manifest and successful result. The driver fixes the failure/stall watchdog settings
and worker/collector options even if ambient stress options differ.

Commands, manifests and harness logs are in `build/soundness/<label>/`. Server logs,
exit status, failure diagnostics and validated results are in
`build/stress-soundness-<label>-<test>/`. Runs are sequential within this driver;
the top-level regression runner can run the other regression scenarios concurrently.
Chunk unload is polled explicitly and can take tens of seconds per repetition.

Recheck an existing run or exercise the checker itself:

```sh
python3 tools/stress/soundness.py check \
  build/stress-soundness-verify-components build/soundness/verify/components.json
python3 tools/stress/soundness_check.py
```

The synthetic checker controls deliberately remove/duplicate items and components,
corrupt/truncate inventory dumps, omit/reorder audit markers, fail command checks,
inject stalls and errors, and combine success-looking logs with failed exits or
watchdogs. They also accept legitimate split stacks and omitted singleton counts.

`tools/BlockEntityTimerLifecycleCheck.java` isolates the scheduler registration
hooks and verifies promotion before wrapper publication, 1,000 repeated resumes
of a retained ticker, duplicate registration, removal/replacement, and stopped
producers. It fails on the original engine when a retained ticker stays paused;
it also fails on the first fix when promotion cannot see an unpublished wrapper.
The server scenarios exercise these hooks through real loading and item transfers.
`ChunkTickerPromotionCheck` verifies new and replaced holders while the visible
map is empty or stale, plus demotion and removal. It fails on the prior build
because the new holder's ticking start is silently omitted.

Run all focused Java checks against the packaged jar with Java 25:

```sh
BUILD=0 SERVER_INSTALL=build/install/soundness-candidate tools/check.sh
```

The `focused` scenario is included in the default `./test.sh` run. Its runner
compiles all check sources (including shared fixtures), discovers their Java
packages, and runs each check in a separate JVM with G1, a 2 GB heap and a
150-second timeout. Output is saved in `build/checks-focused/<CheckClass>.log`;
any compilation error, assertion, nonzero exit or timeout fails the scenario.
`CHECK_TIMEOUT` overrides the per-check timeout. Individual checks can be selected:

```sh
tools/check.sh LevelTicksCheck
BUILD=0 SERVER_INSTALL=build/install/soundness-candidate tools/check.sh EntitySectionCheck PathTypeCacheCheck
```

`LevelTicksCheck` covers zero/negative and partial drain budgets, deferred callbacks,
callback exceptions, same-position self-rescheduling, duplicate notifications,
listener replacement/disabling, container replacement/removal/re-registration,
inclusive copy/clear across negative chunk borders, saved delays and priorities,
pending-tick deduplication, repeated unpack, immutable scheduled positions,
competing producers and simultaneous drains. Sixteen producers submit 81,920
requests for 256 unique ticks; eight drainers must execute each tick exactly once
and leave no running state behind. The zero-budget and obsolete-container checks
fail on the previous packaged engine: a nonpositive drain must consume no work,
and replacing a container must detach its old scheduler listener.

`SectionStorageCheck` also models a submitted save whose I/O task has not yet
been accepted. Closing storage must complete that save before shutting the worker
down; a failed save must retain its cause and still close the storage. The
unfinished-save check fails on the previous engine. The real save/reload check
also exposed this race intermittently: `flushAll` queues writes, and immediate
shutdown could discard them before the I/O worker accepted them. Section storage
now waits for queued writes and a disk flush before closing its worker.
Repeated close is also checked after both a successful save and a failed one;
it must return without submitting another request to the stopped worker.

`ChunkTickerPromotionCheck` runs hot readers while another thread replaces a
chunk's status supplier or changes its holder's ticket level. The readers must
observe promotion without relying on another shared field to publish it. The
previous engine retains `FULL` indefinitely; fixing only the supplier still
leaves the holder reader stuck at `BLOCK_TICKING`. Both fields are now volatile,
so world workers see the server thread's status changes. These checks supplement
the live border-spanning hopper and chunk-reload scenarios.

## Verification on 2026-10-02

Engine: `build/install-final`, SHA-256
`3ca9b8ca28dc639209645400778f07fbd5aab97b3bcc361d1c4e33ff70974f5e`.

| Verification | Configuration | Result |
| --- | --- | --- |
| Clean Gradle build | Java 25, packaged ownership transform | BUILD SUCCESSFUL |
| Default `./test.sh 5` | G1, graph seed 20261002, 16 soundness workers, 2 repetitions | ALL PASSED: basic, misc, clock, graph, soundness and focused |
| 22 focused Java checks | Packaged engine, G1, 2 GB heap | PASS, including hot-reader publication and queued-save close controls |
| Five soundness workloads | Same default regression run | PASS: 221 named checks and 5 exact inventory audits |
| Repeated chunk reload | G1, 16 workers, 5 seconds, 4 repetitions | PASS: 16 named checks and 5 exact inventory audits |
| Python harness controls | Soundness, extended, poll and benchmark timing checkers | PASS, including 34 deliberate soundness failures |

The graph conserved all 185,565 items, the basic hopper rings conserved 65,536
items, and clock changes were exactly 0, 1, 3, 0 and 30 ticks. The drain-budget,
obsolete-listener and unfinished-save checks failed on the previous engine.
The hot-reader check failed with an indefinitely stale `FULL` status; fixing
only that supplier left the separate ticket-level reader stuck at `BLOCK_TICKING`.

Earlier live attempts hit intermittent initial hopper-transfer timeouts and a
section-storage reload failure. They were not counted as successful runs.
The final build and full regression ran with no temporary logging or runtime
probes. These checks establish correctness for the listed cases; no performance
measurement was made.

## Verification on 2026-10-01

Final engine: `build/install/soundness-verified`, SHA-256
`d5332ffba799138510b9d5bcbede8f908e4aff61c847e803791142c7e0f2e9c2`.

| Verification | Configuration | Result |
| --- | --- | --- |
| Five new workloads, `verified-g1` | G1, 16 workers, 15 seconds, 8 repetitions | PASS: 275 named checks and 11 exact inventory audits |
| Five new workloads, `verified-zgc` | ZGC, 1 worker, 15 seconds, 8 repetitions | PASS: 275 named checks and 11 exact inventory audits |
| Default `./test.sh 15` | G1, 16 workers, graph seed 20261004, 2 soundness repetitions | ALL PASSED; graph conserved 186,994 items; clock changes were 0, 1, 3, 0, 30 |
| 21 focused Java checks | Packaged engine, G1, 2 GB heap | PASS; lock stress committed 640,000 transactions with 202,675 rollbacks, zero overlapping touches and exact rollback accounting |
| Five existing extended workloads | G1, 16 workers, scale 0.02, 10-second warmup, 25-second measurement | PASS: crowd, village, sorting, pistons and gravity conservation/activity audits |
| Harness controls | New soundness checker plus existing extended/poll/timing checkers | PASS, including 34 deliberate soundness failures |

The long runs verified all four hoppers consuming fresh items on initial load
and after every reload. Their final inventories contained exactly 292 diamonds,
with each component variant accounted for separately. Each reload also restored
one vehicle and one passenger with their link intact. The component fixture
conserved 2,592 items across 48 containers and any remaining dropped entities.

The tests exposed missed chunk promotions caused by looking up holders in the
stale visible map, and retained timers failing to resume. The promotion path now
uses the current holder map; ticker registration and promotion resume existing
registry entries, including entries whose wrapper has not yet been published.
The focused promotion and lifecycle checks fail on the corresponding prior jars
and pass on the final engine. Temporary diagnostic logging was removed.

Logs are `build/soundness-verified-{g1,zgc,regress,existing}.log`,
`build/soundness-verified-focused{,-rest}.log` and
`build/soundness-verified-lifecycle.log`. An intermediate Mojang service connection
timeout and a SectionStorage five-second gate timeout passed on retry; neither
was counted as a successful initial run. Extended workloads used reduced scale
for correctness verification; these results establish no performance speedup.

These tests cover the listed behaviors. They do not prove equivalence for all
Minecraft contraptions or preserve vanilla ordering of concurrent events.

## Active-event chunk unload regression

`ChunkUnloadCheck` starts the normal dedicated server in a fresh temporary world with a
test-only Java agent that captures its server instance. It loads a remote chunk, admits a
worker transaction that retains that chunk, removes the force-load ticket, and services the
real unload queue while the event is paused. The event then makes its final block write.
The test requires unload to wait, and reload from disk to contain the final diamond block.
The previous engine unloads while the event is paused and reloads the old stone block.
The agent is assembled under `build/` by the check and is never included in the server jar.

`ChunkUnloadLifecycleCheck` exercises the real entity section manager with captured save
snapshots. It requires the final entity write to precede persistence and removal, checks
that revived visibility cancels a deferred unload, and verifies nested events from an
admitted producer can drain through a closing save gate. `QueuedChunkTimerCheck` queues a
retained ticker while the gate is closed, ends its chunk lifetime, and requires that ticker
to skip its stale update after admission. The previous engine fails both lifecycle checks.

All three checks are discovered by `./test.sh` through its focused scenario. Each has bounded
waits; the dedicated-server regression has an 80-second process deadline and uses its own
world and a dynamically allocated network port.
