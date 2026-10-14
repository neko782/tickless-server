import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;

/** Compare the spatial cache with live brute-force queries, including concurrent rebuilds. */
public class EntitySectionCheck {
    static class TestEntity implements EntityAccess {
        final int id;
        volatile AABB box;
        TestEntity(int id, AABB box) { this.id = id; this.box = box; }
        public int getId() { return id; }
        public UUID getUUID() { return new UUID(0, id); }
        public boolean isRemoved() { return false; }
        public BlockPos blockPosition() { return BlockPos.containing(box.minX, box.minY, box.minZ); }
        public AABB getBoundingBox() { return box; }
        public void setLevelCallback(EntityInLevelCallback callback) {}
        public Stream<TestEntity> getSelfAndPassengers() { return Stream.of(this); }
        public Stream<TestEntity> getPassengersAndSelf() { return Stream.of(this); }
        public void setRemoved(Entity.RemovalReason reason) {}
        volatile long sectionStamp;
        public long sectionStamp() { return sectionStamp; }
        public void setSectionStamp(long stamp) { sectionStamp = stamp; }
        public boolean shouldBeSaved() { return true; }
        public boolean isAlwaysTicking() { return false; }
    }
    static class OtherEntity extends TestEntity {
        OtherEntity(int id, AABB box) { super(id, box); }
    }

