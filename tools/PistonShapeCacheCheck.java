import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Compare the packaged piston method with the pre-cache geometry, including cold reader races. */
public class PistonShapeCacheCheck {
    static final BlockPos POS = new BlockPos(-16, 64, 15);
    static final BlockGetter LEVEL = EmptyBlockGetter.INSTANCE;
    static Field progress, movedState, direction, extending, source, cache;
    static ThreadLocal<Direction> noClip;
    static Field field(String name) throws Exception {
        Field field = PistonMovingBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    static VoxelShape reference(PistonMovingBlockEntity piston, float progress, Direction noClip) {
        return reference(piston, progress, noClip, LEVEL);
    }
    static VoxelShape reference(PistonMovingBlockEntity piston, float progress, Direction noClip, BlockGetter level) {
        BlockState moved = piston.getMovedState();
        VoxelShape base = !piston.isExtending() && piston.isSourcePiston() && moved.getBlock() instanceof PistonBaseBlock
            ? moved.setValue(PistonBaseBlock.EXTENDED, true).getCollisionShape(level, POS) : Shapes.empty();
        if (progress < 1.0 && noClip == piston.getMovementDirection()) return base;
        BlockState state = piston.isSourcePiston() ? Blocks.PISTON_HEAD.defaultBlockState()
            .setValue(PistonHeadBlock.FACING, piston.getDirection())
            .setValue(PistonHeadBlock.SHORT, piston.isExtending() != 1.0F - progress < 0.25F) : moved;
        float offset = piston.isExtending() ? progress - 1.0F : 1.0F - progress;
        Direction direction = piston.getDirection();
        return Shapes.or(base, state.getCollisionShape(level, POS).move(
            direction.getStepX() * offset, direction.getStepY() * offset, direction.getStepZ() * offset));
    }
    static void same(VoxelShape actual, VoxelShape expected) {
        if (!actual.toAabbs().equals(expected.toAabbs())) throw new AssertionError("Piston collision boxes differ");
        for (Direction.Axis axis : Direction.Axis.values()) {
            AABB box = new AABB(-0.2, 0.2, -0.2, 0.2, 0.8, 0.2);
            for (double delta : new double[] {-1.25, -0.01, 0, 0.01, 1.25}) {
                if (Double.doubleToLongBits(actual.collide(axis, box, delta)) != Double.doubleToLongBits(expected.collide(axis, box, delta))) {
                    throw new AssertionError("Piston movement clipping differs");
                }
            }
        }
    }
    static class DynamicWorld implements BlockGetter {
        BlockEntity entity;
        int calls;
        public BlockEntity getBlockEntity(BlockPos pos) { calls++; return entity; }
        public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
        public int getMinY() { return 0; }
        public int getHeight() { return 256; }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        progress = field("progress"); movedState = field("movedState"); direction = field("direction");
        extending = field("extending"); source = field("isSourcePiston"); cache = field("collisionShapeCache");
        noClip = (ThreadLocal<Direction>)field("NOCLIP").get(null);
        List<BlockState> states = new ArrayList<>();
        for (Block block : new Block[] {Blocks.AIR, Blocks.STONE, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK,
            Blocks.OAK_STAIRS, Blocks.OAK_FENCE, Blocks.GLASS_PANE}) states.add(block.defaultBlockState());
        for (Block block : new Block[] {Blocks.PISTON, Blocks.STICKY_PISTON}) {
            for (Direction facing : Direction.values()) states.add(block.defaultBlockState().setValue(PistonBaseBlock.FACING, facing));
        }
        var piston = new PistonMovingBlockEntity(POS, Blocks.MOVING_PISTON.defaultBlockState(), states.getFirst(), Direction.EAST, true, false);
        float[] stages = {-0.0F, 0.0F, 0.125F, 0.25F, 0.5F, Math.nextUp(0.5F), 0.75F, 0.875F, 1.0F, -0.5F, 1.25F};
        int cases = 0;
        try {
            for (BlockState state : states) for (Direction facing : Direction.values()) {
                movedState.set(piston, state); direction.set(piston, facing);
                for (boolean extend : new boolean[] {false, true}) for (boolean isSource : new boolean[] {false, true}) {
                    extending.setBoolean(piston, extend); source.setBoolean(piston, isSource);
                    for (float stage : stages) {
                        progress.setFloat(piston, stage);
                        for (int clip = 0; clip < 3; clip++) {
                            Direction clipDirection = clip == 0 ? null : clip == 1 ? piston.getMovementDirection() : piston.getMovementDirection().getOpposite();
                            noClip.set(clipDirection);
                            VoxelShape expected = reference(piston, stage, clipDirection);
                            VoxelShape actual = piston.getCollisionShape(LEVEL, POS);
                            same(actual, expected);
                            if (piston.getCollisionShape(LEVEL, POS) != actual) throw new AssertionError("Repeated shape missed the memo");
                            cases++;
                        }
                    }
                }
            }
        } finally { noClip.remove(); }
        // A world-dependent shape returns the same source object: it must still be queried
        // and rebuilt each time instead of hitting an old static-state memo.
        var dynamic = new DynamicWorld();
        movedState.set(piston, Blocks.STONE.defaultBlockState()); direction.set(piston, Direction.EAST);
        extending.setBoolean(piston, true); source.setBoolean(piston, false); progress.setFloat(piston, 0.5F);
        piston.getCollisionShape(LEVEL, POS);
        Object previousMemo = cache.get(piston);
        movedState.set(piston, Blocks.SHULKER_BOX.defaultBlockState());
        VoxelShape first = piston.getCollisionShape(dynamic, POS), second = piston.getCollisionShape(dynamic, POS);
        if (dynamic.calls != 2 || first == second || cache.get(piston) != previousMemo) throw new AssertionError("Dynamic shape reused the memo");
        same(first, reference(piston, 0.5F, null, dynamic));
        var shulker = new ShulkerBoxBlockEntity(POS, Blocks.SHULKER_BOX.defaultBlockState());
        var lidProgress = ShulkerBoxBlockEntity.class.getDeclaredField("progress");
        lidProgress.setAccessible(true); lidProgress.setFloat(shulker, 1.0F);
        dynamic.entity = shulker;
        VoxelShape opened = piston.getCollisionShape(dynamic, POS);
        same(opened, reference(piston, 0.5F, null, dynamic));
        if (opened.toAabbs().equals(first.toAabbs())) throw new AssertionError("Changed dynamic context was ignored");
        // Simulate reload/rollback by returning to previous inputs on the same object.
        movedState.set(piston, Blocks.STONE.defaultBlockState());
        same(piston.getCollisionShape(LEVEL, POS), reference(piston, 0.5F, null));
        cache.set(piston, null);
        List<AABB>[] expected = new List[3];
        for (int mode = 0; mode < 3; mode++) expected[mode] = reference(piston, 0.5F,
            mode == 0 ? null : mode == 1 ? piston.getMovementDirection() : piston.getMovementDirection().getOpposite()).toAabbs();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 16; worker++) {
                int id = worker;
                futures.add(pool.submit(() -> {
                    try {
                        for (int i = 0; i < 2000; i++) {
                            int mode = (i + id) % 3;
                            noClip.set(mode == 0 ? null : mode == 1 ? piston.getMovementDirection() : piston.getMovementDirection().getOpposite());
                            if (!piston.getCollisionShape(LEVEL, POS).toAabbs().equals(expected[mode])) throw new AssertionError("Concurrent shape or thread-local no-clip differs");
                        }
                    } finally { noClip.remove(); }
                }));
            }
            for (var future : futures) future.get();
        }
        System.out.println("PistonShapeCacheCheck passed: " + cases + " geometry/clip cases, memo hits, direction/state/stage changes, dynamic fallback and 32,000 concurrent no-clip queries");
        System.exit(0);
    }
}
