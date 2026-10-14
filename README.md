# Tickless

A Minecraft Java Edition **26.3** server that has no single-threaded game tick. Every entity,
chunk, block entity and scheduled tick runs on its own timer, as a small transaction on a pool of
virtual-thread world workers, and locks only the blocks and entities it touches. A busy farm slows
down only itself, and a big world uses every core instead of one.

The target is a server a normal player cannot tell apart from vanilla, not a bit-exact one: see
[What is different from vanilla](#what-is-different-from-vanilla).

Tickless is a set of patches on the vanilla server, built with Paper's
[paperweight](https://github.com/PaperMC/paperweight) on top of
[Mâché](https://github.com/PaperMC/mache). It is **not** a Paper fork and has no Bukkit/Paper
plugin API. Fabric server mods are supported by the separate Fabric distribution.

## Numbers

Same world, same script, fresh world per server: 529 closed hopper rings holding 135,424 items,
552 observer-driven piston clocks, a wheat farm at `random_tick_speed 100`, and a pen of mixed
mobs (cows, sheep, pigs, villagers, zombies, chickens) in 144 force-loaded chunks. 15 s warmup, then
a 30 s measured window. Ryzen 9 8940HX (16 cores / 32 threads), Temurin 25, ZGC, 6 GB heap,
2026-10-14. Paper is build 26.3-181 with its default configuration.

**24,000 mobs**

| Server | Simulation rate | vs vanilla | CPU cores used |
| --- | ---: | ---: | ---: |
| Vanilla 26.3 | 0.74 TPS | 1× | 1.1 |
| Paper 26.3 | 1.13 TPS | 1.5× | 1.1 |
| **Tickless** | **19.4 steps/s** | **26×** | 25.5 |

**8,000 mobs**

| Server | Simulation rate | vs vanilla | CPU cores used |
| --- | ---: | ---: | ---: |
| Vanilla 26.3 | 5.0 TPS | 1× | 1.1 |
| Paper 26.3 | 7.3 TPS | 1.5× | 1.0 |
| Paper 26.3, entity activation range off | 6.8 TPS | 1.4× | 1.0 |
| **Tickless** | **20.0 steps/s** (full speed) | **≥4×** | 6.5 |

Every run kept all 135,424 hopper items and logged no errors. Single samples, not confidence
intervals.

How to read "steps/s": tickless has no global tick, so its game clock always runs at 20/s. The
rate shown is the share of requested per-entity and per-chunk steps that actually ran (the lower
of the two), which is the tickless equivalent of TPS: 20 means nothing fell behind. Vanilla and
Paper rates are game ticks completed in the window. Paper's entity activation range skips most
AI for mobs far from players, and these worlds have no players, so it was also measured with
activation range off at 8,000 mobs.

Tickless uses more CPU to do more work: it runs the whole world at full rate where the others fall
to a fraction of a tick per second.

Reproduce:

```sh
./build.sh
tools/stress/setup_vanilla.sh && tools/stress/setup_paper.sh
tools/stress/compare.sh m24k 24000 15 30 vanilla paper tickless
tools/stress/compare.sh m8k 8000 15 30
```

Earlier measurements against vanilla on heavy non-mob workloads (details and other workloads,
including a Fabric + Lithium comparison, in [docs/benchmarks.md](docs/benchmarks.md)):

| Workload | Vanilla | Tickless |
| --- | --- | --- |
| 32×32×32 TNT cube chain reaction | 80 s | 11–12 s |
| 160×160 sheet of sand falling at once (102,400 blocks) | 38 s | 5–6 s |
| Lag machines: 18,000 items, 11,880 minecarts, 9,000 furnaces, fluid and redstone clocks | 2.1 TPS | 20 steps/s |

## How it works

- **No tick loop for the world.** The server thread keeps what is global by nature (game clock,
  chunk loading and saving, networking, entity tracking, commands). Everything else is an
  independent timer: one per ticking entity, chunk, block entity and chunk of scheduled ticks,
  kept in a 64-slot phase wheel. A timer that falls behind skips missed steps instead of
  queueing them.
- **World workers on virtual threads.** Each event runs as its own transaction on a carrier
  pool (cores − 1 by default). An event waiting on a lock or a chunk load unmounts and its core
  runs something else.
- **Fine-grained locks.** Block writes lock the block (65,536 hashed locks per level); nearby
  reads lock within a small radius; entity updates lock the entity and its passengers. Writes to
  entity and block entity fields are checked at build time: a bytecode pass over the packaged
  jar (`OwnershipTransform`) makes them take their owner's lock.
- **Deadlocks are rolled back, not avoided.** A wait-graph walker finds cycles and aborts and
  replays the youngest transaction, using an undo log of its field and block writes.
- **Deliberate races where nobody can see them.** Entity pushing, packet dirty flags and some AI
  reads run without locks; a lost push nudge is invisible, a duplicated item is not.
- **Idle block entities sleep.** A furnace that cannot smelt or a hopper locked by redstone stops
  ticking until its inventory or block state changes. 16,000 of them went from 321,000 events/s
  to about 1,100 events/s.
- **Plus single-threaded optimizations** inspired by Lithium and Paper: cached entity-section
  bounding boxes, lazy collision contexts, AI goal and behavior gating without streams, POI
  lookup caches and moving-piston shape caches. See [docs/mod-optimizations.md](docs/mod-optimizations.md).

The full design is in [docs/concurrency.md](docs/concurrency.md).

## What is different from vanilla

- Same-tick ordering between events in different places is not deterministic. Redstone that
  depends on exact update order across chunk borders can behave differently.
- Overloaded areas slow down locally: a chunk or entity that cannot keep up skips steps, while
  the game clock and the rest of the world keep going at 20 per second.
- Entity pushes can occasionally be lost under contention, and RNG is per-thread, so seeds do
  not reproduce vanilla's mob behaviour.
- `/tick sprint` advances the clock faster than busy chunks can follow.
- Command blocks run on the worker that ticks them, not in a world pause.
- Player actions are acknowledged about one tick later than on vanilla (≈38 ms vs ≈21 ms p50
  in a 20-bot test).

What is checked: items in hopper rings, dropper/dispenser graphs, hopper minecarts and copper
golems are conserved exactly under load; no duplication or loss in container moves, passenger
stacks, chunk reloads, frozen entities or scheduled ticks. See [docs/soundness.md](docs/soundness.md).

## Known issues

- **Run on Java 27.** On Java 25 (Temurin 25.0.4.1), the JVM's garbage collector occasionally
  crashed (G1 and ZGC) during 100-fireball storms and 60,000-entity summons with virtual threads.
  The build still targets Java 25, but run it on Java 27, where those workloads have not crashed.
- No Bukkit/Paper plugin API. Datapacks and compatible Fabric server mods work.

Do not run this on a world you care about without backups.

## Building

You need git and Java 17+ to start Gradle; the build downloads JDK 25 itself.

```sh
./build.sh                        # applies the patches and builds build/install/minecraft-server
./gradlew createPaperclipJar      # single runnable jar in tickless-server/build/libs/
```

Run the server with Java 27 (Java 25 also works, but see Known issues):

```sh
java -XX:+UseZGC -Xmx8G -jar tickless-server/build/libs/tickless-paperclip-26.3-SNAPSHOT.jar --nogui
```

or with Docker: `./run.sh` (world files go into `run/`). Accept Minecraft's EULA in `eula.txt`
first.

Options (`-D...`): `tickless.workers=N` (carrier threads, default cores − 1),
`tickless.virtualThreads=false` (fixed platform-thread pool), `tickless.statsIntervalSeconds=N`
(log worker statistics).

## Fabric mods

Build the Fabric distribution and put **26.3 server-side Fabric mods** in the `mods/`
directory of your server working directory:

```sh
./build.sh installFabric
./gradlew --stop
mkdir -p run-fabric/mods
# Copy Fabric API for 26.3 and your server mods into run-fabric/mods/.
cd run-fabric
printf 'eula=true\n' > eula.txt    # only after accepting Minecraft's EULA
JAVA_OPTS='-Xmx4g -XX:+UseZGC' ../build/install/fabric/bin/minecraft-server --nogui
```

Use Java 27, as for the plain distribution. Set `JAVA_HOME` if it is not your default Java.
Copy the entire `build/install/fabric/` directory when installing on another machine; the
launcher needs its `lib/` and `agent/` directories. Mods and world files belong in the working
directory. The distribution includes Fabric Loader 0.19.5; Fabric API is a mod you install
separately. `installFabric` does not install the verification probe or download test mods.

This launches the patched server through Fabric's Knot loader. Minecraft 26.3 uses official,
unobfuscated names ([Fabric's mapping documentation](https://docs.fabricmc.net/develop/porting/mappings/)),
so 26.3 mods need no intermediary remapping. Mixins see the patched bytecode before an ownership
agent transforms the final classes, including injected field writes and mod-defined entities.
All Tickless timers and parallel world workers remain enabled.

Ordinary mod callbacks reached in a rollbackable world transaction cause that transaction to
roll back and replay on the server thread before executing mod code. This protects ordinary
mod state from concurrent entity callbacks; a mod that hooks every entity tick can therefore
reduce throughput substantially. It does not serialize unrelated worker events. Mods spawning
their own threads must still obey Minecraft's world-access rules. Native code, reflection and
unsafe access cannot be made safe by a field-write transformer.

The launcher refuses overwritten original methods in Tickless-patched classes, removal of
scheduler/ownership operations, and optional injections that match zero callbacks in patched
classes. Startup reports **`Tickless Fabric mixin conflict`**, the target and conflicting
operation/injection, rather than accepting missing behavior. A callback first reached after an
irrevocable partial commit also stops the server with its callback name and an explanation.
Optimization mods that replace vanilla ticking internals can conflict; support is determined
per mod/version, not promised for every Fabric mod. Client-only mods are outside this server's
scope. Mod mixins depending on exact vanilla loop structure may need adaptations.

The tested unmodified Modrinth releases are Fabric API **0.162.0+26.3**, **Chunky 1.5.3**,
**LuckPerms 5.5.85** and **spark 1.10.187**. Verification exercises chunk pregeneration,
permission writes/reads, TPS/tick-duration output, and an additional test mod with tick, damage, death
and block-change mixins plus Fabric damage/death and chunk-unload events. See
[Fabric performance and verification](docs/benchmarks.md#fabric-mod-support) for results against
main, the production mod set and the diagnostic probe. The full verification command is in
[CONTRIBUTING.md](CONTRIBUTING.md#fabric-development-and-verification).
Two 24k comparisons retain plain entity throughput within 0.2% of the reviewed main build;
Fabric with the production mod set has about 4.3% lower entity throughput. The full Fabric suite
passed 32 focused checks, 221 soundness checks and five inventory audits.

## Developing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the patch workflow. In short: `./gradlew applyPatches`,
edit `tickless-server/src/minecraft/java`, `./gradlew fixupSourcePatches rebuildPatches`.

Tests need Docker:

```sh
./test.sh 5            # all regression scenarios plus focused Java checks
./test.sh 5 focused    # focused checks only
```

## Licensing

Tickless's own code (the patches, new classes, build scripts, tools and docs) is released into
the public domain under [the Unlicense](LICENSE).

This repository contains no Minecraft code or binaries: the patches are applied at build time
to sources decompiled from Mojang's official server jar by Mâché, and the build downloads that
jar from Mojang. Use of Minecraft is subject to the
[Minecraft EULA](https://www.minecraft.net/en-us/eula). Mâché is LGPL-3.0-only.
