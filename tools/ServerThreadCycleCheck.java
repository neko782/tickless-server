import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;

/**
 * Regression check for a watchdog hang with power-127 fireballs: an explosion held an entity's
 * lock and waited for the server thread to load a chunk, while the server thread, ticking that
 * entity, waited for its lock. Here a worker takes a lock and waits for a future that only the
 * server thread completes, after it has used the same lock. It fails if the server thread does not
 * get the lock (the worker is rolled back) within a second, or if the worker never finishes.
 *
 * <p>usage: ServerThreadCycleCheck
 */
public class ServerThreadCycleCheck {
    public static void main(final String[] args) throws Exception {
        WorldLock lock = new WorldLock();
        WorldTxn.enable(Thread.currentThread(), () -> {});
        CompletableFuture<Void> chunk = new CompletableFuture<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        boolean[] ownedAfterWait = new boolean[1];
        int[] attempts = new int[1];

        Thread.ofVirtual().start(() -> {
            WorldTxn.run(0, 0, 0, false, () -> {
                WorldTxn txn = WorldTxn.current();
                attempts[0]++;
                txn.lock(lock);
                holding.countDown();
                WorldTxn.awaitServerThread(chunk);
                // The retried attempt owns the lock again when it goes on.
                txn.lock(lock);
                ownedAfterWait[0] = lock.isHeldBy(txn);
            });
            finished.countDown();
        });
        holding.await();

        long start = System.nanoTime();
        Thread watchdog = Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(5000L);
                System.out.println("FAIL: the server thread is still waiting for the worker's lock after 5 s");
                System.exit(1);
            } catch (InterruptedException e) {
                // Finished in time.
            }
        });
        WorldTxn.run(0, 0, 0, false, () -> WorldTxn.current().lock(lock));
        double lockMillis = (System.nanoTime() - start) / 1e6;
        watchdog.interrupt();
        if (lockMillis > 1000.0) {
            System.out.printf("FAIL: the server thread got the lock only after %.0f ms%n", lockMillis);
            System.exit(1);
        }

        chunk.complete(null);
        WorldTxn.mainSafePoint();
        if (!finished.await(5L, TimeUnit.SECONDS) || !ownedAfterWait[0] || attempts[0] != 2) {
            System.out.println("FAIL: the worker did not run again after its rollback and finish (" + attempts[0] + " attempts)");
            System.exit(1);
        }

        System.out.printf("OK: server thread got the lock in %.2f ms by rolling the worker back; the worker ran again and finished%n", lockMillis);
        System.exit(0);
    }
}
