import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;

/** Fixed goal mix; run against both installations with the same JVM and compare steady rounds. */
public class GoalSelectorBench {
    static volatile long sink;
    static long ticks;
    static class BenchGoal extends Goal {
        final boolean usable;
        BenchGoal(int id) {
            usable = id % 3 != 0;
            setFlags(switch (id % 5) {
                case 0 -> EnumSet.noneOf(Flag.class);
                case 1 -> EnumSet.of(Flag.MOVE, Flag.LOOK);
                case 2 -> EnumSet.of(Flag.LOOK);
                case 3 -> EnumSet.of(Flag.JUMP);
                default -> EnumSet.of(Flag.TARGET);
            });
        }
        public boolean canUse() { return usable; }
        public boolean canContinueToUse() { return true; }
        public void tick() { ticks++; }
    }
    public static void main(String[] args) {
        GoalSelector[] selectors = new GoalSelector[1024];
        for (int i = 0; i < selectors.length; i++) {
            var selector = selectors[i] = new GoalSelector();
            for (int goal = 0; goal < 12; goal++) selector.addGoal(goal, new BenchGoal(goal));
            if (i % 4 == 0) selector.disableControlFlag(Goal.Flag.LOOK);
        }
        for (int round = 0; round < 12; round++) {
            ticks = 0;
            long start = System.nanoTime();
            for (int step = 0; step < 1000; step++) for (var selector : selectors) selector.tick();
            sink = ticks;
            System.out.printf("round %d: %.2f ns/tick, checksum %d%n", round,
                (System.nanoTime() - start) / (1000.0 * selectors.length), ticks);
        }
    }
}
