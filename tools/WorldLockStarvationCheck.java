import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;

/**
 * Regression check for a server hang: an explosion held many locks and waited for the server
 * thread to load a chunk, while hundreds of events waited for its locks. When the chunk arrived
 * the explosion never got a carrier back, because waiters looping on Object.wait kept every
 * carrier busy. Here one transaction takes a lock and waits for the main thread; many others wait
 * for that lock. It fails if the holder is not resumed within a second of the main thread answering.
 *
 * <p>Run with {@code -Djdk.virtualThreadScheduler.parallelism=4}. usage: WorldLockStarvationCheck [waiters]
 */
public class WorldLockStarvationCheck {
    public static void main(final String[] args) throws Exception {
        int waiters = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        WorldLock lock = new WorldLock();
        WorldTxn.enable(Thread.currentThread(), () -> {});
        CompletableFuture<Void> chunk = new CompletableFuture<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(waiters + 1);
        long[] resumedAt = new long[1];

        Thread.ofVirtual().start(() -> {
            WorldTxn.run(0, 0, 0, false, () -> {
                WorldTxn.current().lock(lock);
                holding.countDown();
                chunk.join();
                resumedAt[0] = System.nanoTime();
            });
            finished.countDown();
        });
        holding.await();

        for (int i = 0; i < waiters; i++) {
            Thread.ofVirtual().start(() -> {
                WorldTxn.run(0, 0, 0, false, () -> WorldTxn.current().lock(lock));
                finished.countDown();
            });
        }

        Thread.sleep(1000L);
        long completedAt = System.nanoTime();
        chunk.complete(null);
        if (!finished.await(10L, TimeUnit.SECONDS)) {
            System.out.println("FAIL: " + finished.getCount() + " of " + (waiters + 1) + " transactions still stuck"
                + (resumedAt[0] == 0L ? "; the lock holder was never resumed" : ""));
            System.exit(1);
        }

        double resumeMillis = (resumedAt[0] - completedAt) / 1e6;
        if (resumeMillis > 1000.0) {
            System.out.printf("FAIL: holder resumed only %.0f ms after the main thread answered%n", resumeMillis);
            System.exit(1);
        }

        System.out.printf("OK: holder resumed %.2f ms after the main thread answered, %d waiters done%n", resumeMillis, waiters);
        System.exit(0);
    }
}
