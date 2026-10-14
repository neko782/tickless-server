package net.minecraft.server.level;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.parallel.WorldLocks;
import net.minecraft.world.level.parallel.WorldTxn;
import net.minecraft.world.level.parallel.WorldWorkers;
import sun.misc.Unsafe;

/** A queued ticker admitted after a save/unload gate must recheck the timer's lifetime. */
public class QueuedChunkTimerCheck {
    static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe)singleton.get(null);
        ServerLevel level = (ServerLevel)unsafe.allocateInstance(ServerLevel.class);
        field(level, net.minecraft.world.level.Level.class, "worldLocks", new WorldLocks());
        LevelEventScheduler scheduler = (LevelEventScheduler)unsafe.allocateInstance(LevelEventScheduler.class);
        try (WorldWorkers workers = new WorldWorkers(2)) {
            field(scheduler, LevelEventScheduler.class, "level", level);
            field(scheduler, LevelEventScheduler.class, "workers", workers);
            var batch = new ArrayList<>();
            field(scheduler, LevelEventScheduler.class, "blockEntityBatch", batch);
            AtomicInteger ticks = new AtomicInteger();
            TickingBlockEntity ticker = new TickingBlockEntity() {
                public void tick() { ticks.incrementAndGet(); }
                public boolean isRemoved() { return false; }
                public BlockPos getPos() { return BlockPos.ZERO; }
                public String getType() { return "queued-lifecycle-check"; }
            };
            var timerType = Class.forName("net.minecraft.server.level.LevelEventScheduler$BlockEntityTimer");
            var constructor = timerType.getDeclaredConstructor(LevelEventScheduler.class, BlockPos.class, TickingBlockEntity.class, long.class);
            constructor.setAccessible(true);
            var timer = (PhaseWheel.Timer)constructor.newInstance(scheduler, BlockPos.ZERO, ticker, 0L);
            var flush = LevelEventScheduler.class.getDeclaredMethod("flushBlockEntityBatch");
            flush.setAccessible(true);
            WorldTxn.enable(Thread.currentThread(), () -> {});
            try {
                WorldTxn.requestExclusive(() -> {
                    timer.active = true;
                    batch.add(timer);
                    try { flush.invoke(scheduler); }
                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                    // Model the main thread demoting/unloading the chunk before the queued
                    // event can pass the still-closed gate. Its ticker wrapper can be retained.
                    timer.active = false;
                });
                if (!WorldTxn.pollExclusive()) throw new AssertionError("Exclusive lifecycle action did not run");
                long end = System.nanoTime() + 2_000_000_000L;
                while (!workers.isQuiescent() && System.nanoTime() < end) Thread.sleep(1);
                if (!workers.isQuiescent()) throw new AssertionError("Queued ticker failed to drain");
                if (ticks.get() != 0 || workers.failures.sum() != 0) throw new AssertionError("Inactive queued ticker still mutated its old chunk: " + ticks.get() + " ticks");
                System.out.println("QueuedChunkTimerCheck passed: queued retained ticker skipped after chunk lifetime ended at the gate");
            } finally { WorldTxn.disable(); }
        }
    }
}
