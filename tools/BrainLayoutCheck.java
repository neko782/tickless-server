import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;

/** Verify traversal order, shared behaviors, dynamic status and layout invalidation. */
public class BrainLayoutCheck {
    static class Control implements BehaviorControl<LivingEntity> {
        Behavior.Status status = Behavior.Status.STOPPED;
        public Behavior.Status getStatus() { return status; }
        public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(); }
        public boolean tryStart(ServerLevel level, LivingEntity body, long time) { status = Behavior.Status.RUNNING; return true; }
        public void tickOrStop(ServerLevel level, LivingEntity body, long time) {}
        public void doStop(ServerLevel level, LivingEntity body, long time) { status = Behavior.Status.STOPPED; }
        public String debugString() { return "test"; }
    }

    static void verify(Brain<LivingEntity> brain, Map<Integer, Map<Activity, Set<Control>>> reference) {
        List<Control> expected = new ArrayList<>();
        for (var byActivity : reference.values()) for (var behaviors : byActivity.values()) {
            for (var control : behaviors) if (control.status == Behavior.Status.RUNNING) expected.add(control);
        }
        if (!brain.getRunningBehaviors().equals(expected)) throw new AssertionError("Running order or membership differs");
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var brain = new Brain<LivingEntity>();
        Map<Integer, Map<Activity, Set<Control>>> reference = new TreeMap<>();
        Activity[] activities = {Activity.CORE, Activity.IDLE, Activity.WORK, Activity.REST, Activity.PANIC};
        Control[] controls = new Control[100];
        for (int i = 0; i < controls.length; i++) controls[i] = new Control();
        Random random = new Random(42);
        for (int round = 0; round < 500; round++) {
            if (round % 57 == 0) {
                brain.removeAllBehaviors();
                reference.clear();
            }
            Activity activity = activities[random.nextInt(activities.length)];
            int priority = random.nextInt(12);
            Control control = controls[random.nextInt(controls.length)];
            brain.addActivity(activity, ImmutableList.of(Pair.of(priority, control)), Set.of(), Set.of());
            reference.computeIfAbsent(priority, k -> new HashMap<>()).computeIfAbsent(activity, k -> new LinkedHashSet<>()).add(control);
            for (var c : controls) c.status = random.nextBoolean() ? Behavior.Status.RUNNING : Behavior.Status.STOPPED;
            verify(brain, reference);
            // The running list is a snapshot: status changes affect the next call, not its membership.
            var snapshot = brain.getRunningBehaviors();
            var copy = new ArrayList<>(snapshot);
            for (var c : controls) c.status = Behavior.Status.STOPPED;
            verify(brain, reference);
            if (!snapshot.equals(copy)) throw new AssertionError("Running snapshot mutated");
        }
        System.out.println("BrainLayoutCheck passed: priority/activity order, duplicates, status changes, snapshots, add and clear invalidation");
    }
}
