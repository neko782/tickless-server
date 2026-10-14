import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/** POI cache membership, live records/tickets, concurrent creation and persistence. */
public class PoiChunkCacheCheck {
    static Set<BlockPos> positions(PoiManager manager, ChunkPos chunk, PoiManager.Occupancy occupancy) {
        return manager.getInChunk(type -> true, chunk, occupancy).map(record -> record.getPos()).collect(java.util.stream.Collectors.toSet());
    }
    static void expect(PoiManager manager, ChunkPos chunk, PoiManager.Occupancy occupancy, Set<BlockPos> expected) {
        if (!positions(manager, chunk, occupancy).equals(expected)) throw new AssertionError("POI membership differs");
        BlockPos center = new BlockPos(chunk.getMiddleBlockX(), 80, chunk.getMiddleBlockZ());
        Set<BlockPos> square = manager.getInSquare(type -> true, center, 16, occupancy)
            .map(record -> record.getPos()).collect(java.util.stream.Collectors.toSet());
        if (!square.equals(expected)) throw new AssertionError("Cached square membership differs");
        Set<BlockPos> range = manager.getInRange(type -> true, center, 24, occupancy)
            .map(record -> record.getPos()).collect(java.util.stream.Collectors.toSet());
        Set<BlockPos> inRange = expected.stream().filter(pos -> pos.distSqr(center) <= 24 * 24)
            .collect(java.util.stream.Collectors.toSet());
        if (!range.equals(inRange)) throw new AssertionError("Cached range geometry differs");
    }
    static PoiManager open(Path dir) {
        return new PoiManager(new RegionStorageInfo("test", Level.OVERWORLD, "poi"), dir, SectionStorageCheck.FIXER, true,
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), SectionStorageCheck.ERRORS, LevelHeightAccessor.create(-64, 384));
    }
    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } finally {
            net.minecraft.util.Util.shutdownExecutors();
        }
    }
    private static void run(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path dir = Files.createTempDirectory(Path.of("build/checks"), "poi-cache-");
        ChunkPos chunk = new ChunkPos(-2, 3);
        var home = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME);
        var farmer = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.FARMER);
        Set<BlockPos> expected = new HashSet<>();
        try (var manager = open(dir)) {
            expect(manager, chunk, PoiManager.Occupancy.ANY, expected);
            BlockPos first = new BlockPos(-31, -60, 49), second = first.offset(1, 0, 0), high = new BlockPos(-31, 300, 49);
            var delayed = manager.getInChunk(type -> true, chunk, PoiManager.Occupancy.ANY);
            var delayedSquare = manager.getInSquare(type -> true,
                new BlockPos(chunk.getMiddleBlockX(), 80, chunk.getMiddleBlockZ()), 16, PoiManager.Occupancy.ANY);
            manager.add(first, home); expected.add(first);
            if (!delayed.map(record -> record.getPos()).collect(java.util.stream.Collectors.toSet()).equals(expected)) {
                throw new AssertionError("Stream bound section membership before consumption");
            }
            if (!delayedSquare.map(record -> record.getPos()).collect(java.util.stream.Collectors.toSet()).equals(expected)) {
                throw new AssertionError("Square bound membership before consumption");
            }
            expect(manager, chunk, PoiManager.Occupancy.ANY, expected);
            manager.add(second, farmer); expected.add(second);
            manager.add(high, home); expected.add(high);
            expect(manager, chunk, PoiManager.Occupancy.ANY, expected);
            var claimed = manager.take(type -> type.is(PoiTypes.FARMER), (type, pos) -> true, second, 1).orElseThrow();
            expect(manager, chunk, PoiManager.Occupancy.IS_OCCUPIED, Set.of(claimed));
            manager.release(claimed);
            expect(manager, chunk, PoiManager.Occupancy.HAS_SPACE, expected);
            for (BlockPos pos : new ArrayList<>(expected)) manager.remove(pos);
            expected.clear();
            expect(manager, chunk, PoiManager.Occupancy.ANY, expected);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 64; i++) {
                    BlockPos pos = new BlockPos(-32 + i % 16, 80 + i / 16, 49);
                    expected.add(pos);
                    futures.add(pool.submit(() -> { manager.add(pos, home); positions(manager, chunk, PoiManager.Occupancy.ANY); }));
                }
                for (var future : futures) future.get(5, TimeUnit.SECONDS);
            }
            expect(manager, chunk, PoiManager.Occupancy.ANY, expected);
            manager.flushAll();
        }
        try (var manager = open(dir)) {
            expect(manager, chunk, PoiManager.Occupancy.ANY, expected);
        }
        System.out.println("PoiChunkCacheCheck passed: empty/new sections, live additions/removals/tickets, lazy square/range caches, concurrent creation and reload");
    }
}
