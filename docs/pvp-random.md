# Empty-start free-for-all

`tools/stress/pvp_random.py` creates a seeded 32 × 32 arena with 16 scattered chests,
four hopper catchers, floor loot and finite supplies of five armor tiers, swords,
golden apples and slime blocks. Four supply stacks fall from above into hoppers.
Every player starts in survival with an empty inventory and no equipment; full
player NBT proves this before floor items are summoned and the bots start.

Players walk toward random chest/floor waypoints and random positions, avoiding
chests and observed placed blocks. They loot through normal container packets,
collect floor items through normal server pickup, upgrade each armor slot only
when protection increases, waiting for authoritative confirmation of each swap,
select a sword, attack random nearby opponents and
place blocks at random nearby ground positions. Deaths drop the inventory and
worn armor; players respawn empty and loot again. Armor and weapons are unbreakable.
Golden apples are collected, rather than consumed, so they remain in the finite
ledger. No supplies are replenished. Movement is a small scripted collision-aware
client, not the full Minecraft navigation/physics implementation. A deterministic
seed makes the fixture and decisions repeatable; concurrent outcomes can differ.

After clients stop acting, the server is frozen and the checker audits all players,
armor, chests, hoppers, loose item entities and every buildable arena position.
Exact item ID, component types/values and counts must match the starting supply.
Each placed slime block accounts for one consumed inventory block. Ground entity
UUIDs and holder names must be unique; selected-item NBT must agree with inventory
NBT. Known supply items are counted even outside the arena. Unrelated natural
terrain drops outside the arena are listed separately, with coordinates; an unknown
item inside the arena fails. Missing participants, records, completion, gameplay,
CPU samples or any server error also fail. The audit does not claim a general proof
of every possible Minecraft inventory interaction.

## Attack soundness

`-Dtickless.attackAudit=true` enables benchmark-only tracing. Every handled attack
packet receives exactly one committed outcome, recorded after the world transaction
commits. Rejected attacks are classified at the actual range, readiness, item,
PvP or damage-validation branch. Damage cooldown is a normal rejection: equal or
weaker damage cannot hurt again during its protection window. An unclassified
return remains `unexplained` and fails the audit. Client submissions are reconciled
against `(player, target)` server outcomes, and committed sequence numbers must be
unique. A missing packet, duplicate result or unsupported reason fails.

The official vanilla jar is unmodified, so it has inventory/world audits but no
internal rejection trace. Random fights do not assign hurt-animation latency to a
particular attacker: multiple attackers can hit one victim. Comparable latency
measurements are sequenced use acknowledgements and chest-content responses.
Block-transition samples can refer to a competing player's change at the same
position and are diagnostic, not causal per-action latency.

## Run and verify

```sh
./build.sh installDist -PinstallTo=build/install/random-arena
tools/stress/setup_vanilla.sh
python3 tools/stress/pvp_random.py run comparison --players 64 128 256 --seconds 45
python3 tools/stress/pvp_random.py check build/stress-random-comparison-tickless-64
python3 tools/stress/pvp_random_check.py
```

The checker regression uses the local `verified` 64-player fixture, including
negative controls for lost/modified items, missing blocks, nonempty starts, missing
attack packets, unexplained rejections, duplicate outcomes and missing CPU records.
Run with `run verified --players 64 --servers tickless` to create that fixture.

Server and bot CPU/RSS are sampled separately from their Linux process counters.
100% CPU is one core; bot cost is not charged to the server. Tickless throughput
uses committed player steps divided by elapsed time and population. Vanilla uses
game-time increments. Console timestamps have one-second resolution, so measured
rates near 20 have rounding error. CPU excludes the first five seconds. These are
single local runs, with tracing enabled on tickless, not confidence intervals or a
universal capacity claim. The real-client video is recorded in a separate demo and
is excluded from performance measurements.

At 256 players an earlier client sent repeated SWAP clicks before receiving slot
updates; swaps toggle contents, so a repeat could undo an upgrade. The corrected
client permits only one outstanding swap, confirms both changed slots, avoids
opening a chest while an inventory swap is pending, and resets its inventory view
on death/respawn. The checker rejects observed armor downgrades. That earlier
failed trial is excluded from the final comparisons.

