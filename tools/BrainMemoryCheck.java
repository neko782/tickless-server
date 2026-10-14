import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;
import sun.misc.Unsafe;

/** Memory observations/hints must not acquire a foreign owner; reconfiguration still must. */
public class BrainMemoryCheck {
    static Entity fixture() throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        // Only ownership/type identity is needed. No world or entity methods run on this fixture.
        Entity entity = (Entity) unsafe.allocateInstance(Cow.class);
        Field lock = Entity.class.getDeclaredField("worldLock");
        lock.setAccessible(true);
        lock.set(entity, new WorldLock());
        Field type = Entity.class.getDeclaredField("type");
        type.setAccessible(true);
        type.set(entity, EntityTypes.COW);
        return entity;
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Entity owner = fixture();
        Brain<?> brain = new Brain<>();
        Method register = Brain.class.getDeclaredMethod("registerMemory", MemoryModuleType.class);
        register.setAccessible(true);
        register.invoke(brain, MemoryModuleType.ATTACK_TARGET);
        brain.worldOwner = owner;
        WorldTxn.enable(Thread.currentThread(), () -> {});
        WorldTxn.current().lock(owner.worldLock);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                WorldTxn.run(0, 0, 0, false, () -> {
                    brain.setMemory(MemoryModuleType.ATTACK_TARGET, (net.minecraft.world.entity.LivingEntity) owner);
                    if (!brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                        || !brain.isMemoryValue(MemoryModuleType.ATTACK_TARGET, (net.minecraft.world.entity.LivingEntity) owner)
                        || brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElseThrow() != owner
                        || !brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT)) {
                        throw new AssertionError("Published memory missing");
                    }
                    brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                    if (brain.getMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) throw new AssertionError("Erase failed");
                    if (owner.worldLock.isHeldBy(WorldTxn.current())) throw new AssertionError("Observation took owner lock");
                });
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(2, TimeUnit.SECONDS)) throw new AssertionError("Memory hint waited for foreign owner");
        WorldTxn.mainSafePoint();
        if (failure.get() != null) throw new AssertionError(failure.get());
        // Non-memory operations retain the instrumentation entry lock.
        Thread bulk = Thread.ofVirtual().start(() -> {
            try {
                WorldTxn.run(0, 0, 0, false, () -> {
                    brain.clearMemories();
                    if (!owner.worldLock.isHeldBy(WorldTxn.current())) throw new AssertionError("Bulk operation lost owner lock");
                });
            } catch (Throwable t) { failure.set(t); }
        });
        bulk.join(2000);
        if (bulk.isAlive() || failure.get() != null) throw new AssertionError("Bulk ownership failed", failure.get());
        System.out.println("BrainMemoryCheck passed: foreign observations/hints, publication, erase, bulk ownership");
        System.exit(0);
    }
}
