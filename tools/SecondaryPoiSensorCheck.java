import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SecondaryPoiSensor;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceKey;
import sun.misc.Unsafe;

/** Exercise the actual sensor: useless scans, profession changes, order and immutable positions. */
public class SecondaryPoiSensorCheck {
    static class TestLevel extends ServerLevel {
        Map<BlockPos, BlockState> blocks;
        List<BlockPos> visited;
        // Fixtures bypass the constructors; only the overrides below are used.
        TestLevel() { super(null, null, null, null, null, null, false, 0, List.of(), false); }
        public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        public BlockState getBlockState(BlockPos pos) {
            visited.add(pos.immutable());
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
    }
    static class TestVillager extends Villager {
        Brain<Villager> testBrain;
        VillagerData data;
        BlockPos center;
        TestVillager() { super(EntityTypes.VILLAGER, null); }
        public Brain<Villager> getBrain() { return testBrain; }
        public VillagerData getVillagerData() { return data; }
        public BlockPos blockPosition() { return center; }
    }
    static class TestSensor extends SecondaryPoiSensor {
        void sense(TestLevel level, TestVillager body) { doTick(level, body); }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe)singleton.get(null);
        var level = (TestLevel)unsafe.allocateInstance(TestLevel.class);
        level.blocks = new HashMap<>(); level.visited = new ArrayList<>();
        var body = (TestVillager)unsafe.allocateInstance(TestVillager.class);
        body.testBrain = new Brain<>();
        var register = Brain.class.getDeclaredMethod("registerMemory", MemoryModuleType.class);
        register.setAccessible(true);
        register.invoke(body.testBrain, MemoryModuleType.SECONDARY_JOB_SITE);
        body.center = new BlockPos(-16, 64, 15);
        body.data = new VillagerData(BuiltInRegistries.VILLAGER_TYPE.getOrThrow(VillagerType.PLAINS),
            BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.FARMER), 1);
        var sensor = new TestSensor();
        List<GlobalPos> expected = new ArrayList<>();
        List<BlockPos> expectedVisits = new ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int y = -2; y <= 2; y++) for (int z = -4; z <= 4; z++) {
            BlockPos pos = body.center.offset(x, y, z);
            expectedVisits.add(pos);
            if ((x + y + z) % 7 == 0) {
                level.blocks.put(pos, Blocks.FARMLAND.defaultBlockState());
                expected.add(GlobalPos.of(Level.OVERWORLD, pos));
            }
        }
        // A block outside the search boundary must never appear.
        level.blocks.put(body.center.offset(5, 0, 0), Blocks.FARMLAND.defaultBlockState());
        sensor.sense(level, body);
        List<GlobalPos> remembered = body.testBrain.getMemory(MemoryModuleType.SECONDARY_JOB_SITE).orElseThrow();
        if (!remembered.equals(expected) || !level.visited.equals(expectedVisits)) throw new AssertionError("Farmer search order or bounds changed");
        for (GlobalPos pos : remembered) if (pos.pos() instanceof BlockPos.MutableBlockPos) throw new AssertionError("Mutable position escaped");
        level.blocks.clear(); level.visited.clear();
        sensor.sense(level, body);
        if (body.testBrain.hasMemoryValue(MemoryModuleType.SECONDARY_JOB_SITE)) throw new AssertionError("Empty farmer scan retained memory");
        if (!remembered.equals(expected)) throw new AssertionError("Published positions changed during the next scan");
        for (var holder : BuiltInRegistries.VILLAGER_PROFESSION.listElements().toList()) {
            if (!holder.value().secondaryPoi().isEmpty()) continue;
            body.data = body.data.withProfession(holder);
            body.testBrain.setMemory(MemoryModuleType.SECONDARY_JOB_SITE, expected);
            level.visited.clear();
            sensor.sense(level, body);
            if (!level.visited.isEmpty() || body.testBrain.hasMemoryValue(MemoryModuleType.SECONDARY_JOB_SITE)) {
                throw new AssertionError("Useless profession scan or stale job memory: " + holder);
            }
        }
        System.out.println("SecondaryPoiSensorCheck passed: farmer bounds/order, immutable positions, no matches, profession changes, zero reads for empty secondary POIs");
        System.exit(0);
    }
}
