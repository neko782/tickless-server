import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;

/** Checks the per-chunk scheduled tick drain used by deadline timers. */
public class LevelTicksCheck {
    private static final BiConsumer<ScheduledTick<String>, Runnable> INLINE = (tick, run) -> run.run();

    private static ScheduledTick<String> tick(final String type, final int x, final long time, final TickPriority priority, final long order) {
        return new ScheduledTick<>(type, new BlockPos(x, 64, 0), time, priority, order);
    }

    private static void expect(final List<String> actual, final String... expected) {
        if (!actual.equals(List.of(expected))) {
            throw new AssertionError("Expected " + List.of(expected) + ", got " + actual);
        }

        actual.clear();
    }

    private static void checkLimits() {
        LevelChunkTicks<String> container = new LevelChunkTicks<>();
        LevelTicks<String> ticks = new LevelTicks<>(key -> true);
        ticks.addContainer(new ChunkPos(0, 0), container);
        for (int i = 0; i < 4; i++) container.schedule(tick("limit-" + i, i, 10, TickPriority.NORMAL, i));
        for (int limit : new int[] {0, -1, Integer.MIN_VALUE}) {
            if (!container.pollDue(10, limit).isEmpty() || container.count() != 4
                || container.willTickThisTick(new BlockPos(0, 64, 0), "limit-0")) {
                throw new AssertionError("Nonpositive drain limit consumed a tick: " + limit);
            }
            if (ticks.runDueTicks(ChunkPos.pack(0, 0), 10, limit, INLINE,
                (pos, type) -> { throw new AssertionError("Nonpositive budget ran a callback"); }) != 0) {
                throw new AssertionError("Nonpositive level drain reported work");
            }
        }
        if (!container.pollDue(9, 4).isEmpty()) throw new AssertionError("Future tick drained early");
        var first = container.pollDue(10, 1);
        if (first.size() != 1 || !first.getFirst().type().equals("limit-0") || container.count() != 3) {
            throw new AssertionError("One-tick budget not respected");
        }
        container.finishedRunning(first.getFirst());
        var rest = container.pollDue(10, 2);
        if (!rest.stream().map(ScheduledTick::type).toList().equals(List.of("limit-1", "limit-2")) || container.count() != 1) {
            throw new AssertionError("Partial drain lost ordering or remaining work");
        }
        rest.forEach(container::finishedRunning);
        var last = container.pollDue(10, Integer.MAX_VALUE);
        if (last.size() != 1 || container.count() != 0) {
            throw new AssertionError("Final drain lost a tick");
        }
        last.forEach(container::finishedRunning);
    }

    private static void checkDeferredAndFailure() {
        LevelTicks<String> ticks = new LevelTicks<>(key -> true);
        long chunk = ChunkPos.pack(0, 0);
        ticks.addContainer(new ChunkPos(0, 0), new LevelChunkTicks<>());
        ticks.schedule(tick("deferred", 1, 10, TickPriority.NORMAL, 1));
        List<Runnable> queued = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        if (ticks.runDueTicks(chunk, 10, 1, (tick, run) -> queued.add(run), (pos, type) -> ran.add(type)) != 1
            || !ran.isEmpty() || ticks.hasScheduledTick(new BlockPos(1, 64, 0), "deferred")
            || !ticks.willTickThisTick(new BlockPos(1, 64, 0), "deferred")) {
            throw new AssertionError("Deferred tick lifecycle differs");
        }
        queued.getFirst().run();
        expect(ran, "deferred");
        if (ticks.willTickThisTick(new BlockPos(1, 64, 0), "deferred")) throw new AssertionError("Deferred tick never finished");

        ticks.schedule(tick("throws", 2, 10, TickPriority.NORMAL, 2));
        RuntimeException failure = new RuntimeException("callback failure");
        try {
            ticks.runDueTicks(chunk, 10, 1, INLINE, (pos, type) -> { throw failure; });
            throw new AssertionError("Callback exception swallowed");
        } catch (RuntimeException actual) {
            if (actual != failure) throw new AssertionError("Callback exception replaced", actual);
        }
        if (ticks.willTickThisTick(new BlockPos(2, 64, 0), "throws")) throw new AssertionError("Failed callback retained running state");

        // A self-reschedule is separate from the running tick, and cannot run in this batch.
        ticks.schedule(tick("repeat", 3, 10, TickPriority.NORMAL, 3));
        ticks.runDueTicks(chunk, 10, 10, INLINE, (pos, type) -> {
            ran.add(type);
            ticks.schedule(tick(type, 3, 10, TickPriority.NORMAL, 4));
        });
        expect(ran, "repeat");
        if (!ticks.hasScheduledTick(new BlockPos(3, 64, 0), "repeat") || ticks.willTickThisTick(new BlockPos(3, 64, 0), "repeat")) {
            throw new AssertionError("Finishing removed the self-rescheduled tick");
        }
        ticks.runDueTicks(chunk, 10, 10, INLINE, (pos, type) -> ran.add(type));
        expect(ran, "repeat");
    }

