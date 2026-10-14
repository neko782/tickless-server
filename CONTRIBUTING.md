# Working on tickless

Tickless uses [paperweight](https://github.com/PaperMC/paperweight), the same patching system as
Paper, directly on top of [Mâché](https://github.com/PaperMC/mache) (the decompiled vanilla
server). There is no Bukkit or Paper code underneath; the patches apply to vanilla.

## Layout

| Path | What it is |
| --- | --- |
| `tickless-server/patches/sources/` | One patch per changed Minecraft source file |
| `tickless-server/patches/features/` | Larger multi-file patches (currently none) |
| `tickless-server/src/main/java/` | New classes (world workers, locks, scheduler), not patches |
| `tickless-server/src/buildtools/java/` | `OwnershipTransform`, the bytecode pass run on the packaged jar |
| `tickless-server/src/minecraft/` | Generated: patched Minecraft sources as a git repo (ignored) |
| `tools/` | Focused Java checks, microbenchmarks and the bot client |
| `tools/stress/` | Docker stress harness, world generators, checkers and benchmarks |
| `docs/` | Design notes and full benchmark logs |

## Setting up

You need git and Java 17+ to start Gradle; Gradle downloads JDK 25 for the build itself.

```sh
./gradlew applyPatches        # creates tickless-server/src/minecraft/java
```

## Changing Minecraft code

Edit files under `tickless-server/src/minecraft/java`, then:

```sh
./gradlew fixupSourcePatches  # folds your changes into the "file patches" commit
./gradlew rebuildPatches      # rewrites tickless-server/patches/sources
```

Commit the changed `.patch` files. If `fixupSourcePatches` hits a conflict, do it by hand
inside `tickless-server/src/minecraft/java`: `git add -A`, `git commit --fixup file`,
`git rebase -i --autosquash base`, then `rebuildPatches`.

Larger, self-contained changes can be feature patches: commit them on top of the file-patch
commit in `src/minecraft/java` and run `./gradlew rebuildPatches`; they are written to
`patches/features/`.

New classes go in `tickless-server/src/main/java` (Minecraft packages are fine there).

## Building and testing

```sh
./build.sh                          # applies patches if needed, builds build/install/minecraft-server
./gradlew createPaperclipJar        # single-file server: tickless-server/build/libs/tickless-paperclip-*.jar
./test.sh 5                         # all regression scenarios (needs Docker), 5 s of load each
./test.sh 5 focused                 # only the focused Java checks
tools/check.sh LevelTicksCheck      # one focused check
```

`./test.sh` builds into `build/install-dev`, so it never replaces the jar of a server you are
running from `build/install`.

## Updating Minecraft

Bump `mcVersion` in `gradle.properties` and the `mache(...)` version in
`tickless-server/build.gradle.kts`, run `./gradlew applyPatches`, fix rejects in
`tickless-server/patches/rejected/`, then `rebuildPatches`.

## Fabric development and verification

`./build.sh installFabric` builds the uninstrumented patched game jar, Fabric Loader and the
post-Mixin ownership agent into `build/install/fabric`. The ordinary `jar` task still performs
its ownership pass at build time. Do not apply that pass to the Fabric game jar: mixins must
see the patched classes before ownership instrumentation. Java 27 is used for runtime tests;
the build toolchain still targets Java 25.

`FabricOwnershipAgent` transforms final Knot class definitions. `FabricConflictAudit` compares
them with the patched baseline and audits injection counts. When changing a patched method,
preserve useful vanilla method descriptors and injection sites without restoring a global
world tick. Update file patches with `fixupSourcePatches rebuildPatches`, and commit the patches,
not the generated Minecraft sources.

Fabric API's chunk-unload hook targets the vanilla `lambda$scheduleUnload$0` descriptor and
its `save(chunk)` call, with a local variable named `chunk`. That call site remains inside the
drained unload operation; explicit outer callbacks keep javac from renumbering it. Preserve
this bridge when changing unload scheduling. The probe checks the callback while transactions
are enabled and during shutdown after workers have drained. Background mod startup monitors
wait for player-list publication without blocking the initializing server thread;
`FabricStartupCheck` covers that boundary.

For transaction-aware mod callbacks, annotate both the injected handler and called callback
methods with `net.minecraft.world.level.parallel.ParallelSafe`. This is an explicit opt-in:
shared state must be thread safe, world mutations must follow ownership/undo rules, and external
side effects must be deferred with `WorldTxn.afterCommit`. Ordinary handlers replay their entire
rollbackable transaction on the server thread. After an irrevocable commit, unsafe routing is
rejected. Mixin class-loading/plugin callbacks are outside this world-callback routing boundary.

Run the full Fabric suite:

```sh
GRADLE_USER_HOME="$HOME/.cache/tickless-fabric-gradle" \
GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.workers.max=2" \
  timeout 1800 env TICKLESS_FABRIC=1 STRESS_HEAP=4g ./test.sh 5
```

This includes every plain regression scenario, all five soundness workloads, focused checks,
the real-mod/probe smoke run, and two startup conflict controls. The probe also checks
Fabric chunk-unload events inside the drained server lane. Scenarios run sequentially;
the server heap defaults to 6 GB (`STRESS_HEAP=4g` caps it for shared machines).
Stop Gradle daemons after manual builds with `./gradlew --stop`. On memory-constrained/shared machines, pass
`-Dorg.gradle.jvmargs=-Xmx4g --max-workers=2` to manual Gradle builds. `BUILD=0` reuses a completed
build without skipping any test. `tools/fabric/verify.sh` runs just smoke and conflict controls.

`tools/fabric/test-mods.lock.json` records exact Modrinth artifacts and SHA-512 hashes. The
probe builder compiles against the installed game and actual nested API jars, without changing
the real mods. Downloads, generated probe jars, worlds and logs stay under ignored `build/`.
Never commit Minecraft jars or generated Minecraft source.

For performance runs, preserve a separately built `main` baseline, build plain Tickless into
`build/install/plain`, and use `tools/stress/compare.sh LABEL MOBS WARMUP WINDOW main plain fabric`. Set
`STRESS_MODS=build/fabric-test-mods`; the plain launcher ignores that directory while the Fabric
launcher loads it. Both distributions must use the same source revision, JDK, collector, heap
and workload. Apply patches after switching revisions before building: an existing generated source
directory can contain an older revision even when the outer repository is at the correct commit.
Inspect the packaged classes when results disagree with the source. Run each 24,000-mob configuration
twice, reversing the order on the second comparison, preserve full logs, check exact hopper totals,
and report both entity and chunk rates against main. Comparing Fabric only with the branch's plain
distribution misses regressions shared by both builds.

The idle server loop services an exclusive drain before its next network deadline. Keep the
100 microsecond wait cap while admission is closed; otherwise a finished drain can leave worker
events parked until the next 50 ms network slot. `IdleDrainCheck` covers completing the snapshot
from task polling, while the unload checks cover protecting in-flight writes.
