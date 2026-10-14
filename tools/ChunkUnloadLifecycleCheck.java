import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.parallel.WorldTxn;
import net.minecraft.world.phys.AABB;

/** Real entity-unload snapshots, revived visibility, and producer admission across an exclusive gate. */
public class ChunkUnloadLifecycleCheck {
    static class TestEntity implements EntityAccess {
        int value;
        boolean removed;
        long stamp;
        EntityInLevelCallback callback = EntityInLevelCallback.NULL;
        public int getId() { return 1; }
        public boolean isRemoved() { return removed; }
        public UUID getUUID() { return new UUID(0, 1); }
        public BlockPos blockPosition() { return BlockPos.ZERO; }
        public AABB getBoundingBox() { return new AABB(0, 0, 0, 1, 1, 1); }
        public void setLevelCallback(EntityInLevelCallback callback) { this.callback = callback; }
        public Stream<TestEntity> getSelfAndPassengers() { return Stream.of(this); }
        public Stream<TestEntity> getPassengersAndSelf() { return Stream.of(this); }
        public void setRemoved(Entity.RemovalReason reason) { removed = true; callback.onRemove(reason); }
        public boolean shouldBeSaved() { return true; }
        public boolean isAlwaysTicking() { return false; }
        public long sectionStamp() { return stamp; }
        public void setSectionStamp(long stamp) { this.stamp = stamp; }
    }
    static class Storage implements EntityPersistentStorage<TestEntity> {
        int writes;
        int snapshot = -1;
        public CompletableFuture<ChunkEntities<TestEntity>> loadEntities(ChunkPos pos) {
            return CompletableFuture.completedFuture(new ChunkEntities<>(pos, List.of()));
        }
        public void storeEntities(ChunkEntities<TestEntity> chunk) {
            writes++;
            snapshot = chunk.getEntities().findFirst().map(entity -> entity.value).orElse(-1);
        }
        public void flush(boolean flushStorage) {}
    }
    static final LevelCallback<TestEntity> CALLBACKS = new LevelCallback<>() {
        public void onCreated(TestEntity e) {}
        public void onDestroyed(TestEntity e) {}
        public void onTickingStart(TestEntity e) {}
        public void onTickingEnd(TestEntity e) {}
        public void onTrackingStart(TestEntity e) {}
        public void onTrackingEnd(TestEntity e) {}
        public void onSectionChange(TestEntity e) {}
    };

    static void entityUnload(boolean revive) throws Exception {
        Storage storage = new Storage();
        var manager = new PersistentEntitySectionManager<>(TestEntity.class, CALLBACKS, storage);
        var entity = new TestEntity();
        ChunkPos pos = new ChunkPos(0, 0);
        manager.updateChunkStatus(pos, Visibility.TICKING);
        manager.processPendingLoads();
        if (!manager.addNewEntity(entity)) throw new AssertionError("Entity fixture failed to register");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                WorldTxn.run(0, 0, 0, false, () -> {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Event release timed out"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    entity.value = 37;
                });
                done.complete(null);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Event failed to enter");
        try {
            manager.updateChunkStatus(pos, Visibility.HIDDEN);
            manager.tick();
            WorldTxn.pollExclusive();
            if (storage.writes != 0 || entity.removed) throw new AssertionError("Entity snapshot/unload overtook active event");
            if (revive) manager.updateChunkStatus(pos, Visibility.TICKING);
        } finally { release.countDown(); }
        done.get(5, TimeUnit.SECONDS);
        if (!WorldTxn.pollExclusive()) throw new AssertionError("Pending entity unload was lost");
        if (revive) {
            if (storage.writes != 0 || entity.removed || manager.count() != 1) throw new AssertionError("Deferred unload discarded revived entity chunk");
            manager.updateChunkStatus(pos, Visibility.HIDDEN);
            manager.tick();
            WorldTxn.pollExclusive();
        }
        if (storage.writes != 1 || storage.snapshot != 37 || !entity.removed || manager.count() != 0) {
            throw new AssertionError("Entity final write did not survive exactly one unload snapshot");
        }
    }

    static void producerGate() throws Exception {
        // Invoke through reflection so this test compiles against the previous packaged engine.
        var source = WorldTxn.class.getMethod("runEventSource", Runnable.class);
        CountDownLatch admitted = new CountDownLatch(1), enterEvent = new CountDownLatch(1);
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicBoolean ran = new AtomicBoolean(), exclusive = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try {
                source.invoke(null, (Runnable)() -> {
                    admitted.countDown();
                    try { if (!enterEvent.await(5, TimeUnit.SECONDS)) throw new AssertionError("Producer release timed out"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    WorldTxn.run(0, 0, 0, false, () -> ran.set(true));
                });
                done.complete(null);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        if (!admitted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Producer failed to enter");
        WorldTxn.requestExclusive(() -> {
            if (!ran.get()) throw new AssertionError("Snapshot overtook admitted producer");
            exclusive.set(true);
        });
        WorldTxn.pollExclusive();
        if (exclusive.get()) throw new AssertionError("Gate ignored admitted producer");
        enterEvent.countDown();
        done.get(1, TimeUnit.SECONDS);
        WorldTxn.pollExclusive();
        if (!ran.get() || !exclusive.get()) throw new AssertionError("Producer/gate did not drain");
    }

    public static void main(String[] args) throws Exception {
        WorldTxn.enable(Thread.currentThread(), () -> {});
        try {
            entityUnload(false);
            entityUnload(true);
            producerGate();
            System.out.println("ChunkUnloadLifecycleCheck passed: final entity snapshot, revived chunk, producer drain and nested event admission");
        } finally { WorldTxn.disable(); }
    }
}