    private static void checkListenerLifecycle() {
        LevelTicks<String> ticks = new LevelTicks<>(key -> true);
        ChunkPos pos = new ChunkPos(-2, -1);
        LevelChunkTicks<String> old = new LevelChunkTicks<>();
        ticks.addContainer(pos, old);
        AtomicInteger notifications = new AtomicInteger();
        ticks.setOnTickAdded((key, tick) -> {
            if (key != pos.pack()) throw new AssertionError("Wrong listener chunk key");
            notifications.incrementAndGet();
        });
        var scheduled = new ScheduledTick<>("notify", new BlockPos(-32, 64, -16), 10, 1);
        ticks.schedule(scheduled);
        old.schedule(new ScheduledTick<>(scheduled.type(), scheduled.pos(), 1, 2));
        if (notifications.get() != 1) throw new AssertionError("Duplicate tick notified the timer");

        LevelChunkTicks<String> replacement = new LevelChunkTicks<>();
        ticks.addContainer(pos, replacement);
        old.schedule(new ScheduledTick<>("obsolete", new BlockPos(-31, 64, -16), 10, 3));
        if (notifications.get() != 1) throw new AssertionError("Replaced container still notifies the scheduler");
        replacement.schedule(new ScheduledTick<>("replacement", new BlockPos(-30, 64, -16), 10, 4));
        if (notifications.get() != 2) throw new AssertionError("Replacement container has no listener");
        ticks.addContainer(pos, replacement);
        replacement.schedule(new ScheduledTick<>("same-container", new BlockPos(-29, 64, -16), 10, 5));
        if (notifications.get() != 3) throw new AssertionError("Re-registering a container detached its listener");
        ticks.setOnTickAdded((key, tick) -> notifications.addAndGet(10));
        replacement.schedule(new ScheduledTick<>("new-listener", new BlockPos(-28, 64, -16), 10, 6));
        if (notifications.get() != 13) throw new AssertionError("Listener replacement did not reach existing containers");
        ticks.removeContainer(pos);
        replacement.schedule(new ScheduledTick<>("removed", new BlockPos(-27, 64, -16), 10, 7));
        if (notifications.get() != 13) throw new AssertionError("Removed container retained its listener");
        ticks.addContainer(pos, replacement);
        ticks.setOnTickAdded(null);
        replacement.schedule(new ScheduledTick<>("disabled", new BlockPos(-26, 64, -16), 10, 8));
        if (notifications.get() != 13) throw new AssertionError("Disabled listener still called");
    }

