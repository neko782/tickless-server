import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.NonNullList;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;

/**
 * Stress check for world locks: many transactions take random, overlapping lock sets in random
 * order, so wait cycles and rollbacks happen constantly. Each transaction adds one to a counter
 * per lock it takes, in a NonNullList (whose writes are logged for rollbacks), without atomics.
 * It fails if the run hangs, if two transactions touch one lock at the same time, if any lock is
 * left owned afterwards, or if the counters do not add up to the increments of the attempts that
 * committed: a rolled back attempt's increments must be undone.
 *
 * <p>usage: WorldLockCheck [threads] [transactions-per-thread] [locks]
 */
public class WorldLockCheck {
    public static void main(final String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 32;
        int perThread = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
        int lockCount = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        WorldLock[] locks = new WorldLock[lockCount];
        AtomicInteger[] touching = new AtomicInteger[lockCount];
        NonNullList<Integer> counters = NonNullList.withSize(lockCount, 0);
        AtomicLong committed = new AtomicLong();
        for (int i = 0; i < lockCount; i++) {
            locks[i] = new WorldLock();
            touching[i] = new AtomicInteger();
        }

        // The main thread stands in for the server thread; the virtual threads are world workers.
        WorldTxn.enable(Thread.currentThread(), () -> {});
        AtomicLong overlaps = new AtomicLong();
        AtomicLong done = new AtomicLong();
        CountDownLatch finished = new CountDownLatch(threads);
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            int seed = t;
            Thread.ofVirtual().start(() -> {
                Random random = new Random(seed);
                for (int n = 0; n < perThread; n++) {
                    // The increments of the last attempt, the one that committed.
                    int[] increments = new int[1];
                    WorldTxn.run(0, 0, 0, false, () -> {
                        WorldTxn txn = WorldTxn.current();
                        increments[0] = 0;
                        int size = 1 + random.nextInt(4);
                        for (int k = 0; k < size; k++) {
                            int index = random.nextInt(lockCount);
                            // Touch the lock the way world code touches a block: acquire, then use.
                            txn.lock(locks[index]);
                            if (touching[index].incrementAndGet() != 1) {
                                overlaps.incrementAndGet();
                            }

                            counters.set(index, counters.get(index) + 1);
                            increments[0]++;
                            Thread.onSpinWait();
                            touching[index].decrementAndGet();
                        }
                    });

                    committed.addAndGet(increments[0]);
                    done.incrementAndGet();
                }

                finished.countDown();
            });
        }

        if (!finished.await(120, TimeUnit.SECONDS)) {
            throw new AssertionError("Hung after " + done.get() + " transactions");
        }

        for (int i = 0; i < lockCount; i++) {
            if (!locks[i].isFree()) {
                throw new AssertionError("Lock " + i + " still owned after all transactions ended");
            }
        }

        long total = 0L;
        for (int i = 0; i < lockCount; i++) {
            total += counters.get(i);
        }

        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(
            "WorldLockCheck: %d transactions in %.2fs, %d rollbacks, %d overlapping touches, counters %d of %d committed increments%n",
            done.get(), seconds, WorldTxn.ABORTS.sum(), overlaps.get(), total, committed.get()
        );
        if (overlaps.get() != 0L) {
            throw new AssertionError("Overlapping touches: " + overlaps.get());
        }

        if (total != committed.get()) {
            throw new AssertionError("Counters add up to " + total + ", but committed attempts added " + committed.get());
        }
    }
}
