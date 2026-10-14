# PvP arena benchmark

`tools/stress/pvp_arena.py` runs real offline protocol clients against the
Minecraft 26.3 vanilla server and tickless on fresh worlds, sequentially.
The default is 128 survival players, 15 seconds of warmup and a 30-second
measurement window, repeated twice with server order reversed.

Each player attacks a fixed partner two blocks away once per second and
alternates placing and breaking a private slime block every 250 ms.
Slime blocks break instantly in survival. Knockback resistance and strong
regeneration keep pairs in reach and prevent deaths. All players are close
enough to generate entity tracking and block broadcasts for other players.
Ambient non-player entities are cleared before measurement. Client ticks have deterministic, varied phases within the 50 ms interval.
Missed client ticks are skipped rather than replayed as a catch-up burst.

This is a controlled combat/build workload. It does not simulate navigation,
an entire competitive match, equipment changes, deaths or respawns. Block
drops are disabled on both servers so that the inventory audit has an exact
reference: blocks supplied = successful placements + blocks remaining.

## Measurements

Three latencies use the client's monotonic clock, with raw samples in
`bots.log` and pooled p50/p95/p99 in `arena-result.json`:

- **Combat feedback:** attacker sends an attack, paired victim receives its
  hurt animation. Both clients run in the same JVM and share the clock.
  Each victim has exactly one attacker. There is at most one outstanding
  attack per attacker, so a late response cannot be attributed to a later hit.
- **Block acknowledgement:** action send to cumulative sequence acknowledgement.
  One acknowledgement can acknowledge several actions; each action contributes
  its own latency sample.
- **Block update:** action send to the expected air/non-air transition at that
  player's private build spot. This checks visible feedback separately from
  acknowledgements, which alone do not establish a successful placement.

Bots stop sending measured actions at the window end, then allow two seconds
for responses to drain. Missing responses are reported by coverage and rejected
by the auditor; they are not silently removed to improve tail percentiles.
Combat and update coverage must reach 95% for **every player**. All block
actions must be acknowledged. Each player must sustain at least 90% of the
requested action rate. Window lengths above 30 seconds are refused because
this workload uses a finite supply of blocks in the selected hotbar slot.

Vanilla TPS uses game ticks divided by the wall-clock measurement interval.
Tickless's game clock advances independently of simulation work and is never
used as its TPS. Tickless reports the existing entity/chunk step estimates and
a direct completed-player-step rate: the change in a dedicated committed
player-step counter divided by elapsed wall time and online players. The
existing completed-entity counter excludes players and cannot supply this
measurement. Counter increments wait for transaction commit, so rolled-back
attempts do not inflate TPS. The logged online population must equal the
requested player count.
Only steady worker-stat rows inside the measurement window are used. Short
smoke windows may have too few rows to report tickless simulation throughput.
Server log timestamps have one-second precision, so rates around 20/s are
approximate. These samples do not establish a throughput gain above the
20-step target rate.

Docker CPU samples include **both server and bot JVMs**. The same bot code,
heap, collector and Docker image are used for both servers. Heap and runtime
configuration, server jar SHA-256 hashes, bot source hash and checkout commit
are saved in `build/arena-LABEL-config.json`. Samples within a run are not
independent benchmark repetitions; two pairs do not supply confidence intervals.

## Soundness

Every run must finish and shut down successfully without server errors,
worker failures, command syntax errors, long world-lock waits, bot failures or
premature bot disconnections. Normal shutdown disconnections after the response
drain are allowed.
Every player must pass measured-window damage and placement scoreboard checks
and be alive at full health after the response drain. Successful measured
placements must reach 95% of requested placements for every player. Every
inventory is parsed as SNBT and audited against the total successful placement
counter and the exact 128 blocks supplied. Missing or duplicate checks, inventory
audits and bot results fail the run. The client and server windows must agree
within the precision of the server timestamps.

`pvp_arena_check.py` supplies negative controls for failed/missing audits,
inventory duplication/loss, missing or duplicate feedback, rejected placements,
harness failure and an invalid completed-step population.