## Final local measurements

45-second runs, 6 GiB server heap, 2 GiB bot heap, Temurin 27, ZGC. CPU values
are mean cores used by each process. Tracing was enabled on tickless.

| Players | Server | TPS / player steps/s | Ack p50 / p95 (ms) | Server / bots CPU cores | Items conserved |
| ---: | --- | ---: | ---: | ---: | ---: |
| 64 | Tickless | 20.03 | 0.86 / 3.08 | 3.70 / 1.84 | 554 |
| 64 | Vanilla | 20.35 | 38.76 / 63.00 | 2.15 / 0.83 | 554 |
| 128 | Tickless | 16.89 | 2.33 / 116.18 | 9.68 / 4.90 | 1,108 |
| 128 | Vanilla | 6.13 | 141.82 / 363.25 | 4.84 / 2.21 | 1,108 |
| 256 | Tickless | 1.64 | 2116.38 / 6302.36 | 10.91 / 5.82 | 2,234 |

All five runs passed their item/world audits. Tickless accounted for every one of
its **192,294 attack packets**, with no unexplained rejections or observed armor
downgrades. The 256-player case is saturated and unsuitable for play despite
passing the integrity audit. The additional vanilla 256 run was omitted to shorten
the remaining work at the user’s request.

Final records: `build/random-complete-results.json`,
`build/stress-random-complete-{tickless,vanilla}-{64,128}/random-result.json` and
`build/stress-random-complete-tickless-256/random-result.json`. Commands, manifests,
CPU samples and logs remain alongside each result. Config hashes identify the
benchmark runtime. The subsequent `random-final` build also avoids repeated PvP
permission checks and removes disabled audit thread-local allocation; the tested
tracing paths and fixture behavior are equivalent.

## Real-client video and validation

`build/random-film2/pvp-random.mp4` is the 30-second real Minecraft 26.3 recording;
`pvp-random.gif` is a looping preview. It contains 120 actual rendered screenshots
with their original elapsed-time intervals preserved, encoded as 1280 × 720 H.264.
The separate demo uses 64 bots and two spectator camera views. Recording starts
mid-match, after looting has begun. The client’s local fixture directory is isolated
from existing Prism instances. Rendering is excluded from all five final performance
measurements. `frames.jsonl` and source PNGs are retained beside the video.

The final runtime passed all **37 focused engine checks**; the audit checker passed
**14 negative/control replay tests**. The earlier broad concurrency-fix regression
also passed basic conservation, clock tests and all five soundness workloads (221
named checks plus five inventory audits). Latest logs: `build/random-focused-checks.log`,
`build/random-audit-check.log`; earlier broad log: `build/chaos-regress.log`.

## Parallel player tracking follow-up

Player gameplay already ran on individual worker lanes, but player tracking deltas
were explicitly excluded from the parallel tracker pass. They now run on the same
player lane, ordered with packets and simulation. A single pending delta job per
tracker coalesces delayed passes. The player/vehicle entity locks protect the
tracker codec, dirty metadata and attribute state; pairing and tracker getters
use the same lock. Removed players are skipped, and disabled/stopped lanes retain
the main-thread fallback. Item-frame map updates and global tracker membership
stay on the server thread.

Tracking codec/cache state is not part of the world undo log, so the update becomes
irrevocable after acquiring its source entity locks. Completion clears the pending
flag after the whole event finishes, rather than on an individual rollback attempt.
`PlayerTrackingLaneCheck` exercises concurrent lanes, held locks, ordering,
coalescing and removed/stopped players. All 38 focused engine checks passed.

A same-install 128-player comparison (30 seconds, same fixture/policy and tracing)
measured main-thread tracking versus worker-lane tracking:

| Tracking | Player steps/s | Ack p50 / p95 (ms) | Server CPU cores | Items conserved |
| --- | ---: | ---: | ---: | ---: |
| Main thread | 15.02 | 5.89 / 265.82 | 9.05 | 1,108 |
| Player lanes | 19.24 | 4.14 / 46.13 | 11.20 | 1,108 |

