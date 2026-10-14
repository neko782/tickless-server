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
