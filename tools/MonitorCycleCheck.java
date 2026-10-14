import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;

/**
 * Checks that a wait cycle through a Java monitor is broken. Field writes lock their owner, so
 * world code can wait for a world lock inside a latch: here event A holds monitor M and waits for
 * lock L, while event B holds L and waits to enter M. B cannot be rolled back while it blocks on
 * a monitor, so A is: it leaves M, and runs again after B. The build inserts the monitor hooks
 * into game code; this check calls them by hand. It fails if either event is still stuck after
 * 5 s, or if B does not still own L after leaving the monitor.
 *
 * <p>usage: MonitorCycleCheck
 */
public class MonitorCycleCheck {
    public static void main(final String[] args) throws Exception {
        WorldTxn.enable(Thread.currentThread(), () -> {});
        WorldLock lock = new WorldLock();
        Object monitor = new Object();
        CountDownLatch aInMonitor = new CountDownLatch(1);
        CountDownLatch bHoldsLock = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        boolean[] bOwnsLockInMonitor = new boolean[1];

        Thread.ofVirtual().start(() -> {
            WorldTxn.run(0, 0, 0, false, () -> {
                WorldTxn.beforeMonitor(monitor);
                synchronized (monitor) {
                    WorldTxn.enteredMonitor(monitor);
                    aInMonitor.countDown();
                    await(bHoldsLock);
                    WorldTxn.current().lock(lock);
                }
                WorldTxn.exitedMonitor(monitor);
            });
            done.countDown();
        });
        Thread.ofVirtual().start(() -> {
            WorldTxn.run(0, 0, 0, false, () -> {
                WorldTxn txn = WorldTxn.current();
                txn.lock(lock);
                await(aInMonitor);
                bHoldsLock.countDown();
                WorldTxn.beforeMonitor(monitor);
                synchronized (monitor) {
                    WorldTxn.enteredMonitor(monitor);
                }
                WorldTxn.exitedMonitor(monitor);
                bOwnsLockInMonitor[0] = lock.isHeldBy(txn);
            });
            done.countDown();
        });

        if (!done.await(5L, TimeUnit.SECONDS)) {
            System.out.println("FAIL: " + done.getCount() + " of 2 events still stuck in the monitor/lock cycle");
            System.exit(1);
        }

        if (!bOwnsLockInMonitor[0]) {
            System.out.println("FAIL: B lost its lock");
            System.exit(1);
        }

        System.out.println("OK: monitor/lock cycle broken by rolling back the event waiting for the lock");
        System.exit(0);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