Both runs accounted for every attack and passed the item/world audit. This is one
paired comparison, not a confidence interval. Tracking on player lanes is the
default; `-Dtickless.parallelPlayerTracking=false` retains the control path.
Records: `build/player-tracking-comparison.json`, `build/stress-player-tracking-{false,true}/`
and `build/player-tracking-checks.log`. Installation: `build/install/player-tracking`.
The earlier final table predates this optimization and remains historical data.

The subsequent 256-player, 20-second worker-tracking check also passed: 2,234 items
conserved and all 50,354 attacks classified, with no observed downgrade. It remained
overloaded at 2.39 player steps/s (ack p95 2.98 seconds); the shorter run is not
a controlled numerical comparison with the earlier 45-second capacity test.

## CPU and wait profiling follow-up

Two fresh 256-player, 45-second runs used the player-tracking installation, Java 27,
6 GiB ZGC, attack tracing, JFR CPU/monitor/park recordings and five virtual-thread
dumps. Both passed inventory/block conservation (2,234 items), armor checks and
exact attack accounting. These are diagnostic runs with profiler overhead, not
replacement capacity measurements.

The default run completed 1.22 player steps/s at 10.02 server CPU cores. In 6,375
JFR execution samples, 1,166 (18.3%) had `ServerWaypointManager` in the stack.
Its global monitor accounted for 1,721 recorded contention events: mean 6.67 ms,
p90 13.6 ms, maximum 73.2 ms. Across five dumps, 833 of 1,241 worker observations
(67.1%) were waiting in `WorldLock.acquireSlow`, 277 (22.3%) waited for a main-thread
handoff, and 118 (9.5%) were monitor-blocked. Of the monitor-blocked workers, 117
were entering waypoint updates. These are repeated thread observations, not
unique players or measured fractions of wall time.

The dependency chain is movement -> globally synchronized waypoint update while
holding the mover's entity lock -> attackers waiting for that entity -> ordered
player-lane packets and steps waiting behind the blocked action. The main thread
was also waiting for player locks in chunk-view changes or `sendPairingData` in
four of the five dumps. Tracking membership and death/respawn handoffs therefore
amplify the contention. Entity timers suppress another pending step instead of
queuing catch-up steps (`worldTickQueued`), so delayed lanes deliver fewer than
20 committed steps/s even when some cores are available.

The only gameplay configuration change in the second run was
`gamerule locator_bar false`: it reached 3.07 steps/s at 13.86 server CPU cores,
still heavily overloaded. Disabling the bar does **not** bypass the synchronized
waypoint scans: `Entity.setPosRaw` calls both updates, and the game-rule check is
inside `createConnection`/`updateConnection`. This diagnostic pair also differs
in the timing of the second JFR recording; it is not a precision speedup estimate.
It supports waypoint connection work as a significant bottleneck, while the
remaining player-lock and main-thread waits show that removing it alone would
not establish 20-step capacity.

The next optimization target is coalescing waypoint updates and avoiding global
connection scans on every movement, with world-lock waits kept out of the global
monitor. It must preserve connection creation, updates, removal and packet order;
removing the monitor would reintroduce the previously reproduced collection race.

Evidence: `build/profile-player-steps/{arena.jfr,waits.jfr,threads-*.json,analysis.json,
hot-methods.txt,contention.txt,result.json}` and
`build/profile-player-steps-no-locator/{arena.jfr,waits.jfr,threads-*.json,result.json}`.
Full gameplay logs and audits are in `build/stress-profile-player-steps/` and
`build/stress-profile-player-steps-no-locator/`.

A separate 128-player, 30-second CPU recording found 767/5,400 samples (14.2%)
under the waypoint manager and 555/5,400 (10.3%) under `Heightmap.update`. Of the
heightmap samples, 352 came from noise terrain filling and 176 from surface
generation: fresh-world chunk generation is competing with player work in these
measurements. This run's strict workload audit rejected `Bot101` for zero movement
packets, despite chest interaction, armor upgrades and attacks. Its CPU recording
is useful diagnostic evidence, but it is **not** an accepted capacity or complete
soundness result. Files: `build/profile-player-steps-128/{arena.jfr,threads-*.json,
hot-methods.txt,cpu-events.json,run.log}` and `build/stress-profile-player-steps-128/`.
The earlier accepted 128-player 19.24-step result remains the comparison figure.

