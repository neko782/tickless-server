package net.minecraft.server.level;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import sun.misc.Unsafe;

/** Retained tickers must resume after a chunk stops ticking; registration stays idempotent. */
public class BlockEntityTimerLifecycleCheck {
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = LevelEventScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void main(String[] args) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe)singleton.get(null);
        // Exercise the real registry and wheel without starting a server. The
        // wheel's very long period prevents callbacks into the absent world.
        LevelEventScheduler scheduler = (LevelEventScheduler)unsafe.allocateInstance(LevelEventScheduler.class);
        var timers = new ConcurrentHashMap<BlockPos, PhaseWheel.Timer>();
        var chunks = new ConcurrentHashMap<Long, Object>();
        var byChunk = new ConcurrentHashMap<Long, java.util.Set<?>>();
        set(scheduler, "blockEntityTimers", timers);
        set(scheduler, "tickingChunks", chunks);
        var scheduled = new ConcurrentHashMap<Long, Object>();
        set(scheduler, "scheduledChunks", scheduled);
        set(scheduler, "blockEntitiesByChunk", byChunk);
        PhaseWheel wheel = new PhaseWheel("lifecycle-check", () -> Long.MAX_VALUE, () -> {});
        set(scheduler, "wheel", wheel);
        BlockPos pos = new BlockPos(-17, 200, 15);
        long chunk = ChunkPos.pack(pos);
        var constructor = Class.forName("net.minecraft.server.level.LevelEventScheduler$ChunkTimer")
            .getDeclaredConstructor(LevelEventScheduler.class, LevelChunk.class);
        constructor.setAccessible(true);
        Object chunkTimer = constructor.newInstance(scheduler, null);
        LevelChunk unpublished = (LevelChunk)unsafe.allocateInstance(LevelChunk.class);
        Field chunkPos = ChunkAccess.class.getDeclaredField("chunkPos");
        chunkPos.setAccessible(true);
        chunkPos.set(unpublished, ChunkPos.unpack(chunk));
        Field wrappers = LevelChunk.class.getDeclaredField("tickersInLevel");
        wrappers.setAccessible(true);
        wrappers.set(unpublished, new ConcurrentHashMap<>());
        var scheduledConstructor = Class.forName("net.minecraft.server.level.LevelEventScheduler$ScheduledChunkTimer")
            .getDeclaredConstructor(LevelChunk.class);
        scheduledConstructor.setAccessible(true);
        TickingBlockEntity ticker = new TickingBlockEntity() {
            public void tick() { throw new AssertionError("Test wheel must not fire"); }
            public boolean isRemoved() { return false; }
            public BlockPos getPos() { return pos; }
            public String getType() { return "test"; }
        };
        try {
            scheduler.onBlockEntityTickerAdded(ticker);
            PhaseWheel.Timer retained = timers.get(pos);
            if (retained.active) throw new AssertionError("Ticker activated before timers started");
            set(scheduler, "timersStarted", true);
            scheduler.onBlockEntityTickerAdded(ticker);
            if (retained.active) throw new AssertionError("Ticker activated outside a ticking chunk");
            // Suppress clock scheduling; only registry publication is under test.
            chunks.put(chunk, chunkTimer);
            scheduled.put(chunk, scheduledConstructor.newInstance(unpublished));
            scheduler.onChunkTickingStart(unpublished);
            if (!retained.active) throw new AssertionError("Promotion missed the unpublished wrapper's registered timer");
            scheduler.onChunkTickingEnd(chunk);

            for (int i = 0; i < 1000; i++) {
                chunks.put(chunk, chunkTimer);
                scheduler.onBlockEntityTickerAdded(ticker);
                scheduler.onBlockEntityTickerAdded(ticker);
                if (!retained.active || timers.get(pos) != retained || timers.size() != 1 || byChunk.get(chunk).size() != 1) {
                    throw new AssertionError("Retained ticker did not resume exactly once at cycle " + i);
                }
                scheduler.onChunkTickingEnd(chunk);
                if (retained.active) throw new AssertionError("Ticker stayed active after ticking ended");
            }

            scheduler.onBlockEntityTickerRemoved(pos);
            if (!timers.isEmpty() || !byChunk.isEmpty() || retained.active) {
                throw new AssertionError("Removed ticker retained registry membership");
            }
            chunks.put(chunk, chunkTimer);
            scheduler.onBlockEntityTickerAdded(ticker);
            if (timers.get(pos) == retained || !timers.get(pos).active) {
                throw new AssertionError("Replacement ticker was not registered and started");
            }
            scheduler.onBlockEntityTickerRemoved(pos);
            set(scheduler, "timersStarted", false);
            scheduler.onBlockEntityTickerAdded(ticker);
            if (timers.get(pos).active) throw new AssertionError("Ticker resumed after producers stopped");
            scheduler.onBlockEntityTickerRemoved(pos);
            System.out.println("BlockEntityTimerLifecycleCheck passed: 1000 retained-ticker resumes, idempotent registration, removal/replacement and stopped producers");
        } finally {
            wheel.stop();
        }
    }
}
