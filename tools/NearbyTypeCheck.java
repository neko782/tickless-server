import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;

/** Typed scans must retain nearest order, duplicates, visibility and concurrent publication. */
public class NearbyTypeCheck {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var constructor = NearestVisibleLivingEntities.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var nearby = constructor.newInstance();
        Random random = new Random(42);
        EntityType<?>[] types = {EntityTypes.COW, EntityTypes.VILLAGER, EntityTypes.ZOMBIE};
        List<LivingEntity> entities = new ArrayList<>();
        Field idField = Entity.class.getDeclaredField("id"); idField.setAccessible(true);
        Field typeField = Entity.class.getDeclaredField("type"); typeField.setAccessible(true);
        for (int i = 0; i < 600; i++) {
            LivingEntity entity = (LivingEntity)BrainMemoryCheck.fixture();
            typeField.set(entity, types[random.nextInt(types.length)]);
            idField.setInt(entity, i + 1);
            entities.add(entity);
            if (i % 11 == 0) entities.add(entity);
        }
        List<LivingEntity> input = new ArrayList<>(entities);
        var copied = new NearestVisibleLivingEntities(null, entities.get(0), input);
        input.clear();
        if (!copied.nearbyEntities().equals(entities)) throw new AssertionError("Caller mutated nearby snapshot");
        try {
            copied.nearbyEntities().clear();
            throw new AssertionError("Published snapshot is mutable");
        } catch (UnsupportedOperationException expected) {}
        Field listField = NearestVisibleLivingEntities.class.getDeclaredField("nearbyEntities"); listField.setAccessible(true);
        listField.set(nearby, List.copyOf(entities));
        Predicate<LivingEntity> visible = entity -> entities.indexOf(entity) % 3 != 0;
        Field sightField = NearestVisibleLivingEntities.class.getDeclaredField("lineOfSightTest"); sightField.setAccessible(true);
        sightField.set(nearby, visible);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int j = 0; j < 16; j++) jobs.add(executor.submit(() -> {
                for (int round = 0; round < 50; round++) {
                    for (EntityType<?> type : types) {
                        Predicate<LivingEntity> filter = entity -> entities.indexOf(entity) % 5 != 0;
                        var expected = entities.stream().filter(entity -> entity.getType() == type).filter(filter).filter(visible).toList();
                        if (!nearby.findAll(type, filter).equals(expected)) throw new AssertionError("Typed list changed");
                        if (!nearby.findClosest(Set.of(type), filter).equals(expected.stream().findFirst())) throw new AssertionError("Closest changed");
                    }
                    var selected = Set.<EntityType<?>>of(EntityTypes.COW, EntityTypes.ZOMBIE);
                    var expected = entities.stream().filter(entity -> selected.contains(entity.getType())).filter(visible).findFirst();
                    if (!nearby.findClosest(selected, entity -> true).equals(expected)) throw new AssertionError("Merged nearest order changed");
                    if (nearby.findClosest(Set.of(EntityTypes.PIG), entity -> true).isPresent()) throw new AssertionError("Absent type found");
                }
            }));
            for (var job : jobs) job.get();
        }
        for (EntityType<?> uniformType : types) {
            List<LivingEntity> uniform = entities.stream().filter(e -> e.getType() == uniformType).toList();
            var uniformNearby = constructor.newInstance();
            listField.set(uniformNearby, uniform);
            sightField.set(uniformNearby, visible);
            for (EntityType<?> requested : types) {
                Predicate<LivingEntity> filter = e -> entities.indexOf(e) % 5 != 0;
                var expected = uniform.stream().filter(e -> e.getType() == requested).filter(filter).filter(visible).toList();
                if (!uniformNearby.findAll(requested, filter).equals(expected)) throw new AssertionError("Uniform typed list changed");
                if (!uniformNearby.findClosest(Set.of(requested), filter).equals(expected.stream().findFirst())) throw new AssertionError("Uniform closest changed");
            }
            List<LivingEntity> expected = uniform.stream().filter(visible).toList();
            if (!uniformNearby.findClosest(Set.of(uniformType, EntityTypes.PIG), e -> true).equals(expected.stream().findFirst())) throw new AssertionError("Uniform multi-type order changed");
        }
        var empty = constructor.newInstance();
        if (!empty.findAll(EntityTypes.COW, e -> true).isEmpty() || empty.findClosest(Set.of(EntityTypes.COW), e -> true).isPresent()) throw new AssertionError("Empty typed query changed");
        System.out.println("NearbyTypeCheck passed: exact filtering, nearest order, duplicates, uniform/empty snapshots and concurrent publication");
        System.exit(0);
    }
}
