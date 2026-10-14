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
 * <p>An event now takes a single token as it becomes irrevocable ({@link WorldTxn#makeIrrevocable}),
 * while it can still be rolled back, so at most one event is irrevocable at a time and every cycle
 * keeps a member to roll back. Here two events each hold one lock, become irrevocable and then
 * need the other's lock:
 * <ul>
 * <li>with a lock they must have ({@code lock}): both finish within a second, the one waiting for
 * the token having been rolled back and run again, and each body commits exactly once;
 * <li>with an optional lock ({@link WorldTxn#lockIfSafe}): both finish.
 * </ul>
 * A revocable transaction must still wait for a held optional lock and get it.
 *
 * <p>usage: IrrevocableLockCheck
 */
public class IrrevocableLockCheck {
    private static final int ROUNDS = 200;

    public static void main(final String[] args) throws Exception {
        WorldTxn.enable(Thread.currentThread(), () -> {});
        Thread watchdog = Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(20_000L);
                System.out.println("FAIL: transactions still waiting after 20 s (deadlock)");
                System.exit(1);
            } catch (InterruptedException e) {
                // Finished in time.
            }
        });

        for (int round = 0; round < ROUNDS; round++) {
            crossedLocks(round, true);
            crossedLocks(round, false);
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
        System.out.println("OK: " + ROUNDS + " pairs of events that became irrevocable holding each other's locks finished, "
            + "with required and optional locks; revocable transactions still wait");
        System.exit(0);
    }

    /**
     * Two events each take their own lock, wait until both hold it, become irrevocable and then
     * take the other's lock, required or optional.
     */
    private static void crossedLocks(final int round, final boolean required) throws InterruptedException {
        WorldLock a = new WorldLock();
        WorldLock b = new WorldLock();
        CyclicBarrier bothHolding = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger committed = new AtomicInteger();
        AtomicBoolean tookHeldLock = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        for (WorldLock[] pair : new WorldLock[][] {{a, b}, {b, a}}) {
            boolean[] first = {true};
            Thread.ofVirtual().start(() -> {
                try {
                    WorldTxn.run(0, 0, 0, false, () -> {
                        WorldTxn txn = WorldTxn.current();
                        txn.lock(pair[0]);
                        if (first[0]) {
                            // Only the first attempt meets the other: a retry runs once the other has finished.
                            first[0] = false;
                            try {
                                bothHolding.await(5L, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }

                        WorldTxn.makeIrrevocable();
                        if (required) {
                            txn.lock(pair[1]);
                        } else if (txn.lockIfSafe(pair[1]) && !pair[1].isHeldBy(txn)) {
                            tookHeldLock.set(true);
                        }

                        committed.incrementAndGet();
                    });
                } catch (Throwable t) {
                    failed.set(true);
                    t.printStackTrace(System.out);
                }

                done.countDown();
            });
        }

        String kind = required ? "required" : "optional";
        if (!done.await(2L, TimeUnit.SECONDS)) {
            System.out.println("FAIL: two events that became irrevocable needing each other's " + kind + " lock did not finish (round " + round + ")");
            System.exit(1);
        }

        if (failed.get() || committed.get() != 2 || tookHeldLock.get()) {
            System.out.println("FAIL: " + kind + " locks in round " + round + ": failed " + failed.get() + ", committed bodies "
                + committed.get() + " (expected 2), lockIfSafe reported a lock it does not hold " + tookHeldLock.get());
            System.exit(1);
        }
    }
}
