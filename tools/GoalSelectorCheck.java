import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

/** Compare every callback and running state against the pre-optimization selector. */
public class GoalSelectorCheck {
    // Unmodified selector logic from d2d53e5; kept independent of the optimized implementation.
    static class Reference {
        private static final WrappedGoal NO_GOAL = new WrappedGoal(Integer.MAX_VALUE, new Goal() {
            @Override
            public boolean canUse() {
                return false;
            }
        }) {
            @Override
            public boolean isRunning() {
                return false;
            }
        };
        private final Map<Goal.Flag, WrappedGoal> lockedFlags = new EnumMap<>(Goal.Flag.class);
        private final Set<WrappedGoal> availableGoals = new ObjectLinkedOpenHashSet<>();
        private final EnumSet<Goal.Flag> disabledFlags = EnumSet.noneOf(Goal.Flag.class);

        public void addGoal(final int prio, final Goal goal) {
            this.availableGoals.add(new WrappedGoal(prio, goal));
        }

        public void removeAllGoals(final Predicate<Goal> predicate) {
            for (WrappedGoal availableGoal : this.availableGoals) {
                if (predicate.test(availableGoal.getGoal()) && availableGoal.isRunning()) {
                    availableGoal.stop();
                }
            }

            this.availableGoals.removeIf(goal -> predicate.test(goal.getGoal()));
        }

        public void removeGoal(final Goal toRemove) {
            this.removeAllGoals(goal -> goal == toRemove);
        }

        private static boolean goalContainsAnyFlags(final WrappedGoal goal, final EnumSet<Goal.Flag> disabledFlags) {
            for (Goal.Flag flag : goal.getFlags()) {
                if (disabledFlags.contains(flag)) {
                    return true;
                }
            }

            return false;
        }

        private static boolean goalCanBeReplacedForAllFlags(final WrappedGoal goal, final Map<Goal.Flag, WrappedGoal> lockedFlags) {
            for (Goal.Flag flag : goal.getFlags()) {
                if (!lockedFlags.getOrDefault(flag, NO_GOAL).canBeReplacedBy(goal)) {
                    return false;
                }
            }

            return true;
        }

        public void tick() {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("goalCleanup");

            for (WrappedGoal goal : this.availableGoals) {
                if (goal.isRunning() && (goalContainsAnyFlags(goal, this.disabledFlags) || !goal.canContinueToUse())) {
                    goal.stop();
                }
            }

            this.lockedFlags.entrySet().removeIf(entry -> !entry.getValue().isRunning());
            profiler.pop();
            profiler.push("goalUpdate");

            for (WrappedGoal goal : this.availableGoals) {
                if (!goal.isRunning() && !goalContainsAnyFlags(goal, this.disabledFlags) && goalCanBeReplacedForAllFlags(goal, this.lockedFlags) && goal.canUse()) {
                    for (Goal.Flag flag : goal.getFlags()) {
                        WrappedGoal currentGoal = this.lockedFlags.getOrDefault(flag, NO_GOAL);
                        currentGoal.stop();
                        this.lockedFlags.put(flag, goal);
                    }

                    goal.start();
                }
            }

            profiler.pop();
            this.tickRunningGoals(true);
        }

        public void tickRunningGoals(final boolean forceTickAllRunningGoals) {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("goalTick");

            for (WrappedGoal goal : this.availableGoals) {
                if (goal.isRunning() && (forceTickAllRunningGoals || goal.requiresUpdateEveryTick())) {
                    goal.tick();
                }
            }

            profiler.pop();
        }

        public Set<WrappedGoal> getAvailableGoals() {
            return this.availableGoals;
        }

        public void disableControlFlag(final Goal.Flag flag) {
            this.disabledFlags.add(flag);
        }

        public void enableControlFlag(final Goal.Flag flag) {
            this.disabledFlags.remove(flag);
        }

        public void setControlFlag(final Goal.Flag flag, final boolean enabled) {
            if (enabled) {
                this.enableControlFlag(flag);
            } else {
                this.disableControlFlag(flag);
            }
        }
    }