## Waypoint convoy fix and precise measurements

Movement now publishes dirty waypoint sources/receivers after its transaction
commits. It does not acquire the manager monitor while holding player locks.
Each level's periodic server-thread refresh processes each affected connection
once, using the latest source/receiver positions. Registration, disconnection,
removal and the connection table remain protected. Removed identities cannot
recreate connections, and a disabled locator bar skips the refresh scan.
`-Dtickless.coalesceWaypoints=false` retains the old immediate update path.

`WaypointCoalescingCheck` holds the manager monitor while a locked worker commits
100 source/receiver updates, then verifies one refresh, no dirty replay, no
publication from an aborted attempt, removal, reconnect and the disabled bar.
All **39** engine checks passed (`build/waypoint-all-checks.log`). All **19** audit
and step-clock checks passed (`build/waypoint-audit-checks.log`).

The step-rate calculation previously divided by whole-second log timestamps.
A 20-second interval rounded to 21 seconds can report roughly 19 steps/s even
at 20. Stats now include a monotonic nanosecond timestamp, and the random-arena
checker uses that elapsed time. Old logs retain their legacy calculation and
are labeled `log_seconds`; new results are labeled `monotonic_ns`. Mixed clocks
and nonpositive intervals are rejected. This corrects measurement precision,
without changing simulation scheduling.

Final runs used `build/install/waypoint-final`, 30 seconds, 6 GiB ZGC, 2 GiB bots,
the same seeded empty-start arena and attack tracing, with no CPU profiler:

| Players | Committed player steps/s | Action ack p50 / p95 (ms) | Server CPU cores | Bot CPU cores | Items conserved | Attacks accounted |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 128 | 19.14 | 2.94 / 47.85 | 11.08 | 3.53 | 1,108 | 36,533 |
| 256 | 9.09 | 211.82 / 4,304.86 | 13.40 | 4.87 | 2,234 | 68,967 |

Both passed full item/floor/block conservation, armor and hit accounting, with
zero unexplained/missing attack outcomes. The 128-player run recorded 486 kills
and 773 armor upgrades; the 256-player run recorded 485 kills and 1,000 upgrades.
CPU cores are logical-core equivalents, with 100% process CPU equal to one core.
These are single runs. The 256-player case remains overloaded; the fix does not
establish 20-step capacity there. The earlier accepted 256-player tracking run
reported 2.39 steps/s over 20 seconds using rounded timestamps, so it is context
rather than a controlled speedup denominator.

The initial same-install flag comparison is retained in
`build/waypoint-comparison.json`: both 128-player runs and the optimized 256-player
run passed. The old-path 256-player run was rejected by the strict checker for
`StacklessClosedChannelException` messages during shutdown and is excluded from
accepted comparisons. The first 128-player pair's action ack p95 was 42.01 vs
22.47 ms; the final repeat above demonstrates variability and is the published
precise result, rather than selecting the best sample.

Final records: `build/waypoint-final-results.json`, `build/waypoint-final-config.json`
(jar/tool hashes and settings), `build/stress-waypoint-final-true-{128,256}/`, and
`build/waypoint-final-measurements.log`.

## Packet delivery, connection reuse and item snapshots

Profiling the waypoint-coalesced 256-player workload identified packet delivery
as the next cost: `Connection.flushQueue` was the hottest leaf method (590/6,504
execution samples, 9.1%; 612 samples, 9.4%, inclusive). Netty appeared in 39.4%
of sampled stacks. Across five thread dumps, 604/930 worker observations (64.9%)
waited for main-thread handoffs. Records: `build/profile-after-waypoints/`.

Established connections now bypass the empty pending-action monitor. Server
gameplay sends share a per-connection FIFO: one Netty task writes up to 256
packets and flushes once when requested. Real futures retain their callbacks;
flush=false, explicit flushes, direct event-loop sends, pre-connect actions and
protocol-change barriers preserve delivery order. A real Netty regression also
checks 32 concurrent producers, failed writes and continuation after failure.

