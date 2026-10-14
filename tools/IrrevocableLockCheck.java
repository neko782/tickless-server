import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;

/**
 * Regression check for a hang when items came through a nether portal together: moving an entity
 * to another dimension makes its event irrevocable, and each arriving item then locked its
 * neighbour to merge with it. Two such events each held their own item and waited for the other's:
 * a cycle the deadlock resolver cannot break, because neither member can be rolled back.
 *
 * <p>Optional locks now go through {@link WorldTxn#lockIfSafe}: an irrevocable transaction only
 * takes a free lock, while a revocable one still waits (and is rolled back on a cycle).
 * Here two irrevocable transactions each hold one lock and ask for the other's; both must finish
 * within a second, and a revocable transaction must still wait for a held lock and get it.
 *
 * <p>usage: IrrevocableLockCheck
 */
public class IrrevocableLockCheck {
    public static void main(final String[] args) throws Exception {
        WorldTxn.enable(Thread.currentThread(), () -> {});
        Thread watchdog = Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(10_000L);
                System.out.println("FAIL: transactions still waiting after 10 s (deadlock)");
                System.exit(1);
            } catch (InterruptedException e) {
                // Finished in time.
            }
        });

        for (int round = 0; round < 200; round++) {
            WorldLock a = new WorldLock();
            WorldLock b = new WorldLock();
            CyclicBarrier bothHolding = new CyclicBarrier(2);
            CyclicBarrier bothTried = new CyclicBarrier(2);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger merged = new AtomicInteger();
            AtomicBoolean ownLockKept = new AtomicBoolean(true);
            for (WorldLock[] pair : new WorldLock[][] {{a, b}, {b, a}}) {
                Thread.ofVirtual().start(() -> {
                    WorldTxn.run(0, 0, 0, false, () -> {
                        WorldTxn txn = WorldTxn.current();
                        txn.lock(pair[0]);
                        WorldTxn.makeIrrevocable();
                        try {
                            bothHolding.await(5L, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        if (txn.lockIfSafe(pair[1])) {
                            merged.incrementAndGet();
                        }
                        // Neither ends (and releases its lock) until both have asked.
                        try {
                            bothTried.await(5L, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        if (!pair[0].isHeldBy(txn)) {
                            ownLockKept.set(false);
                        }
                    });
                    done.countDown();
                });
            }
            if (!done.await(1L, TimeUnit.SECONDS)) {
                System.out.println("FAIL: two irrevocable transactions waiting for each other's lock did not finish (round " + round + ")");
                System.exit(1);
            }
            if (merged.get() != 0 || !ownLockKept.get()) {
                System.out.println("FAIL: an irrevocable transaction took a lock held by another (" + merged.get() + "), or lost its own (" + !ownLockKept.get() + ") in round " + round);
                System.exit(1);
            }
        }

        // A revocable transaction must still wait for a held lock rather than skip the action.
        WorldLock held = new WorldLock();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> WorldTxn.run(0, 0, 0, false, () -> {
            WorldTxn.current().lock(held);
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));
        holding.await();
        AtomicBoolean got = new AtomicBoolean();
        CountDownLatch waiterDone = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            WorldTxn.run(0, 0, 0, false, () -> got.set(WorldTxn.current().lockIfSafe(held)));
            waiterDone.countDown();
        });
        Thread.sleep(100L);
        if (waiterDone.getCount() == 0) {
            System.out.println("FAIL: a revocable transaction skipped a held lock instead of waiting");
            System.exit(1);
        }
        release.countDown();
        if (!waiterDone.await(5L, TimeUnit.SECONDS) || !got.get()) {
            System.out.println("FAIL: a revocable transaction did not get the lock after it was released");
            System.exit(1);
        }

        watchdog.interrupt();
        System.out.println("OK: 200 pairs of irrevocable transactions skipped each other's locks without a deadlock; revocable transactions still wait");
        System.exit(0);
    }
}