    static class TestGoal extends Goal {
        final int id;
        final List<String> events;
        boolean usable, continued, interruptible, everyTick;
        int useMutation = -1;
        TestGoal(int id, List<String> events) { this.id = id; this.events = events; }
        void event(String name) { events.add(id + ":" + name); }
        public boolean canUse() {
            event("use");
            if (useMutation >= 0) setFlags(flags(useMutation));
            return usable;
        }
        public boolean canContinueToUse() { event("continue"); return continued; }
        public boolean isInterruptable() { event("interruptible"); return interruptible; }
        public boolean requiresUpdateEveryTick() { event("every"); return everyTick; }
        public void start() { event("start"); }
        public void stop() { event("stop"); }
        public void tick() { event("tick"); }
    }
    static EnumSet<Goal.Flag> flags(int mask) {
        EnumSet<Goal.Flag> flags = EnumSet.noneOf(Goal.Flag.class);
        for (Goal.Flag flag : Goal.Flag.values()) if ((mask & 1 << flag.ordinal()) != 0) flags.add(flag);
        return flags;
    }
    static void checkState(GoalSelector actual, Reference reference) {
        var left = new ArrayList<>(actual.getAvailableGoals());
        var right = new ArrayList<>(reference.getAvailableGoals());
        if (left.size() != right.size()) throw new AssertionError("Membership differs");
        for (int i = 0; i < left.size(); i++) {
            var a = left.get(i); var b = right.get(i);
            if (((TestGoal)a.getGoal()).id != ((TestGoal)b.getGoal()).id
                || a.isRunning() != b.isRunning() || !a.getFlags().equals(b.getFlags()))
                throw new AssertionError("Order, running state or flags differ");
        }
    }
    public static void main(String[] args) {
        for (int seed = 0; seed < 16; seed++) {
            Random random = new Random(seed);
            GoalSelector actual = new GoalSelector();
            Reference reference = new Reference();
            List<String> left = new ArrayList<>(), right = new ArrayList<>();
            TestGoal[] a = new TestGoal[24], b = new TestGoal[24];
            for (int id = 0; id < a.length; id++) {
                a[id] = new TestGoal(id, left); b[id] = new TestGoal(id, right);
                a[id].usable = b[id].usable = id % 3 != 0;
                a[id].continued = b[id].continued = true;
                a[id].interruptible = b[id].interruptible = id % 5 != 0;
                a[id].setFlags(flags(id % 16)); b[id].setFlags(flags(id % 16));
                int priority = id == 23 ? Integer.MAX_VALUE : id % 6;
                actual.addGoal(priority, a[id]); reference.addGoal(priority, b[id]);
            }
            for (int round = 0; round < 3000; round++) {
                int id = random.nextInt(a.length), mask = random.nextInt(16);
                boolean value = random.nextBoolean();
                switch (random.nextInt(10)) {
                    case 0 -> { a[id].usable = b[id].usable = value; }
                    case 1 -> { a[id].continued = b[id].continued = value; }
                    case 2 -> { a[id].interruptible = b[id].interruptible = value; }
                    case 3 -> { a[id].everyTick = b[id].everyTick = value; }
                    case 4 -> { a[id].setFlags(flags(mask)); b[id].setFlags(flags(mask)); }
                    case 5 -> { // Direct mutations of the public set must remain visible.
                        a[id].getFlags().clear(); b[id].getFlags().clear();
                        a[id].getFlags().addAll(flags(mask)); b[id].getFlags().addAll(flags(mask));
                    }
                    case 6 -> {
                        Goal.Flag flag = Goal.Flag.values()[random.nextInt(4)];
                        actual.setControlFlag(flag, value); reference.setControlFlag(flag, value);
                    }
                    case 7 -> {
                        actual.removeGoal(a[id]); reference.removeGoal(b[id]);
                        if (value) {
                            int priority = random.nextInt(6);
                            actual.addGoal(priority, a[id]); reference.addGoal(priority, b[id]);
                            // Duplicate additions must not change insertion order or priority.
                            actual.addGoal(priority + 1, a[id]); reference.addGoal(priority + 1, b[id]);
                        }
                    }
                    case 8 -> { a[id].useMutation = b[id].useMutation = value ? mask : -1; }
                    case 9 -> {
                        if (value) {
                            actual.removeAllGoals(goal -> ((TestGoal)goal).id % 4 == id % 4);
                            reference.removeAllGoals(goal -> ((TestGoal)goal).id % 4 == id % 4);
                        }
                    }
                }
                if (random.nextInt(3) == 0) {
                    actual.tickRunningGoals(value); reference.tickRunningGoals(value);
                } else {
                    actual.tick(); reference.tick();
                }
                if (!left.equals(right)) throw new AssertionError("Callback trace differs at " + seed + "/" + round + "\n" + left + "\n" + right);
                checkState(actual, reference);
                left.clear(); right.clear();
            }
        }
        System.out.println("GoalSelectorCheck passed: 48,000 differential rounds, all flag masks, priorities, preemption, callbacks, mutable flags and removal/rejoin");
    }
}
