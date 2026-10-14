package net.minecraft.server.level;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import sun.misc.Unsafe;

/** A stale visible holder snapshot must not lose a new chunk's ticking promotion. */
public class ChunkTickerPromotionCheck {
    private static void checkPublication(java.util.function.Supplier<FullChunkStatus> statusGetter, FullChunkStatus before, FullChunkStatus after, Runnable change) throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        FullChunkStatus[] observed = new FullChunkStatus[1];
        Thread reader = Thread.ofPlatform().daemon().start(() -> {
            started.countDown();
            FullChunkStatus status;
            while ((status = statusGetter.get()) == before) {}
            observed[0] = status;
        });
        if (!started.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Status reader did not start");
        // Let the getter become hot before changing status, without publishing through another
        // field read by the worker. Status changes themselves must reach existing readers.
        Thread.sleep(1000);
        change.run();
        reader.join(5000);
        if (reader.isAlive() || observed[0] != after) throw new AssertionError("Worker retained stale chunk status " + before);
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe)singleton.get(null);
        ChunkPos pos = new ChunkPos(512, 512);
        LevelChunk chunk = (LevelChunk)unsafe.allocateInstance(LevelChunk.class);
        set(chunk, ChunkAccess.class, "chunkPos", pos);
        set(chunk, LevelChunk.class, "tickersInLevel", new ConcurrentHashMap<>());
        ChunkHolder current = (ChunkHolder)unsafe.allocateInstance(ChunkHolder.class);
        ChunkHolder obsolete = (ChunkHolder)unsafe.allocateInstance(ChunkHolder.class);
        set(current, GenerationChunkHolder.class, "pos", pos);
        set(obsolete, GenerationChunkHolder.class, "pos", pos);
        set(current, ChunkHolder.class, "tickingChunkFuture", CompletableFuture.completedFuture(ChunkResult.of(chunk)));
        set(obsolete, ChunkHolder.class, "tickingChunkFuture", CompletableFuture.completedFuture(ChunkHolder.UNLOADED_LEVEL_CHUNK));
        current.setTicketLevel(ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING));
        checkPublication(chunk::getFullStatus, FullChunkStatus.FULL, FullChunkStatus.BLOCK_TICKING, () -> chunk.setFullStatus(current::getFullStatus));
        checkPublication(current::getFullStatus, FullChunkStatus.BLOCK_TICKING, FullChunkStatus.ENTITY_TICKING,
            () -> current.setTicketLevel(ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)));

        LevelEventScheduler scheduler = (LevelEventScheduler)unsafe.allocateInstance(LevelEventScheduler.class);
        var ticking = new ConcurrentHashMap<Long, Object>();
        set(scheduler, LevelEventScheduler.class, "tickingChunks", ticking);
        set(scheduler, LevelEventScheduler.class, "scheduledChunks", new ConcurrentHashMap<>());
        set(scheduler, LevelEventScheduler.class, "blockEntitiesByChunk", new ConcurrentHashMap<>());
        ServerLevel level = (ServerLevel)unsafe.allocateInstance(ServerLevel.class);
        set(level, ServerLevel.class, "eventScheduler", scheduler);
        ChunkMap map = (ChunkMap)unsafe.allocateInstance(ChunkMap.class);
        var updating = new Long2ObjectLinkedOpenHashMap<ChunkHolder>();
        var visible = new Long2ObjectLinkedOpenHashMap<ChunkHolder>();
        updating.put(pos.pack(), current);
        set(map, ChunkMap.class, "updatingChunkMap", updating);
        set(map, ChunkMap.class, "visibleChunkMap", visible);
        set(map, ChunkMap.class, "level", level);
        set(map, ChunkMap.class, "chunkStatusListener", (net.minecraft.world.level.entity.ChunkStatusUpdateListener)(p, status) -> {});

        map.onFullChunkStatusChange(pos, FullChunkStatus.BLOCK_TICKING);
        if (!ticking.containsKey(pos.pack())) throw new AssertionError("New holder promotion lost before visible-map publication");
        map.onFullChunkStatusChange(pos, FullChunkStatus.FULL);
        if (ticking.containsKey(pos.pack())) throw new AssertionError("Demotion retained a ticking chunk");
        visible.put(pos.pack(), obsolete);
        map.onFullChunkStatusChange(pos, FullChunkStatus.ENTITY_TICKING);
        if (!ticking.containsKey(pos.pack())) throw new AssertionError("Replacement holder promotion used the obsolete visible holder");
        map.onFullChunkStatusChange(pos, FullChunkStatus.FULL);
        updating.remove(pos.pack());
        map.onFullChunkStatusChange(pos, FullChunkStatus.BLOCK_TICKING);
        if (!ticking.isEmpty()) throw new AssertionError("Absent current holder activated an obsolete chunk");
        // ENTITY_TICKING's range future can finish before the queued BLOCK_TICKING
        // preparation. The higher promotion replaces the lower confirmation; it must
        // still wait for block readiness or its only notification loses the timers.
        updating.put(pos.pack(), current);
        var blockReady = new CompletableFuture<ChunkResult<LevelChunk>>();
        var entityReady = new CompletableFuture<ChunkResult<LevelChunk>>();
        set(current, ChunkHolder.class, "tickingChunkFuture", blockReady);
        set(current, ChunkHolder.class, "pendingFullStateConfirmation", CompletableFuture.completedFuture(null));
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        set(map, ChunkMap.class, "chunkStatusListener", (net.minecraft.world.level.entity.ChunkStatusUpdateListener)(p, status) -> notifications.incrementAndGet());
        var promotion = ChunkHolder.class.getDeclaredMethod("scheduleFullChunkPromotion", ChunkMap.class, CompletableFuture.class, java.util.concurrent.Executor.class, FullChunkStatus.class);
        promotion.setAccessible(true);
        promotion.invoke(current, map, entityReady, (java.util.concurrent.Executor)Runnable::run, FullChunkStatus.ENTITY_TICKING);
        entityReady.complete(ChunkResult.of(chunk));
        if (notifications.get() != 0 || !ticking.isEmpty()) throw new AssertionError("Entity promotion notified before block preparation");
        blockReady.complete(ChunkResult.of(chunk));
        if (notifications.get() != 1 || !ticking.containsKey(pos.pack())) throw new AssertionError("Out-of-order preparation lost chunk timers");
        System.out.println("ChunkTickerPromotionCheck passed: hot-reader status publication, unpublished/new holder, stale/replaced holder, demotion, missing current holder and out-of-order preparation");
    }
}
