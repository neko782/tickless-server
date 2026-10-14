package net.minecraft.world.level.parallel;

import com.mojang.logging.LogUtils;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Runs world events.
 *
 * <p>By default every task gets its own virtual thread. A task that waits for a world lock or
 * for the server thread to load a chunk unmounts, and its carrier core runs other events in
 * the meantime. Set {@code -Dtickless.virtualThreads=false} to use a fixed platform-thread pool
 * instead, where a waiting event keeps its core idle.
 */
public final class WorldWorkers implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    public final LongAdder events = new LongAdder();
    public final LongAdder failures = new LongAdder();
    private final LongAdder pending = new LongAdder();
    private final int threads;
    private final boolean virtual;
    private final Executor executor;
    private final ForkJoinPool pool;

    public WorldWorkers(final int threads) {
        this.threads = threads;
        this.virtual = !"false".equals(System.getProperty("tickless.virtualThreads"));
        if (this.virtual) {
            configureCarriers();
            this.pool = null;
            Thread.Builder.OfVirtual builder = Thread.ofVirtual().name("World Event").uncaughtExceptionHandler((thread, t) -> LOGGER.error("Uncaught exception in world event", t));
            java.util.concurrent.ThreadFactory factory = builder.factory();
            this.executor = task -> factory.newThread(task).start();
        } else {
            AtomicInteger counter = new AtomicInteger();
            this.pool = new ForkJoinPool(threads, pool -> {
                ForkJoinWorkerThread thread = new ForkJoinWorkerThread(null, pool, true) {
                };
                thread.setName("World Worker #" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, (thread, t) -> LOGGER.error("Uncaught exception in {}", thread.getName(), t), true);
            this.executor = this.pool;
        }
    }

    /** Sets the virtual thread carrier count. Must run before the first virtual thread starts. */
    public static void configureCarriers() {
        if (System.getProperty("jdk.virtualThreadScheduler.parallelism") == null) {
            System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(defaultThreadCount()));
        }
    }

    public static int defaultThreadCount() {
        String configured = System.getProperty("tickless.workers");
        if (configured != null) {
            return Math.max(1, Integer.parseInt(configured));
        }

        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    public int threads() {
        return this.threads;
    }

    public boolean isVirtual() {
        return this.virtual;
    }

    public void execute(final Runnable task) {
        this.pending.increment();
        this.executor.execute(() -> {
            try {
                task.run();
            } finally {
                this.pending.decrement();
            }
        });
    }

    public boolean isQuiescent() {
        return this.pending.sum() == 0L;
    }

    /** Tasks queued or running. */
    public long pendingTasks() {
        return this.pending.sum();
    }

    /** Runs one event in its own transaction. Failures are logged so one broken block cannot stop the world. */
    public void runEvent(final int x, final int y, final int z, final boolean hasOrigin, final Runnable event, final Object description) {
        this.tryRunEvent(x, y, z, hasOrigin, event, description);
    }

    /** Returns true only after the event commits, so completed-step counters exclude failures. */
    public boolean tryRunEvent(final int x, final int y, final int z, final boolean hasOrigin, final Runnable event, final Object description) {
        try {
            WorldTxn.run(x, y, z, hasOrigin, event);
            return true;
        } catch (Throwable t) {
            WorldTxn.reportFabricFailure(t);
            this.failures.increment();
            LOGGER.error("Exception in world event {}", description, t);
            return false;
        } finally {
            this.events.increment();
        }
    }

    @Override
    public void close() {
        if (this.pool != null) {
            this.pool.shutdown();
            this.pool.awaitQuiescence(10L, TimeUnit.SECONDS);
            this.pool.shutdownNow();
        }
    }
}