The first large run found a `ConcurrentModificationException` in
`ServerWaypointManager.trackWaypoint` while players joined. Its sets and
connection table now share one monitor across all public operations, and
`transmitters()` returns an immutable snapshot. The existing build-time monitor
hooks keep worker waits visible to the deadlock resolver. The failed run is
excluded from the comparison. `WaypointConcurrencyCheck` exercises concurrent
registration/update/removal and retained snapshots; the pre-fix runtime fails
its snapshot control. `MonitorCycleCheck` checks monitor/world-lock cycles.

The minimal bot also now ticks its network connection during joining. Netty can
complete a connection before its channel-active callback runs; in that case the
handshake is queued and needs the connection tick to flush it. Waiting for play
before servicing the connection can leave those clients permanently unjoined.

## Reproduction

```sh
# If generated Minecraft sources are stale, regenerate separately before building:
./gradlew applyPatches
./build.sh installDist
tools/stress/setup_vanilla.sh
python3 tools/stress/pvp_arena_check.py
BUILD=0 SERVER_INSTALL=build/install/minecraft-server \
  tools/check.sh WaypointConcurrencyCheck MonitorCycleCheck
STRESS_HEAP=4g python3 tools/stress/pvp_arena.py run pvp128 \
  --players 128 --warmup 15 --seconds 30 --rounds 2
```

Each run writes to `build/stress-arena-LABEL-SERVER-ROUND/`. Re-audit a saved run:

```sh
python3 tools/stress/pvp_arena.py check build/stress-arena-pvp128-tickless-1 128
```

The aggregate comparison is saved to `build/arena-LABEL-results.json` only
when every run passes. Per-run raw samples, server logs, CPU samples and the
world remain available under ignored `build/`.

## Verified 128-player comparison (2026-10-14)

Minecraft 26.3, Temurin 27 in Docker, ZGC, 4 GB server heap. Two
sequential pairs with order reversed: vanilla/tickless, then tickless/vanilla.
Each measured window lasted approximately 30 seconds after warmup and cleanup.
Tickless is the current checkout with the waypoint fix and committed player counter.

| Server / round | TPS or committed player steps/s | Combat p50 / p95 / p99 (ms) | Block update p50 / p95 / p99 (ms) | Ack p50 / p95 / p99 (ms) |
| --- | ---: | --- | --- | --- |
| vanilla / 1 | 20.00 | 23.76 / 46.12 / 48.63 | 31.54 / 52.70 / 57.96 | 59.13 / 137.82 / 151.97 |
| tickless / 1 | 20.15 | 0.54 / 1.39 / 2.29 | 0.94 / 2.26 / 3.05 | 0.95 / 2.28 / 3.06 |
| tickless / 2 | 20.20 | 0.52 / 1.48 / 2.38 | 0.92 / 2.33 / 3.67 | 0.93 / 2.34 / 3.68 |
| vanilla / 2 | 20.00 | 21.42 / 46.11 / 46.56 | 23.81 / 44.70 / 46.63 | 47.54 / 120.69 / 128.02 |

Both servers maintained approximately the 20-step target. These samples support
a substantial reduction in combat/build feedback latency under this load; they
do **not** establish an increased player capacity or a TPS advantage. Tickless
used 3.96–4.14 CPU cores including clients, versus vanilla's 3.27–3.28.

Every run passed 384 named damage/placement/health checks and 128 exact
inventory audits (1,536 named checks and 512 inventory audits across four runs).
All requested attacks and block actions received their measured feedback: no
pending acknowledgements and 100% combat/update coverage in all four windows.
Totals per window were 3,840–3,841 attacks and 15,360–15,376 block actions.
Nine auditor regression tests passed. The failed setup/calibration attempts
are excluded. Artifact label: `committed128`; aggregate and configuration are
`build/arena-committed128-{results,config}.json`.

The broader regression run also passed: basic conserved exactly 65,536 items;
clock freeze/step/rate checks passed; all five soundness workloads passed
221 named checks and five inventory audits; all 34 focused Java checks
passed, including the waypoint and monitor cycle regressions. Reproduce:

```sh
BUILD=0 SERVER_INSTALL=build/install/minecraft-server STRESS_HEAP=4g \
  ./test.sh 5 basic clock soundness focused
```

Log: `build/arena-regress.log`. Interactive comparison: `build/arena-comparison.html`.
