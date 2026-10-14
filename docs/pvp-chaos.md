# PvP chaos: capacity, CPU and a real-client recording

This extends the controlled [PvP arena](pvp-arena.md) with running, rapid attacks,
deaths/respawns, chest transfers, and finite gear rain feeding hoppers above chests.

`tools/stress/pvp_chaos.py` runs identical fresh worlds sequentially on the official
vanilla 26.3 server and tickless. The default client cadence is a movement/client tick
20 times/s, ten attack attempts/s, ten alternating place/break actions/s, and repeated
chest opening/quick-move/closing. Attacks and block actions also switch held slots.
The script can escalate player counts from 2 to 4,096. This is a packet-heavy workload:
player count alone does not describe the load, and many rapid attacks are legitimately
rejected by damage invulnerability and attack cooldowns.

Fighters run bounded paths around private build spots in pairs. Knockback resistance
keeps them in reach, six maximum health accelerates combat deaths, and clients request
real respawns. Every ten seconds a mass-kill command also exercises simultaneous
respawns. Minecraft may credit those command deaths to a recent attacker; the player
kill scoreboard is therefore not an independent count of purely sword-caused deaths.
Inventory retention is enabled across death so that the gear ledger stays exact.

Each pair shares a gear chest with a downward hopper directly above it. Initial player
weapons/helmets and chest contents are tagged, unbreakable gear. Every two wall-clock
seconds, a batch of swords or helmets is spawned 17 blocks above every hopper. Bots
open those chests and request real quick-move transfers. At the end, the auditor parses
all player inventories **and worn equipment**, all hopper/chest inventories, and full
NBT for any tagged items still in the world. Exact item/component signatures and totals
must equal the initial supply plus the counted rain batches. Duplicate holder/ground
records, lost players, modified gear, errors, stalls and incomplete audits fail.

Slime blocks are replenished in hotbar slot 1 every four seconds as a building fixture.
They are excluded from the finite gear ledger. The workload does not establish item
conservation for that unlimited building supply. Players keep their own spawn locations
and gear across respawns; the visual movement is a scripted path, not navigation AI.

## What the measurements mean

- Vanilla TPS uses completed game ticks between server markers. Tickless player rate
  uses the dedicated **committed player simulation-step counter**, not its game clock
  or the non-player entity-step estimate.
- CPU comes from host `/proc/PID/stat` counters and is sampled separately for the server
  JVM and bot JVM. One CPU core means 100% of one logical CPU. RSS is also retained.
  `process-cpu.jsonl` stores timestamps, intervals, CPU and RSS. Statistics include only
  samples inside the client window, after the first five seconds.
- Ack latency covers cumulative acknowledgements for block actions and chest-use actions,
  including actions refused by gameplay rules. All acknowledgements are accounted for.
- Block-update latency samples matching air/non-air transitions at private build spots.
  Under overload, repeated actions can overtake their feedback and Minecraft can merge
  state updates. The matched-update ratio is observational coverage, **not** a proof
  that every unmatched attempt lost a packet. Rejected placements can also reduce it.
- Chest-open latency ends when the server sends the initial container contents.
- The rapid attack stream has no per-hit response sequence number. Combat probes time
  the first outstanding attack until the next accepted hurt animation, with a two-second
  timeout. This includes cooldowns, respawn protection and waiting for an accepted hit;
  it is **not** a pure packet-processing latency and is not comparable to the earlier
  one-outstanding, one-attack-per-second benchmark.

Only completed, audited runs are successful measurements. CPU/client records from a
failed overload case can be useful diagnostics, but are not passing soundness results.
Console commands themselves can be starved by excessive incoming packets; absent or
late server markers must not be presented as a valid 20 TPS result. Per-run server and
client window lengths are retained. These initial sweeps are single samples per size,
not confidence intervals or a universal maximum-player recommendation.

## Reproduction

```sh
./build.sh installDist -PinstallTo=build/install/chaos-final
tools/stress/setup_vanilla.sh
BUILD=0 SERVER_INSTALL=build/install/chaos-final \
  tools/check.sh TrackingPlayersCheck PlayerBroadcastSnapshotCheck ChunkMoveShutdownCheck MonitorCycleCheck
STRESS_HEAP=6g BOT_HEAP=3g python3 tools/stress/pvp_chaos.py run sweep \
  --players 64 128 256 512 1024 --warmup 10 --seconds 30 \
  --attack-every 2 --block-every 2 --install build/install/chaos-final \
  --continue-failures
```

