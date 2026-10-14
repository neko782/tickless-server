import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;

/** Publishing a hint cannot expose a value paired with another hint's expiry. */
public class MemoryPublicationCheck {
    public static void main(String[] args) throws Exception {
        MemorySlot<Long> slot = MemorySlot.create();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = Thread.ofVirtual().start(() -> {
            for (long i = 1; i <= 1_000_000; i++) {
                slot.set(i, i);
                if ((i & 7) == 0) slot.clear();
            }
        });
        Brain.Visitor visitor = new Brain.Visitor() {
            public <U> void acceptEmpty(MemoryModuleType<U> type) {}
            public <U> void accept(MemoryModuleType<U> type, U value) { throw new AssertionError("Lost expiry"); }
            public <U> void accept(MemoryModuleType<U> type, U value, long ttl) {
                if (value == null || ((Long)value).longValue() != ttl) throw new AssertionError("Mixed publication");
            }
        };
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 1_000_000; i++) slot.visit(null, visitor);
            } catch (Throwable t) { failure.set(t); }
        });
        writer.join(); reader.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        slot.set(17L, 1); slot.tick();
        if (slot.value() != 17L || slot.timeToLive() != 0) throw new AssertionError("Expiry changed");
        slot.tick();
        if (slot.hasValue()) throw new AssertionError("Failed expiry");
        slot.set(23L); slot.tick();
        if (slot.value() != 23L || slot.canExpire()) throw new AssertionError("Permanent hint expired");
        System.out.println("MemoryPublicationCheck passed: coherent concurrent hints, clear and expiry");
    }
}
