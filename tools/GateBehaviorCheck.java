import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.cow.Cow;
import sun.misc.Unsafe;

/** Differential gate lifecycle check, including callbacks that change an earlier child. */
public class GateBehaviorCheck {
    // Unmodified gate logic from 0c45f7d.
    static class Reference<E extends LivingEntity> implements BehaviorControl<E> {
        private final Map<MemoryModuleType<?>, MemoryStatus> entryCondition;
        private final Set<MemoryModuleType<?>> exitErasedMemories;
        private final Reference.OrderPolicy orderPolicy;
        private final Reference.RunningPolicy runningPolicy;
        private final ShufflingList<BehaviorControl<? super E>> behaviors = new ShufflingList<>();
        private Behavior.Status status = Behavior.Status.STOPPED;

        public Reference(
            final Map<MemoryModuleType<?>, MemoryStatus> entryCondition,
            final Set<MemoryModuleType<?>> exitErasedMemories,
            final Reference.OrderPolicy orderPolicy,
            final Reference.RunningPolicy runningPolicy,
            final List<Pair<? extends BehaviorControl<? super E>, Integer>> behaviors
        ) {
            this.entryCondition = entryCondition;
            this.exitErasedMemories = exitErasedMemories;
            this.orderPolicy = orderPolicy;
            this.runningPolicy = runningPolicy;
            behaviors.forEach(entry -> this.behaviors.add(entry.getFirst(), entry.getSecond()));
        }

        @Override
        public Behavior.Status getStatus() {
            return this.status;
        }

        @Override
        public Set<MemoryModuleType<?>> getRequiredMemories() {
            Set<MemoryModuleType<?>> memories = new HashSet<>(this.entryCondition.keySet());

            for (BehaviorControl<? super E> behavior : this.behaviors) {
                memories.addAll(behavior.getRequiredMemories());
            }

            return memories;
        }

