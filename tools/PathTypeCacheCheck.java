import it.unimi.dsi.fastutil.HashCommon;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;

/** Hits, invalidation and concurrent replacement must retain the position/type pairing. */
public class PathTypeCacheCheck {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            check();
        } finally {
            net.minecraft.util.Util.shutdownExecutors();
        }
    }

    private static void check() throws Exception {
        BlockPos first = BlockPos.ZERO;
        int slot = (int)HashCommon.mix(first.asLong()) & 65535;
        BlockPos second = null;
        for (int x = 1; x < 1_000_000; x++) {
            BlockPos candidate = new BlockPos(x, 64, -x);
            if (((int)HashCommon.mix(candidate.asLong()) & 65535) == slot) {
                second = candidate;
                break;
            }
        }
        if (second == null) throw new AssertionError("No collision fixture");
        BlockPos collision = second;
        AtomicInteger reads = new AtomicInteger();
        BlockGetter level = new BlockGetter() {
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            public BlockState getBlockState(BlockPos pos) {
                reads.incrementAndGet();
                return (pos.equals(first) ? Blocks.AIR : Blocks.HONEY_BLOCK).defaultBlockState();
            }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public int getHeight() { return 384; }
            public int getMinY() { return -64; }
        };
        PathTypeCache cache = new PathTypeCache();
        expect(cache, level, first, PathType.OPEN);
        expect(cache, level, first, PathType.OPEN);
        if (reads.get() != 1) throw new AssertionError("Missed repeated-position hit");
        // An invalidation at a colliding position must leave this position's entry intact.
        cache.invalidate(collision);
        expect(cache, level, first, PathType.OPEN);
        if (reads.get() != 1) throw new AssertionError("Invalidated a different position");
        cache.invalidate(first);
        expect(cache, level, first, PathType.OPEN);
        if (reads.get() != 2) throw new AssertionError("Failed to invalidate position");
        expect(cache, level, collision, PathType.STICKY_HONEY);
        expect(cache, level, first, PathType.OPEN);
        if (reads.get() != 4) throw new AssertionError("Accepted a hash-slot collision as a hit");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 16; worker++) {
                boolean useFirst = worker % 2 == 0;
                futures.add(pool.submit(() -> {
                    BlockPos pos = useFirst ? first : collision;
                    PathType type = useFirst ? PathType.OPEN : PathType.STICKY_HONEY;
                    for (int i = 0; i < 50_000; i++) {
                        if (i % 7 == 0) cache.invalidate(pos);
                        expect(cache, level, pos, type);
                    }
                }));
            }
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        System.out.println("PathTypeCacheCheck passed: hits, exact invalidation, collisions and 800,000 concurrent queries");
    }

    private static void expect(PathTypeCache cache, BlockGetter level, BlockPos pos, PathType type) {
        if (cache.getOrCompute(level, pos) != type) throw new AssertionError("Wrong path type for " + pos);
    }
}
