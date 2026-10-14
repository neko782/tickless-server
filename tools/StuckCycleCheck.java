import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;

/**
 * A wait cycle in which no member can be rolled back must be reported within seconds, not found
 * by the 10-second long-wait report or the watchdog. The server thread is never rolled back and
 * an irrevocable event cannot be: here the server thread holds a monitor and waits for a world
 * lock an irrevocable event holds, and that event waits to enter the monitor. Nothing can break
 * this cycle (the two threads stay deadlocked); the check requires the deadlock resolver to report
 * it within five seconds.
 *
 * <p>usage: StuckCycleCheck
 */
public class StuckCycleCheck {
    public static void main(final String[] args) throws Exception {
        Object monitor = new Object();
        WorldLock lock = new WorldLock();
        CountDownLatch serverInMonitor = new CountDownLatch(1);
        CountDownLatch eventHoldsLock = new CountDownLatch(1);
        Thread server = Thread.ofPlatform().daemon().name("Server thread").unstarted(() -> {
            WorldTxn.beforeMonitor(monitor);
            synchronized (monitor) {
                WorldTxn.enteredMonitor(monitor);
                serverInMonitor.countDown();
                await(eventHoldsLock);
                WorldTxn.current().lock(lock);
            }
        });
        WorldTxn.enable(server, () -> {});
        server.start();
        await(serverInMonitor);

        Thread.ofPlatform().daemon().start(() -> WorldTxn.run(0, 0, 0, false, () -> {
            WorldTxn.current().lock(lock);
            WorldTxn.makeIrrevocable();
            eventHoldsLock.countDown();
            WorldTxn.beforeMonitor(monitor);
            synchronized (monitor) {
                WorldTxn.enteredMonitor(monitor);
            }
        }));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (WorldTxn.STUCK_CYCLES.sum() == 0L && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        if (WorldTxn.STUCK_CYCLES.sum() == 0L) {
            System.out.println("FAIL: a cycle of the server thread and an irrevocable event was not reported within 5 s");
            System.exit(1);
        }

        System.out.println("OK: a wait cycle no rollback can break was reported");
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
