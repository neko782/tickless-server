import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

/** Repeated unsuccessful POI searches and searches with records across several heights. */
public class PoiChunkCacheBench {
    static volatile long sink;

    public static void main(String[] args) throws Exception {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            int queries = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
            int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 8;
            Path dir = Files.createTempDirectory(Path.of("build/checks"), "poi-bench-");
            try (var manager = PoiChunkCacheCheck.open(dir)) {
                BlockPos center = new BlockPos(0, 80, 0);
                measure(manager, center, queries, rounds, "empty");
                var home = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME);
                for (int x = -32; x <= 32; x += 16) {
                    for (int z = -32; z <= 32; z += 16) {
                        manager.add(new BlockPos(x, 0, z), home);
                        manager.add(new BlockPos(x, 80, z), home);
                        manager.add(new BlockPos(x, 160, z), home);
                    }
                }
                measure(manager, center, queries, rounds, "populated");
            }
        } finally {
            net.minecraft.util.Util.shutdownExecutors();
        }
    }

    private static void measure(PoiManager manager, BlockPos center, int queries, int rounds, String label) {
        // Force the initial region reads and snapshot creation before timing.
        long expected = manager.getInRange(type -> true, center, 48, PoiManager.Occupancy.ANY).count();
        for (int round = 0; round < rounds; round++) {
            long checksum = 0;
            long start = System.nanoTime();
            for (int i = 0; i < queries; i++) {
                checksum += manager.getInRange(type -> true, center, 48, PoiManager.Occupancy.ANY).count();
            }
            long elapsed = System.nanoTime() - start;
            if (checksum != expected * queries) throw new AssertionError("POI count changed");
            sink = checksum;
            System.out.printf("%s round %d: %.1f ns/query, checksum %d%n", label, round, (double)elapsed / queries, checksum);
        }
    }
}