The clean 256-player, 45-second same-install network flag comparison passed both
audits: 8.42 -> 12.17 player steps/s and ack p95 2,847.87 -> 1,152.24 ms.
The optimized 128-player, 30-second sample reached 19.86 steps/s, ack p95 6.38 ms,
and 7.87 server CPU cores. Records: `build/network-final-results.json`,
`build/network-final-config.json`, `build/stress-network-final-*/`. No other
stress container overlapped those measurement windows.

The subsequent profile found main-thread waypoint connection creation and a
removed-item warning flood (`build/profile-after-network/`: 27,012 warnings).
Entity connections now retain their block/chunk/azimuth representation until
visibility, range or distance changes the selected type. Multi-block movement
uses coordinate updates instead of unnecessarily recreating the connection.
Removed items cannot enter tracking or pair with new receivers. New item pairing
captures a live source under its entity lock; newly acquired main snapshot locks
release after the read, while existing, worker and Fabric ownership is retained.

A faster run exposed an indexed sound-broadcast race in `PlayerList.broadcast`
during respawn: `CopyOnWriteArrayList.size()` and `get(i)` could observe different
arrays and throw in a hit handler. All remaining indexed player-list loops now
use iterator/array snapshots. The strengthened regression reproduces the actual
old sound-broadcast exception (`build/player-broadcast-negative.log`) and passes
on the corrected engine. The rejected gameplay run is retained at
`build/stress-arena-final-true-256-a1/`; it is not included in accepted results.

The first live-item guard retained its source locks until the entire main event
ended and regressed the 256-player result to 8.30 steps/s. That intermediate
result is retained in `build/arena-complete-results.json`. The final snapshot
scope fixes that convoy; it does not release pre-existing or worker ownership.

Final installation: `build/install/arena-snapshot`. Both final runs used 6 GiB
ZGC, 2 GiB bots, the same seeded fixture and attack tracing, precise monotonic
timing, no profiler and zero foreign stress-container overlap samples:

| Case | Seconds | Player steps/s | Action ack p50 / p95 (ms) | Server CPU cores | Bot CPU cores |
| --- | ---: | ---: | ---: | ---: | ---: |
| 256, network control before fixes | 45 | 8.42 | 151.07 / 2,847.87 | 13.23 | 5.79 |
| 256, final combined fixes | 45 | 11.85 | 5.53 / 1,348.57 | 12.00 | 5.55 |
| 128, final combined fixes | 30 | 19.91 | 1.29 / 9.40 | 9.91 | 2.82 |

The control row is the accepted same-fixture network control above; the final
rows also include the subsequent correctness/connection fixes and use a different
build. These are single samples, not confidence intervals. The final 256-player
case delivered about 41% more steps than that control at lower server CPU, but
remains overloaded and does not establish 20-step capacity. Both final audits
conserved 2,234/1,108 items and reconciled floor entities and placed blocks; all
142,718 submitted attacks have exact recorded outcomes, with zero unexplained
outcomes. They recorded 1,087/499 kills and 1,897/807 armor upgrades.

All **42** focused engine checks passed (`build/snapshot-all-checks.log`), including
snapshot lock lifetime, nested/outer ownership and worker retention. The separate
Fabric-mode snapshot check also passed (`build/snapshot-fabric-check.log`). All
**24** audit/clock controls passed (`build/snapshot-audit-checks.log`).

The final 128-player shutdown logged two exact Netty closed-channel exceptions
after the complete audit and the actual server-thread `Stopping server` message.
The checker now records these terminal socket callbacks separately, while
rejecting the same exception before shutdown, a forged stop chat, missing stop,
all other exceptions and all gameplay errors. Five controls enforce that boundary;
raw logs retain the warnings. This does not exclude errors such as the sound race
or the previously documented structure-cache shutdown failure.

Final records: `build/arena-snapshot-results.json`, `build/arena-snapshot-config.json`
(jar/tool hashes), `build/stress-arena-snapshot-true-{128,256}-a2/`, and
`build/benchmark-arena-snapshot.py` (run from the repository root). Earlier rejected
and regressed stages remain available; the table publishes the final sample
rather than selecting the best intermediate one.
