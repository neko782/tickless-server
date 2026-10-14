# Design notes and lab logs

These notes were written during development, before the code moved to paperweight patches.
Commit hashes in them (`4d398a1`, `ae435af`, ...) refer to that earlier, unpublished history, and
`./build.sh`/`src/main/java` paths refer to the old single-tree layout: Minecraft sources now live
in `tickless-server/src/minecraft/java` after `./gradlew applyPatches`. The measurements are
single samples from the dates given; the current headline numbers are in the top-level README.

- [concurrency.md](concurrency.md): world workers, locks, rollbacks, deliberate races, known gaps
- [benchmarks.md](benchmarks.md): vanilla, Lithium, lag machines, heavy workloads, scaling
- [extended-benchmarks.md](extended-benchmarks.md): AI, container, piston and falling-block workloads
- [mod-optimizations.md](mod-optimizations.md): Lithium and Paper inspired single-thread optimizations
- [soundness.md](soundness.md): lifecycle and conservation edge-case tests
