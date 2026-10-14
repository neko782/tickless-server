import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.parallel.WorldTxn;
import net.minecraft.world.level.parallel.WorldUndo;

/** Exercises rollback-before-callback and replay of scoped parts, with no duplicated side effects. */
public final class FabricRoutingCheck {
    public static void main(String[] args) throws Exception {
        System.setProperty("tickless.fabric", "true");
        WorldTxn.enable(Thread.currentThread(), () -> {});
        try {
            AtomicInteger world = new AtomicInteger(), external = new AtomicInteger();
            CompletableFuture<Void> done = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    WorldTxn.run(0, 0, 0, true, () -> {
                        int previous = world.getAndIncrement();
                        WorldUndo.action(() -> world.set(previous));
                        WorldTxn.modCallback("ordinary-mod");
                        if (!WorldTxn.isServerThread(Thread.currentThread())) throw new AssertionError("Callback thread");
                        external.incrementAndGet();
                    });
                    done.complete(null);
                } catch (Throwable error) { done.completeExceptionally(error); }
            });
            await(done);
            if (world.get() != 1 || external.get() != 1) throw new AssertionError("Repeated side effects: " + world + "/" + external);
            CompletableFuture<Void> scoped = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    WorldTxn.run(0, 0, 0, true, () -> {
                        WorldTxn.scoped(() -> {
                            int previous = world.getAndIncrement();
                            WorldUndo.action(() -> world.set(previous));
                            WorldTxn.modCallback("scoped-mod");
                            external.incrementAndGet();
                        });
                    });
                    scoped.complete(null);
                } catch (Throwable error) { scoped.completeExceptionally(error); }
            });
            await(scoped);
            if (world.get() != 2 || external.get() != 2) throw new AssertionError("Scoped side effects repeated");
            CompletableFuture<Void> unsafe = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    WorldTxn.run(0, 0, 0, true, () -> {
                        WorldTxn.makeIrrevocable();
                        WorldTxn.modCallback("unsafe-partial-commit");
                    });
                    unsafe.completeExceptionally(new AssertionError("Unsafe callback allowed"));
                } catch (IllegalStateException expected) {
                    if (!expected.getMessage().contains("after a partial commit")) unsafe.completeExceptionally(expected);
                    else unsafe.complete(null);
                }
            });
            await(unsafe);
            System.out.println("FabricRoutingCheck passed: rollback, exactly-once main-thread callbacks, scoped replay, unsafe callback refusal");
        } finally { WorldTxn.disable(); }
    }
    static void await(CompletableFuture<Void> done) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.isDone() && System.nanoTime() < deadline) {
            WorldTxn.mainSafePoint();
            Thread.sleep(1);
        }
        done.get(1, java.util.concurrent.TimeUnit.SECONDS);
    }
}