Every run retains commands/datapack fixtures, server/bot logs, CPU samples, world and
`chaos-manifest.json`; successful audits write `chaos-result.json`. An aggregate is
written after every run, including failures: `build/chaos-LABEL-results.json`.
Re-audit with `python3 tools/stress/pvp_chaos.py check RUN_DIR`.
`pvp_chaos_check.py` replays the small smoke run with corrupted audit records and missing
CPU samples to check fail-closed behavior. Run the four-player `smoke3` fixture first.

The first large trial exposed concurrent modification of the entity tracker's identity
set while player lanes broadcast equipment or swings. `IdentitySnapshotSet` retains
identity-based membership and publishes immutable iteration snapshots; broadcasts do
not hold its membership monitor while sending packets. `TrackingPlayersCheck` exercises
identity semantics, retained snapshots and concurrent broadcasts during pairing churn.
Those pre-fix runs are excluded from successful comparisons.

The same escalation also exposed a second broadcast race in the global `PlayerList`
and a movement-dispatch recursion during shutdown. The global player list now uses
copy-on-write iteration, and chunk-movement callbacks cannot re-enter from a worker
when `execute()` switches to inline execution after stop.
`PlayerBroadcastSnapshotCheck` tests the actual player-list constructor under churn
and fails on the saved pre-fix runtime. `ChunkMoveShutdownCheck` simulates stop between
the dispatch check and callback execution; `TrackingPlayersCheck` and the monitor
cycle check pass alongside it.

## Real Minecraft client

`ArenaViewer.java` launches the real cached vanilla 26.3 client using offline fixture
credentials and records its rendered frames. `arena_viewer.py` resolves cached Prism
libraries and assets, verifies missing official library downloads by their published
SHA-1, and writes only to an isolated `build/` client directory. It does not change an
existing Prism instance, account or options. `STRESS_PUBLISH_PORT=25575` publishes a
server's port only on loopback so this spectator can join.

The recording uses a separate demonstration run, **outside all performance windows**.
Start the viewer, switch `ArenaCam` to spectator and position it using the demo server
console, then touch `CAPTURE_DIR/capture-start`. The helper saves up to eight actual
Minecraft screenshots per second for 30 seconds. Encode the frames with `ffmpeg`:

```sh
python3 tools/stress/arena_viewer.py 127.0.0.1:25575 build/arena-capture
ffmpeg -framerate 8 -i build/arena-capture/frame-%05d.png \
  -c:v libx264 -pix_fmt yuv420p build/arena-capture/chaos.mp4
```

The visual demo shows a smaller crowd that can render smoothly; it is not evidence of
client render performance at the largest server load.

## Earlier paired overload results

These 30-second measurements use paired movement and replenished building blocks,
not the newer empty-start free-for-all. Server heap 6 GiB, bots 3 GiB, Temurin 27,
ZGC. CPU is the server process only.

| Players | Server | TPS / player steps/s | Ack p95 (ms) | Server CPU cores | Audit |
| ---: | --- | ---: | ---: | ---: | --- |
| 64 | Vanilla | 20.03 | 61.55 | 2.03 | PASS |
| 128 | Vanilla | 1.77 | 2175.13 | 3.43 | PASS |
| 64 | Tickless | 19.89 | 1.82 | 3.10 | PASS |
| 128 | Tickless | 18.46 | 21.68 | 10.37 | PASS |
| 256 | Tickless | 4.49 | 15730.10 | 9.54 | PASS |
| 512 | Tickless | 0.31 | 27376.27 | 7.05 | PASS |
| 256 | Vanilla | — | — | — | FAIL |
| 1024 | Tickless | — | — | — | FAIL |

512 players conserved the finite gear but ran at only 0.31 player steps/s with
67,182 pending acknowledgements; it is not a usable capacity result. Vanilla 256
failed its participant audit. Tickless 1024 failed during shutdown with concurrent
structure-cache updates (`StructureCheck` fastutil map rehash); the structure cache is now
guarded (see "What is still shared" in concurrency.md), and that case is excluded from passing
measurements. Raw failed diagnostics
remain in `build/stress-chaos-high-tickless-1024/`.

The initial real-client paired recording is `build/arena-film/pvp-chaos.mp4`
(81 captured frames over 30 seconds). The newer random arena has its own recording
and results in [pvp-random.md](pvp-random.md).
