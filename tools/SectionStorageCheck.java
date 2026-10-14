import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** Single section identity, persistence, and competing synchronous/asynchronous unpacking. */
public class SectionStorageCheck {
    static final DataFixer FIXER = new DataFixer() {
        public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int from, int to) { return input; }
        public Schema getSchema(int version) { throw new UnsupportedOperationException(); }
    };
    static final ChunkIOErrorReporter ERRORS = new ChunkIOErrorReporter() {
        public void reportChunkLoadFailure(Throwable t, RegionStorageInfo info, ChunkPos pos) { throw new AssertionError(t); }
        public void reportChunkSaveFailure(Throwable t, RegionStorageInfo info, ChunkPos pos) { throw new AssertionError(t); }
    };
    static class Section {
        final Runnable dirty;
        int value;
        Section(int value, Runnable dirty) { this.value = value; this.dirty = dirty; }
        void set(int next) { value = next; dirty.run(); }
    }
    static class Store extends SectionStorage<Section, Integer> {
        Store(Path dir, AtomicInteger creations, CountDownLatch unpacking, CountDownLatch release) {
            this(new SimpleRegionStorage(new RegionStorageInfo("test", Level.OVERWORLD, "sections"), dir, FIXER, true, DataFixTypes.POI_CHUNK),
                creations, unpacking, release);
        }
        Store(SimpleRegionStorage region, AtomicInteger creations, CountDownLatch unpacking, CountDownLatch release) {
            super(region, Codec.INT, section -> section.value, (value, dirty) -> {
                    if (unpacking != null) {
                        unpacking.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Unpack release timed out"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                    return new Section(value, dirty);
                }, dirty -> {
                    creations.incrementAndGet();
                    try { Thread.sleep(100); } catch (InterruptedException e) { throw new AssertionError(e); }
                    return new Section(0, dirty);
                }, RegistryAccess.EMPTY, ERRORS, LevelHeightAccessor.create(0, 64));
        }
        Section create(long pos) { return getOrCreate(pos); }
        Section load(long pos) { return getOrLoad(pos).orElseThrow(); }
        void tickNow() { tick(() -> true); }
    }
    /** Model a write that has been submitted but whose I/O task has not yet been accepted. */
    static class DeferredStorage extends SimpleRegionStorage {
        final CompletableFuture<Void> written = new CompletableFuture<>();
        CompoundTag pending;
        boolean closed;
        DeferredStorage(Path dir) {
            super(new RegionStorageInfo("test", Level.OVERWORLD, "deferred-sections"), dir, FIXER, true, DataFixTypes.POI_CHUNK);
        }
        @Override
        public CompletableFuture<Void> write(ChunkPos pos, CompoundTag value) {
            pending = value;
            return written;
        }
        @Override
        public CompletableFuture<Void> synchronize(boolean flush) {
            if (closed) throw new AssertionError("Save drained after storage closed");
            if (pending != null) written.complete(null);
            return written;
        }
        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
    private static void checkQueuedWriteBeforeClose() throws Exception {
        Path dir = Files.createTempDirectory(Path.of("build/checks"), "section-close-");
        DeferredStorage region = new DeferredStorage(dir);
        Store store = new Store(region, new AtomicInteger(), null, null);
        try (store) {
            store.create(SectionPos.asLong(0, 1, 0)).set(43);
            store.flushAll();
            if (region.pending == null || region.written.isDone()) throw new AssertionError("Deferred save fixture did not queue a write");
        }
        if (!region.closed || !region.written.isDone() || region.written.isCompletedExceptionally()) {
            throw new AssertionError("Section storage closed with an unfinished save");
        }
        store.close(); // Repeated close must not submit work to an already stopped I/O worker.

        DeferredStorage failed = new DeferredStorage(dir.resolve("failed"));
        IOException failure = new IOException("deliberate save failure");
        Store failedStore = new Store(failed, new AtomicInteger(), null, null);
        try (failedStore) {
            failedStore.create(SectionPos.asLong(0, 1, 0)).set(44);
            failedStore.flushAll();
            failed.written.completeExceptionally(failure);
        } catch (CompletionException error) {
            if (error.getCause() != failure || !failed.closed) throw new AssertionError("Failed save lost its cause or leaked storage", error);
            failedStore.close();
            return;
        }
        throw new AssertionError("Failed save was ignored on close");
    }
    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } finally {
            net.minecraft.util.Util.shutdownExecutors();
        }
    }
    private static void run(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkQueuedWriteBeforeClose();
        Path dir = Files.createTempDirectory(Path.of("build/checks"), "section-storage-");
        long key = SectionPos.asLong(0, 1, 0);
        AtomicInteger creations = new AtomicInteger();
        try (var store = new Store(dir, creations, null, null)) {
            if (args.length == 0 || !args[0].equals("load-race")) {
                try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    CountDownLatch start = new CountDownLatch(1);
                    var futures = new ArrayList<java.util.concurrent.Future<Section>>();
                    for (int i = 0; i < 64; i++) futures.add(pool.submit(() -> { start.await(); return store.create(key); }));
                    start.countDown();
                    var identities = new HashSet<Section>();
                    for (var future : futures) identities.add(future.get(5, TimeUnit.SECONDS));
                    if (identities.size() != 1 || creations.get() != 1) throw new AssertionError("Duplicate section instances: " + identities.size());
                }
            }
            store.create(key).set(42);
            store.flushAll();
        }
        CountDownLatch unpacking = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var store = new Store(dir, new AtomicInteger(), unpacking, release);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            store.prefetch(new ChunkPos(0, 0)).get(5, TimeUnit.SECONDS);
            var reader = pool.submit(() -> store.load(key));
            try {
                if (!unpacking.await(5, TimeUnit.SECONDS)) throw new AssertionError("Reader never unpacked");
                // This must not unpack the same completed IO future while the reader owns it.
                pool.submit(store::tickNow).get(1, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            Section section = reader.get(5, TimeUnit.SECONDS);
            if (section.value != 42 || store.load(key) != section) throw new AssertionError("Loaded section was replaced or corrupted");
        }
        System.out.println("SectionStorageCheck passed: queued saves finish before close, single creation, persistence and competing unpack ownership");
    }
}
