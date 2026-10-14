import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.parallel.WorldTxn;
import sun.misc.Unsafe;

/** Idle task polling completes a drained exclusive request before another network tick. */
public final class IdleDrainCheck {
    public static void main(String[] args) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe)singleton.get(null);
        MinecraftServer server = (MinecraftServer)unsafe.allocateInstance(DedicatedServer.class);
        WorldTxn.enable(Thread.currentThread(), () -> {});
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        AtomicInteger snapshots = new AtomicInteger();
        Thread.ofVirtual().start(() -> {
            try {
                WorldTxn.run(0, 0, 0, false, () -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Release timed out");
                    } catch (InterruptedException error) { throw new AssertionError(error); }
                });
                finished.complete(null);
            } catch (Throwable error) { finished.completeExceptionally(error); }
        });
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Event failed to enter");
        try {
            WorldTxn.requestExclusive(() -> {
                if (!WorldTxn.isExclusive()) throw new AssertionError("Snapshot ran outside exclusive lane");
                snapshots.incrementAndGet();
            });
            if (WorldTxn.pollExclusive() || snapshots.get() != 0)
                throw new AssertionError("Snapshot overtook active event");
        } finally { release.countDown(); }
        finished.get(5, TimeUnit.SECONDS);
        var poll = MinecraftServer.class.getDeclaredMethod("pollTaskInternal");
        poll.setAccessible(true);
        // The fixture has no task queue: only the pending, now-drained world work can run.
        try {
            if (!Boolean.TRUE.equals(poll.invoke(server)) || snapshots.get() != 1)
                throw new AssertionError("Idle poll left a drained snapshot waiting for networking");
        } finally { WorldTxn.disable(); }
        System.out.println("IdleDrainCheck passed: active event protected, idle poll resumes world, snapshot exactly once");
    }
}
