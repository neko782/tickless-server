import com.mojang.datafixers.util.Pair;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/** Stable running children isolate gate traversal; run unchanged on both installations. */
public class GateBehaviorBench {
    static long ticks;
    static volatile long sink;
    static class Control implements BehaviorControl<LivingEntity> {
        Behavior.Status status = Behavior.Status.STOPPED;
        public Behavior.Status getStatus() { return status; }
        public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(); }
        public boolean tryStart(ServerLevel level, LivingEntity body, long time) { status = Behavior.Status.RUNNING; return true; }
        public void tickOrStop(ServerLevel level, LivingEntity body, long time) { ticks++; }
        public void doStop(ServerLevel level, LivingEntity body, long time) { status = Behavior.Status.STOPPED; }
        public String debugString() { return "bench"; }
    }
    public static void main(String[] args) {
        var allocation = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        var gates = new ArrayList<GateBehavior<LivingEntity>>();
        for (int i = 0; i < 1024; i++) {
            List<Pair<? extends BehaviorControl<? super LivingEntity>, Integer>> children = new ArrayList<>();
            for (int child = 0; child < 8; child++) children.add(Pair.of(new Control(), 1));
            var gate = new GateBehavior<LivingEntity>(Map.of(), Set.of(), GateBehavior.OrderPolicy.ORDERED,
                i % 2 == 0 ? GateBehavior.RunningPolicy.RUN_ONE : GateBehavior.RunningPolicy.TRY_ALL, children);
            gate.tryStart(null, null, 0);
            gates.add(gate);
        }
        for (int round = 0; round < 12; round++) {
            ticks = 0;
            long allocatedBefore = allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int step = 0; step < 1000; step++) for (var gate : gates) gate.tickOrStop(null, null, step);
            sink = ticks;
            long elapsed = System.nanoTime() - start;
            long allocated = allocation.getThreadAllocatedBytes(thread) - allocatedBefore;
            System.out.printf("round %d: %.2f ns/gate tick, checksum %d, %.2f bytes/tick%n", round,
                elapsed / (1000.0 * gates.size()), ticks, allocated / (1000.0 * gates.size()));
        }
    }
}
