package net.minecraft.world.level.parallel;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Splits per-item server-thread work, such as sending entity changes, across helper threads
 * while the server thread waits.
 *
 * <p>The helpers are platform threads outside the world-worker scheduler, so a phase does not
 * queue behind world events. The server thread blocks until every item is done, so the phase
 * keeps its place in the tick.
 */
public final class ServerThreadHelpers {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MIN_ITEMS_PER_TASK = 256;
    private static final ForkJoinPool POOL = createPool();

    private ServerThreadHelpers() {
    }

    private static ForkJoinPool createPool() {
        AtomicInteger counter = new AtomicInteger();
        int threads = Math.max(1, Integer.getInteger("tickless.serverHelpers", Runtime.getRuntime().availableProcessors()));
        return new ForkJoinPool(threads, pool -> {
            ForkJoinWorkerThread thread = new Helper(pool);
            thread.setName("Server Helper #" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, (thread, t) -> LOGGER.error("Uncaught exception in {}", thread.getName(), t), false);
    }

    /** Whether the current thread is running part of a server-thread phase for the server thread. */
    public static boolean isHelperThread() {
        return Thread.currentThread() instanceof Helper;
    }

    /** Runs {@code action} on every item, in parallel when there are enough, and returns when all are done. */
    public static <T> void forEach(final List<T> items, final Consumer<? super T> action) {
        if (items.size() < MIN_ITEMS_PER_TASK * 2) {
            items.forEach(action);
        } else {
            POOL.invoke(new Slice<>(items, action, 0, items.size()));
        }
    }

    private static final class Helper extends ForkJoinWorkerThread {
        private Helper(final ForkJoinPool pool) {
            super(null, pool, true);
        }
    }

    private static final class Slice<T> extends RecursiveAction {
        private final List<T> items;
        private final Consumer<? super T> action;
        private final int from;
        private final int to;

        private Slice(final List<T> items, final Consumer<? super T> action, final int from, final int to) {
            this.items = items;
            this.action = action;
            this.from = from;
            this.to = to;
        }

        @Override
        protected void compute() {
            if (this.to - this.from <= MIN_ITEMS_PER_TASK) {
                for (int i = this.from; i < this.to; i++) {
                    this.action.accept(this.items.get(i));
                }
            } else {
                int mid = (this.from + this.to) >>> 1;
                invokeAll(new Slice<>(this.items, this.action, this.from, mid), new Slice<>(this.items, this.action, mid, this.to));
            }
        }
    }
}
