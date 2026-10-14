import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;

/** A dense, stationary section; one cache rebuild per simulated step, querying every mob. */
public class EntitySectionBench {
    static volatile long sink;
    public static void main(String[] args) {
        double inflation = args.length > 0 ? Double.parseDouble(args[0]) : 0.0;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 900;
        int steps = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        AtomicLong clock = new AtomicLong();
        var section = new EntitySection<>(EntitySectionCheck.TestEntity.class, Visibility.TICKING, clock::get);
        Random random = new Random(42);
        AABB[] queries = new AABB[count];
        for (int i = 0; i < queries.length; i++) {
            double x = random.nextDouble() * 15, z = random.nextDouble() * 15;
            queries[i] = new AABB(x, 0, z, x + 0.6, 1.8, z + 0.6);
            section.add(new EntitySectionCheck.TestEntity(i, queries[i]));
            queries[i] = queries[i].inflate(inflation);
        }
        long[] hits = {0};
        net.minecraft.util.AbortableIterationConsumer<EntitySectionCheck.TestEntity> consumer = entity -> {
            hits[0] += entity.id;
            return Continuation.CONTINUE;
        };
        for (int round = 0; round < rounds; round++) {
            long start = System.nanoTime();
            for (int step = 0; step < steps; step++) {
                clock.incrementAndGet();
                for (AABB query : queries) section.getEntities(query, consumer);
            }
            sink = hits[0];
            System.out.printf("round %d: %.1f ns/query, checksum %d%n", round,
                (System.nanoTime() - start) / ((double)steps * queries.length), hits[0]);
        }
    }
}
