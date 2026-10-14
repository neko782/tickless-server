import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;

/** Repeated collision lookups while pistons advance through the normal three stages. */
public class PistonShapeBench {
    static volatile double sink;
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var allocation = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        Field progress = PistonMovingBlockEntity.class.getDeclaredField("progress");
        progress.setAccessible(true);
        Direction[] directions = Direction.values();
        var pistons = new PistonMovingBlockEntity[192];
        for (int i = 0; i < pistons.length; i++) {
            Direction facing = directions[i % directions.length];
            boolean source = i % 2 == 0;
            var moved = source ? Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing)
                : Blocks.OAK_STAIRS.defaultBlockState();
            pistons[i] = new PistonMovingBlockEntity(BlockPos.ZERO, Blocks.MOVING_PISTON.defaultBlockState(), moved, facing, i % 4 < 2, source);
        }
        for (int round = 0; round < 12; round++) {
            double checksum = 0;
            long allocatedBefore = allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int cycle = 0; cycle < 12; cycle++) for (int stage = 0; stage < 3; stage++) {
                for (var piston : pistons) progress.setFloat(piston, stage * 0.5F);
                for (int query = 0; query < 32; query++) for (var piston : pistons) {
                    var shape = piston.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                    checksum += shape.min(Direction.Axis.X) + shape.max(Direction.Axis.Y);
                }
            }
            sink = checksum;
            long elapsed = System.nanoTime() - start;
            long allocated = allocation.getThreadAllocatedBytes(thread) - allocatedBefore;
            System.out.printf("round %d: %.2f ns/shape query, checksum %.4f, %.2f bytes/query%n", round,
                elapsed / (12.0 * 3 * 32 * pistons.length), checksum, allocated / (12.0 * 3 * 32 * pistons.length));
        }
        System.exit(0);
    }
}
