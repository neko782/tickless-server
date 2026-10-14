package net.minecraft.server.level;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.ChunkPos;

/** Synthetic dense generation queue: independent CPU work at nearby centers. */
public class ParallelChunkDispatcherBench {
    private static final int SIDE = 10;
    private static final int WORKERS = 32;
    private static final long WORK_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private static long run(final int trial) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        ParallelChunkTaskDispatcher dispatcher = new ParallelChunkTaskDispatcher(TaskScheduler.wrapExecutor("benchmark", pool), pool, WORKERS);
        CountDownLatch finished = new CountDownLatch(SIDE * SIDE);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        long started = System.nanoTime();
        try {
            for (int x = 0; x < SIDE; x++) {
                for (int z = 0; z < SIDE; z++) {
                    long center = ChunkPos.pack(x, z);
                    dispatcher.submit(() -> {
                        int concurrent = active.incrementAndGet();
                        maxActive.accumulateAndGet(concurrent, Math::max);
                        double value = 1.0;
                        long until = System.nanoTime() + WORK_NANOS;
                        while (System.nanoTime() < until) {
                            value = Math.sqrt(value + 1.0);
                        }

                        if (value == 0.0) {
                            throw new AssertionError();
                        }

                        active.decrementAndGet();
                        finished.countDown();
                    }, center, () -> 0);
                }
            }

            if (!finished.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Generation queue stalled");
            }

            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dispatcher.hasWork() && System.nanoTime() < drainDeadline) {
                Thread.sleep(1);
            }
            if (dispatcher.hasWork()) {
                throw new AssertionError("Dispatcher did not drain");
            }

            long elapsed = System.nanoTime() - started;
            System.out.printf("trial=%d elapsed_ms=%.1f max_active=%d%n", trial, elapsed / 1_000_000.0, maxActive.get());
            return elapsed;
        } finally {
            dispatcher.close();
            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        long[] times = new long[5];
        for (int i = 0; i < times.length; i++) {
            times[i] = run(i);
        }

        Arrays.sort(times);
        System.out.printf("median_ms=%.1f%n", times[times.length / 2] / 1_000_000.0);
    }
}
