package net.minecraft.server.level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ParallelChunkTaskDispatcher;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.ChunkPos;

public class ParallelChunkDispatcherCheck {
    private static void await(final CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for dispatcher");
        }
    }

    private static void awaitUnchecked(final CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static void main(final String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        long near = ChunkPos.pack(0, 0);
        long conflicting = near;
        long neighbor = ChunkPos.pack(1, 1);
        ChunkTaskPriorityQueue queue = new ChunkTaskPriorityQueue("check");
        queue.submit(() -> {}, near, 0);
        queue.submit(() -> {}, neighbor, 0);
        if (queue.pop(pos -> pos == neighbor).chunkPos() != neighbor || queue.pop().chunkPos() != near) {
            throw new AssertionError("Eligible queue pop lost priority or a queued task");
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        TaskScheduler<Runnable> workers = TaskScheduler.wrapExecutor("check", pool);
        ParallelChunkTaskDispatcher dispatcher = new ParallelChunkTaskDispatcher(workers, pool, 4);
        CountDownLatch nearStarted = new CountDownLatch(1);
        CountDownLatch neighborStarted = new CountDownLatch(1);
        CountDownLatch releaseNear = new CountDownLatch(1);
        CountDownLatch releaseNeighbor = new CountDownLatch(1);
        CountDownLatch conflictingStarted = new CountDownLatch(1);
        AtomicBoolean startedTooEarly = new AtomicBoolean();
        try {
            dispatcher.submit(() -> {
                nearStarted.countDown();
                try {
                    await(releaseNear);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }, near, () -> 0);
            await(nearStarted);
            dispatcher.submit(() -> {
                if (releaseNear.getCount() != 0) {
                    startedTooEarly.set(true);
                }
                conflictingStarted.countDown();
            }, conflicting, () -> 0);
            dispatcher.submit(() -> {
                neighborStarted.countDown();
                try {
                    await(releaseNeighbor);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }, neighbor, () -> 0);
            await(neighborStarted);
            if (conflictingStarted.await(200, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Two tasks for the same center ran concurrently");
            }
            releaseNear.countDown();
            await(conflictingStarted);
            if (startedTooEarly.get()) {
                throw new AssertionError("Same-center task started before its predecessor completed");
            }

            releaseNeighbor.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dispatcher.hasWork() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (dispatcher.hasWork()) {
                throw new AssertionError("Dispatcher did not finish after releasing its tasks");
            }

            ChunkNeighborhoodLocks locks = new ChunkNeighborhoodLocks();
            CountDownLatch featureStarted = new CountDownLatch(1);
            CountDownLatch releaseFeature = new CountDownLatch(1);
            CountDownLatch nearbyFeatureStarted = new CountDownLatch(1);
            CompletableFuture<Void> firstFeature = CompletableFuture.runAsync(() -> locks.withFeatureNeighborhood(new ChunkPos(0, 0), () -> {
                featureStarted.countDown();
                awaitUnchecked(releaseFeature);
                return null;
            }), pool);
            await(featureStarted);
            CompletableFuture<Void> nearbyFeature = CompletableFuture.runAsync(() -> locks.withFeatureNeighborhood(new ChunkPos(1, 1), () -> {
                nearbyFeatureStarted.countDown();
                return null;
            }), pool);
            if (nearbyFeatureStarted.await(200, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Overlapping feature writes ran concurrently");
            }
            releaseFeature.countDown();
            firstFeature.join();
            nearbyFeature.join();
            System.out.println("Parallel chunk dispatcher check passed");
        } finally {
            releaseNear.countDown();
            releaseNeighbor.countDown();
            dispatcher.close();
            pool.shutdownNow();
        }
    }
}