        private boolean hasRequiredMemories(final E body) {
            for (Entry<MemoryModuleType<?>, MemoryStatus> entry : this.entryCondition.entrySet()) {
                MemoryModuleType<?> memoryType = entry.getKey();
                MemoryStatus requiredStatus = entry.getValue();
                if (!body.getBrain().checkMemory(memoryType, requiredStatus)) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public final boolean tryStart(final ServerLevel level, final E body, final long timestamp) {
            if (this.hasRequiredMemories(body)) {
                this.status = Behavior.Status.RUNNING;
                this.orderPolicy.apply(this.behaviors);
                this.runningPolicy.apply(this.behaviors.stream(), level, body, timestamp);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public final void tickOrStop(final ServerLevel level, final E body, final long timestamp) {
            this.behaviors.stream().filter(goal -> goal.getStatus() == Behavior.Status.RUNNING).forEach(goal -> goal.tickOrStop(level, body, timestamp));
            if (this.behaviors.stream().noneMatch(g -> g.getStatus() == Behavior.Status.RUNNING)) {
                this.doStop(level, body, timestamp);
            }
        }

        @Override
        public final void doStop(final ServerLevel level, final E body, final long timestamp) {
            this.status = Behavior.Status.STOPPED;
            this.behaviors.stream().filter(goal -> goal.getStatus() == Behavior.Status.RUNNING).forEach(goal -> goal.doStop(level, body, timestamp));
            this.exitErasedMemories.forEach(body.getBrain()::eraseMemory);
        }

        @Override
        public String debugString() {
            Set<String> runningBehaviours = this.behaviors
                .stream()
                .filter(goal -> goal.getStatus() == Behavior.Status.RUNNING)
                .map(b -> b.getClass().getSimpleName())
                .collect(Collectors.toSet());
            return this.getClass().getSimpleName() + ": " + runningBehaviours;
        }

        public enum OrderPolicy {
            ORDERED(t -> {}),
            SHUFFLED(ShufflingList::shuffle);

            private final Consumer<ShufflingList<?>> consumer;

            OrderPolicy(final Consumer<ShufflingList<?>> consumer) {
                this.consumer = consumer;
            }

            public void apply(final ShufflingList<?> list) {
                this.consumer.accept(list);
            }
        }

        public enum RunningPolicy {
            RUN_ONE {
                @Override
                public <E extends LivingEntity> void apply(
                    final Stream<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
                ) {
                    behaviors.filter(goal -> goal.getStatus() == Behavior.Status.STOPPED).filter(goal -> goal.tryStart(level, body, timestamp)).findFirst();
                }
            },
            TRY_ALL {
                @Override
                public <E extends LivingEntity> void apply(
                    final Stream<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
                ) {
                    behaviors.filter(goal -> goal.getStatus() == Behavior.Status.STOPPED).forEach(goal -> goal.tryStart(level, body, timestamp));
                }
            };

            public abstract <E extends LivingEntity> void apply(
                final Stream<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
            );
        }
    }

    static class Body extends Cow {
        Brain<LivingEntity> testBrain;
        int brainReads;
        Body() { super(EntityTypes.COW, null); }
        public Brain<LivingEntity> getBrain() { brainReads++; return testBrain; }
    }
    static class Control implements BehaviorControl<LivingEntity> {
        final int id;
        final List<String> events;
        Control[] peers;
        Behavior.Status status = Behavior.Status.STOPPED;
        boolean usable = true, stopOnTick, startWithoutRunning;
        int startPeer = -1, stopPeer = -1, restartOnStop = -1;
        Control(int id, List<String> events) { this.id = id; this.events = events; }
        void event(String name) { events.add(id + ":" + name); }
        public Behavior.Status getStatus() { event("status"); return status; }
        public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(MemoryModuleType.WALK_TARGET); }
        public boolean tryStart(ServerLevel level, LivingEntity body, long time) {
            event("start");
            if (usable && !startWithoutRunning) status = Behavior.Status.RUNNING;
            return usable;
        }
        public void tickOrStop(ServerLevel level, LivingEntity body, long time) {
            event("tick");
            if (stopOnTick) status = Behavior.Status.STOPPED;
            if (startPeer >= 0) peers[startPeer].status = Behavior.Status.RUNNING;
            if (stopPeer >= 0) peers[stopPeer].status = Behavior.Status.STOPPED;
        }
        public void doStop(ServerLevel level, LivingEntity body, long time) {
            event("stop"); status = Behavior.Status.STOPPED;
            if (restartOnStop >= 0) peers[restartOnStop].status = Behavior.Status.RUNNING;
        }
        public String debugString() { return "control"; }
    }
    static void seed(Object gate, long seed) throws Exception {
        Field behaviors = gate.getClass().getDeclaredField("behaviors");
        behaviors.setAccessible(true);
        Field random = ShufflingList.class.getDeclaredField("random");
        random.setAccessible(true);
        random.set(behaviors.get(gate), RandomSource.create(seed));
    }
    static Body body(Unsafe unsafe) throws Exception {
        Body body = (Body)unsafe.allocateInstance(Body.class);
        body.testBrain = new Brain<>();
        var register = Brain.class.getDeclaredMethod("registerMemory", MemoryModuleType.class);
        register.setAccessible(true);
        register.invoke(body.testBrain, MemoryModuleType.ATTACK_COOLING_DOWN);
        register.invoke(body.testBrain, MemoryModuleType.WALK_TARGET);
        return body;
    }
    static void compare(GateBehavior<LivingEntity> actual, Reference<LivingEntity> expected,
                        Control[] a, Control[] b, List<String> left, List<String> right, Body ba, Body bb) {
        if (!left.equals(right)) throw new AssertionError("Callback order differs\n" + left + "\n" + right);
        if (actual.getStatus() != expected.getStatus() || ba.brainReads != bb.brainReads
            || ba.testBrain.hasMemoryValue(MemoryModuleType.ATTACK_COOLING_DOWN) != bb.testBrain.hasMemoryValue(MemoryModuleType.ATTACK_COOLING_DOWN))
            throw new AssertionError("Gate state, brain access or exit memory differs");
        for (int i = 0; i < a.length; i++) if (a[i].status != b[i].status) throw new AssertionError("Child state differs");
        left.clear(); right.clear(); ba.brainReads = bb.brainReads = 0;
    }
    static class Weighted {
        final int id, weight;
        double key;
        Weighted(int id, int weight) { this.id = id; this.weight = weight; }
    }
    static void checkShuffle() throws Exception {
        Field randomField = ShufflingList.class.getDeclaredField("random");
        randomField.setAccessible(true);
        for (int seed = 0; seed < 32; seed++) {
            var actual = new ShufflingList<Integer>();
            var expected = new ArrayList<Weighted>();
            int[] weights = {1, 2, 3, 7, 0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
            for (int i = 0; i < seed; i++) {
                actual.add(i, weights[i % weights.length]);
                expected.add(new Weighted(i, weights[i % weights.length]));
            }
            randomField.set(actual, RandomSource.create(seed));
            var random = RandomSource.create(seed);
            for (int round = 0; round < 100; round++) {
                for (Weighted entry : expected) entry.key = -Math.pow(random.nextFloat(), 1.0F / entry.weight);
                expected.sort(Comparator.comparingDouble(entry -> entry.key));
                actual.shuffle();
                List<Integer> ids = expected.stream().map(entry -> entry.id).toList();
                List<Integer> iterated = new ArrayList<>();
                actual.forEach(iterated::add);
                if (!ids.equals(actual.stream().toList()) || !ids.equals(iterated) || actual.size() != ids.size()) {
                    throw new AssertionError("Seeded weighted shuffle differs");
                }
                for (int i = 0; i < ids.size(); i++) if (!ids.get(i).equals(actual.get(i))) throw new AssertionError("Indexed order differs");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        checkShuffle();
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe)field.get(null);
        for (GateBehavior.RunningPolicy policy : GateBehavior.RunningPolicy.values()) {
            for (GateBehavior.OrderPolicy order : GateBehavior.OrderPolicy.values()) {
                for (int seed = 0; seed < 16; seed++) {
                    Random random = new Random(seed);
                    List<String> left = new ArrayList<>(), right = new ArrayList<>();
                    Control[] a = new Control[seed % 7], b = new Control[a.length];
                    List<Pair<? extends BehaviorControl<? super LivingEntity>, Integer>> la = new ArrayList<>(), lb = new ArrayList<>();
                    for (int i = 0; i < a.length; i++) {
                        a[i] = new Control(i, left); b[i] = new Control(i, right);
                        a[i].peers = a; b[i].peers = b;
                        la.add(Pair.of(a[i], i % 3 + 1)); lb.add(Pair.of(b[i], i % 3 + 1));
                    }
                    Map<MemoryModuleType<?>, MemoryStatus> entry = seed % 2 == 0 ? Map.of()
                        : Map.of(MemoryModuleType.ATTACK_COOLING_DOWN, MemoryStatus.VALUE_PRESENT);
                    Set<MemoryModuleType<?>> exit = Set.of(MemoryModuleType.ATTACK_COOLING_DOWN);
                    var actual = new GateBehavior<LivingEntity>(entry, exit, order, policy, la);
                    var expected = new Reference<LivingEntity>(entry, exit, Reference.OrderPolicy.valueOf(order.name()),
                        Reference.RunningPolicy.valueOf(policy.name()), lb);
                    seed(actual, seed); seed(expected, seed);
                    Body ba = body(unsafe), bb = body(unsafe);
                    for (int round = 0; round < 500; round++) {
                        if (a.length > 0) {
                            int id = random.nextInt(a.length), peer = random.nextInt(a.length);
                            boolean value = random.nextBoolean();
                            switch (random.nextInt(7)) {
                                case 0 -> a[id].usable = b[id].usable = value;
                                case 1 -> a[id].stopOnTick = b[id].stopOnTick = value;
                                case 2 -> a[id].startWithoutRunning = b[id].startWithoutRunning = value;
                                case 3 -> a[id].startPeer = b[id].startPeer = value ? peer : -1;
                                case 4 -> a[id].stopPeer = b[id].stopPeer = value ? peer : -1;
                                case 5 -> a[id].restartOnStop = b[id].restartOnStop = value ? peer : -1;
                                case 6 -> a[id].status = b[id].status = value ? Behavior.Status.RUNNING : Behavior.Status.STOPPED;
                            }
                        }
                        switch (random.nextInt(4)) {
                            case 0 -> {
                                boolean sa = actual.tryStart(null, ba, round), sb = expected.tryStart(null, bb, round);
                                if (sa != sb) throw new AssertionError("Start result differs");
                            }
                            case 1 -> { actual.tickOrStop(null, ba, round); expected.tickOrStop(null, bb, round); }
                            case 2 -> { actual.doStop(null, ba, round); expected.doStop(null, bb, round); }
                            case 3 -> {
                                ba.testBrain.setMemory(MemoryModuleType.ATTACK_COOLING_DOWN, true);
                                bb.testBrain.setMemory(MemoryModuleType.ATTACK_COOLING_DOWN, true);
                            }
                        }
                        compare(actual, expected, a, b, left, right, ba, bb);
                        if (!actual.getRequiredMemories().equals(expected.getRequiredMemories())) throw new AssertionError("Required memories differ");
                    }
                }
            }
        }
        // A child that was already visited gets reactivated by the last child. A one-pass
        // running flag incorrectly stops this gate and clears its exit memory.
        var events = new ArrayList<String>();
        Control[] children = {new Control(0, events), new Control(1, events)};
        for (var child : children) child.peers = children;
        children[0].usable = false;
        children[1].stopOnTick = true; children[1].startPeer = 0;
        var gate = new GateBehavior<LivingEntity>(Map.of(), Set.of(MemoryModuleType.ATTACK_COOLING_DOWN),
            GateBehavior.OrderPolicy.ORDERED, GateBehavior.RunningPolicy.TRY_ALL,
            List.of(Pair.of(children[0], 1), Pair.of(children[1], 1)));
        Body fixture = body(unsafe);
        fixture.testBrain.setMemory(MemoryModuleType.ATTACK_COOLING_DOWN, true);
        gate.tryStart(null, fixture, 0); gate.tickOrStop(null, fixture, 1);
        if (gate.getStatus() != Behavior.Status.RUNNING || children[0].status != Behavior.Status.RUNNING
            || !fixture.testBrain.hasMemoryValue(MemoryModuleType.ATTACK_COOLING_DOWN)) throw new AssertionError("Reactivation stopped the gate");
        System.out.println("GateBehaviorCheck passed: 32,000 differential lifecycles, policies, seeded shuffle, empty gates, callback order, sibling changes, entry/exit memories");
        System.exit(0);
    }
}