    private static void checkCopyAndPersistence() {
        LevelTicks<String> ticks = new LevelTicks<>(key -> true);
        LevelChunkTicks<String> source = new LevelChunkTicks<>();
        LevelChunkTicks<String> destination = new LevelChunkTicks<>();
        ticks.addContainer(new ChunkPos(-1, 0), source);
        ticks.addContainer(new ChunkPos(0, 0), destination);
        ticks.schedule(tick("first", -2, 12, TickPriority.HIGH, 8));
        ticks.schedule(tick("second", -1, 12, TickPriority.NORMAL, 3));
        ticks.schedule(tick("outside", -3, 12, TickPriority.NORMAL, 7));
        BoundingBox area = new BoundingBox(-2, 64, 0, -1, 64, 0);
        ticks.copyArea(area, new Vec3i(16, 0, 0));
        ticks.copyArea(area, new Vec3i(16, 0, 0));
        if (ticks.count() != 5) throw new AssertionError("Copy changed source or duplicated destination ticks");
        var copied = destination.getAll().sorted(ScheduledTick.DRAIN_ORDER).toList();
        if (copied.size() != 2 || copied.get(0).subTickOrder() != 14 || copied.get(1).subTickOrder() != 9
            || copied.get(0).triggerTick() != 12 || copied.get(0).priority() != TickPriority.HIGH
            || !copied.get(0).pos().equals(new BlockPos(14, 64, 0))) {
            throw new AssertionError("Copy lost position, deadline, priority or relative sub-tick order");
        }
        ticks.clearArea(area);
        if (source.count() != 1 || !source.hasScheduledTick(new BlockPos(-3, 64, 0), "outside")) {
            throw new AssertionError("Inclusive clear area removed outside work");
        }

        List<SavedTick<String>> saved = destination.pack(10);
        if (!saved.stream().map(SavedTick::type).toList().equals(List.of("second", "first"))
            || saved.stream().anyMatch(tick -> tick.delay() != 2)) {
            throw new AssertionError("Save lost sub-tick ordering or relative delay");
        }
        LevelChunkTicks<String> restored = new LevelChunkTicks<>(saved);
        if (restored.count() != 2 || !restored.hasScheduledTick(new BlockPos(14, 64, 0), "first")
            || !restored.pack(99).equals(saved)) throw new AssertionError("Pending saved ticks lost");
        restored.schedule(tick("first", 14, 100, TickPriority.NORMAL, 0));
        if (restored.count() != 2) throw new AssertionError("Pending duplicate accepted");
        restored.unpack(100);
        restored.unpack(100);
        if (restored.count() != 2 || restored.hasDueTick(101)) throw new AssertionError("Unpack duplicated work or changed delay");
        var due = restored.pollDue(102, 10);
        if (!due.stream().map(ScheduledTick::type).toList().equals(List.of("first", "second"))) {
            throw new AssertionError("Reload changed tick priority order");
        }
        due.forEach(restored::finishedRunning);

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 64, 0);
        var immutable = new ScheduledTick<>("immutable", mutable, 1, 1);
        mutable.set(15, 64, 0);
        if (!immutable.pos().equals(new BlockPos(1, 64, 0))) throw new AssertionError("Caller mutated scheduled position");
    }

    private static void checkConcurrentScheduling() throws Exception {
        LevelTicks<String> ticks = new LevelTicks<>(key -> true);
        LevelChunkTicks<String> container = new LevelChunkTicks<>();
        ticks.addContainer(new ChunkPos(-1, -1), container);
        AtomicInteger added = new AtomicInteger();
        ticks.setOnTickAdded((key, tick) -> added.incrementAndGet());
        List<ScheduledTick<String>> work = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            work.add(new ScheduledTick<>("concurrent-" + i, new BlockPos(-16 + i % 16, 64, -16 + i / 16), 5, i));
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) jobs.add(pool.submit(() -> {
                start.await();
                for (int round = 0; round < 20; round++) {
                    for (var tick : work) ticks.schedule(tick);
                }
                return null;
            }));
            start.countDown();
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            if (added.get() != 256 || ticks.count() != 256) throw new AssertionError("Competing producers duplicated ticks or notifications");

            AtomicInteger[] visits = new AtomicInteger[256];
            for (int i = 0; i < visits.length; i++) visits[i] = new AtomicInteger();
            CountDownLatch draining = new CountDownLatch(8);
            BiConsumer<ScheduledTick<String>, Runnable> concurrentRunner = (tick, run) -> {
                // Hold each drainer's first callback until all eight have acquired a batch.
                draining.countDown();
                try {
                    if (!draining.await(10, TimeUnit.SECONDS)) throw new AssertionError("Drains did not overlap");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted concurrent drain", error);
                }
                run.run();
            };
            jobs.clear();
            for (int worker = 0; worker < 8; worker++) jobs.add(pool.submit(() -> {
                while (ticks.runDueTicks(ChunkPos.pack(-1, -1), 5, 7, concurrentRunner, (pos, type) -> {
                    if (!ticks.willTickThisTick(pos, type)) throw new AssertionError("Concurrent callback not marked running");
                    visits[Integer.parseInt(type.substring("concurrent-".length()))].incrementAndGet();
                }) != 0) {}
            }));
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            if (ticks.count() != 0) throw new AssertionError("Concurrent drains left scheduled ticks");
            for (int i = 0; i < visits.length; i++) {
                if (visits[i].get() != 1 || ticks.willTickThisTick(work.get(i).pos(), work.get(i).type())) {
                    throw new AssertionError("Concurrent drains lost, duplicated or retained tick " + i);
                }
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        checkLimits();
        checkDeferredAndFailure();
        checkListenerLifecycle();
        checkCopyAndPersistence();
        checkConcurrentScheduling();
        AtomicBoolean tickable = new AtomicBoolean(false);
        long paused = ChunkPos.pack(1, 0);
        LevelTicks<String> ticks = new LevelTicks<>(pos -> pos != paused || tickable.get());
        ticks.addContainer(new ChunkPos(0, 0), new LevelChunkTicks<>());
        ticks.addContainer(new ChunkPos(1, 0), new LevelChunkTicks<>());
        List<String> ran = new ArrayList<>();
        long chunk = ChunkPos.pack(0, 0);

        // Priority and sub-tick order decide the order within a chunk, as in vanilla.
        ticks.schedule(tick("late", 1, 5, TickPriority.NORMAL, 1));
        ticks.schedule(tick("low", 2, 2, TickPriority.LOW, 2));
        ticks.schedule(tick("normal", 3, 2, TickPriority.NORMAL, 3));
        ticks.schedule(tick("high", 4, 2, TickPriority.HIGH, 4));
        ticks.runDueTicks(chunk, 1, 65536, INLINE, (pos, type) -> ran.add(type));
        expect(ran);
        ticks.runDueTicks(chunk, 2, 65536, INLINE, (pos, type) -> ran.add(type));
        expect(ran, "high", "normal", "low");

        // A tick counts as running this tick until its own callback returns.
        ticks.schedule(tick("self", 5, 3, TickPriority.NORMAL, 5));
        ticks.runDueTicks(chunk, 3, 65536, INLINE, (pos, type) -> {
            if (!ticks.willTickThisTick(pos, type)) {
                throw new AssertionError("Running tick not reported by willTickThisTick");
            }

            ran.add(type);
        });
        expect(ran, "self");
        if (ticks.willTickThisTick(new BlockPos(5, 64, 0), "self")) {
            throw new AssertionError("Finished tick still reported as running");
        }

        // Ticks scheduled while a drain runs wait for the next one, even when already due.
        ticks.schedule(tick("chain", 6, 4, TickPriority.NORMAL, 6));
        ticks.runDueTicks(chunk, 4, 65536, INLINE, (pos, type) -> {
            ran.add(type);
            ticks.schedule(tick("chained", 7, 4, TickPriority.NORMAL, 7));
        });
        expect(ran, "chain");
        ticks.runDueTicks(chunk, 5, 65536, INLINE, (pos, type) -> ran.add(type));
        expect(ran, "chained", "late");

        // Chunks that cannot tick yet keep their ticks.
        ticks.schedule(tick("paused", 16, 1, TickPriority.NORMAL, 8));
        ticks.runDueTicks(paused, 6, 65536, INLINE, (pos, type) -> ran.add(type));
        expect(ran);
        tickable.set(true);
        ticks.runDueTicks(paused, 6, 65536, INLINE, (pos, type) -> ran.add(type));
        expect(ran, "paused");

        // Clearing an area and removing a chunk drop their ticks.
        ticks.schedule(tick("cleared", 8, 10, TickPriority.NORMAL, 9));
        ticks.schedule(tick("kept", 20, 10, TickPriority.NORMAL, 10));
        ticks.clearArea(new BoundingBox(0, 0, 0, 15, 255, 0));
        ticks.runDueTicks(chunk, 10, 65536, INLINE, (pos, type) -> ran.add(type));
        expect(ran);
        ticks.removeContainer(new ChunkPos(1, 0));
        ticks.runDueTicks(paused, 10, 65536, INLINE, (pos, type) -> ran.add(type));
        expect(ran);
        System.out.println("LevelTicksCheck passed: drain budgets, deferred/failing callbacks, self-rescheduling, listener lifecycle, copy/clear, save/reload, competing producers/drains, ordering and paused chunks");
    }
}