    static void verify(EntitySection<TestEntity> section, List<TestEntity> all, AABB query) {
        Set<TestEntity> expected = new HashSet<>();
        for (TestEntity entity : all) if (entity.box.intersects(query)) expected.add(entity);
        Set<TestEntity> actual = new HashSet<>();
        section.getEntities(query, e -> {
            if (!actual.add(e)) throw new AssertionError("Duplicate entity");
            return Continuation.CONTINUE;
        });
        if (!actual.equals(expected)) throw new AssertionError("Query differs: " + query);
        Set<TestEntity> typed = new HashSet<>();
        section.getEntities(EntityTypeTest.forClass(OtherEntity.class), query, e -> {
            typed.add(e);
            return Continuation.CONTINUE;
        });
        expected.removeIf(e -> !(e instanceof OtherEntity));
        if (!typed.equals(expected)) throw new AssertionError("Typed query differs");
        Set<TestEntity> baseMatches = new HashSet<>();
        section.getEntities(EntityTypeTest.forClass(TestEntity.class), query, e -> {
            baseMatches.add(e);
            return Continuation.CONTINUE;
        });
        Set<TestEntity> exactMatches = new HashSet<>();
        section.getEntities(EntityTypeTest.forExactClass(TestEntity.class), query, e -> {
            exactMatches.add(e);
            return Continuation.CONTINUE;
        });
        Set<TestEntity> baseExpected = new HashSet<>(), exactExpected = new HashSet<>();
        for (TestEntity e : all) if (e.box.intersects(query)) {
            baseExpected.add(e);
            if (e.getClass() == TestEntity.class) exactExpected.add(e);
        }
        if (!baseMatches.equals(baseExpected) || !exactMatches.equals(exactExpected)) {
            throw new AssertionError("All-members class proof or exact-class predicate differs");
        }
        int[] visits = {0};
        Continuation result = section.getEntities(query, e -> { visits[0]++; return Continuation.ABORT; });
        if (visits[0] > 1 || (visits[0] == 1) != result.shouldAbort()) throw new AssertionError("Abort ignored");
    }

    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong();
        EntitySection<TestEntity> section = new EntitySection<>(TestEntity.class, Visibility.TICKING, clock::get);
        List<TestEntity> all = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < 900; i++) {
            double x = -16 + random.nextDouble() * 16, z = -16 + random.nextDouble() * 16;
            double width = i % 29 == 0 ? 8 : 0.6;
            AABB box = new AABB(x, 0, z, x + width, 1.8, z + width);
            TestEntity entity = i % 3 == 0 ? new TestEntity(i, box) : new OtherEntity(i, box);
            all.add(entity);
            section.add(entity);
        }
        List<AABB> queries = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            double x = -24 + random.nextDouble() * 40, z = -24 + random.nextDouble() * 40;
            queries.add(new AABB(x, -1, z, x + random.nextDouble() * 6, 2, z + random.nextDouble() * 6));
        }
        queries.add(new AABB(-16, 0, -16, -14, 1, -14));
        queries.add(new AABB(-100, -100, -100, 100, 100, 100));
        queries.add(new AABB(-1.0E20, -100, -1.0E20, 1.0E20, 100, 1.0E20));
        queries.add(new AABB(1.0E20, 0, 1.0E20, 2.0E20, 2, 2.0E20));
        for (AABB query : queries) verify(section, all, query);
        for (int i = 0; i < 100; i++) all.get(i).box = all.get(i).box.move(0.9, 0, -1.1);
        clock.incrementAndGet();
        for (AABB query : queries) verify(section, all, query);
        section.remove(all.remove(0));
        for (AABB query : queries) verify(section, all, query);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 16; t++) futures.add(pool.submit(() -> {
                for (int i = 0; i < queries.size(); i++) {
                    if (i % 17 == 0) clock.incrementAndGet();
                    verify(section, all, queries.get(i));
                }
            }));
            for (var future : futures) future.get();
        }
        TestEntity distant = new TestEntity(1000, new AABB(10000, 0, 10000, 10001, 1, 10001));
        all.add(distant);
        section.add(distant);
        verify(section, all, new AABB(-20000, -1, -20000, 20000, 2, 20000));
        section.remove(distant);
        all.remove(distant);
        while (all.size() > 11) section.remove(all.remove(all.size() - 1));
        for (AABB query : queries) verify(section, all, query);
        // Moving entities must become visible while the simulation clock is frozen: the level's
        // clock then advances with maintenance runs instead of game time.
        AtomicLong maintenance = new AtomicLong();
        var frozen = new EntitySection<>(TestEntity.class, Visibility.TICKING, () -> maintenance.get() << 40);
        var frozenEntities = new ArrayList<TestEntity>();
        for (int i = 0; i < 20; i++) {
            var entity = new TestEntity(i, new AABB(0, 0, 0, 0.6, 1.8, 0.6));
            frozenEntities.add(entity);
            frozen.add(entity);
        }
        verify(frozen, frozenEntities, new AABB(-1, -1, -1, 1, 2, 1));
        frozenEntities.getFirst().box = new AABB(8, 0, 8, 8.6, 1.8, 8.6);
        maintenance.incrementAndGet();
        verify(frozen, frozenEntities, new AABB(7, -1, 7, 9, 2, 9));
        // Membership changes within one step reuse the cache: joined entities come from the tail of
        // the membership snapshot, departed ones are dropped by their stamp, and a rejoin or a move
        // between sections is visited once.
        AtomicLong stepClock = new AtomicLong(1);
        var churn = new EntitySection<>(TestEntity.class, Visibility.TICKING, stepClock::get);
        var other = new EntitySection<>(TestEntity.class, Visibility.TICKING, stepClock::get);
        var members = new ArrayList<TestEntity>();
        var otherMembers = new ArrayList<TestEntity>();
        for (int i = 0; i < 60; i++) {
            double x = random.nextDouble() * 16, z = random.nextDouble() * 16;
            // Only OtherEntity at first, so the typed query takes the all-members cast proof.
            var entity = new OtherEntity(2000 + i, new AABB(x, 0, z, x + 0.6, 1.8, z + 0.6));
            members.add(entity);
            churn.add(entity);
        }
        AABB everything = new AABB(-1, -1, -1, 17, 3, 17);
        verify(churn, members, everything);
        for (int round = 0; round < 40; round++) {
            switch (round % 5) {
                case 0 -> {
                    double x = random.nextDouble() * 16, z = random.nextDouble() * 16;
                    var joined = round % 2 == 0 ? new TestEntity(3000 + round, new AABB(x, 0, z, x + 0.6, 1.8, z + 0.6))
                        : new OtherEntity(3000 + round, new AABB(x, 0, z, x + 0.6, 1.8, z + 0.6));
                    members.add(joined);
                    churn.add(joined);
                }
                case 1 -> churn.remove(members.remove(random.nextInt(members.size())));
                case 2 -> {
                    // Leave and join again: now at the tail, its cached entry is stale.
                    var rejoin = members.get(random.nextInt(members.size()));
                    churn.remove(rejoin);
                    churn.add(rejoin);
                }
                case 3 -> {
                    var moved = members.remove(random.nextInt(members.size()));
                    churn.remove(moved);
                    other.add(moved);
                    otherMembers.add(moved);
                }
                default -> {
                    if (!otherMembers.isEmpty()) {
                        var back = otherMembers.remove(0);
                        other.remove(back);
                        churn.add(back);
                        members.add(back);
                    }
                }
            }
            for (int q = 0; q < 50; q++) verify(churn, members, queries.get(q).move(16, 0, 16));
            verify(churn, members, everything);
            verify(churn, members, new AABB(2, -1, 2, 6, 2, 6));
        }
        // Concurrent churn within one step: no duplicates, and nothing that was never a member.
        var ever = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<TestEntity, Boolean>());
        ever.addAll(members);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            futures.add(pool.submit(() -> {
                Random r = new Random(7);
                for (int i = 0; i < 5000; i++) {
                    double x = r.nextDouble() * 16, z = r.nextDouble() * 16;
                    var e = new TestEntity(10000 + i, new AABB(x, 0, z, x + 0.6, 1.8, z + 0.6));
                    ever.add(e);
                    churn.add(e);
                    if (i % 2 == 0) churn.remove(e);
                    if (i % 500 == 0) stepClock.incrementAndGet();
                }
            }));
            for (int t = 0; t < 8; t++) futures.add(pool.submit(() -> {
                for (int i = 0; i < 3000; i++) {
                    Set<TestEntity> seen = new HashSet<>();
                    churn.getEntities(everything, e -> {
                        if (!seen.add(e)) throw new AssertionError("Duplicate entity during churn");
                        if (!ever.contains(e)) throw new AssertionError("Entity that was never a member");
                        return Continuation.CONTINUE;
                    });
                }
            }));
            for (var future : futures) future.get();
        }
        System.out.println("EntitySectionCheck passed: brute-force, typed, boundary, wide-box, membership, fallback, frozen-clock refresh, concurrent rebuild queries and same-step membership churn");
    }
}
