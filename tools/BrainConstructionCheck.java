import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.ActivityData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;

/** Initial values and behavior-required cells must be published before default activity selection. */
public class BrainConstructionCheck {
    static class Fixture extends Brain<LivingEntity> {
        Fixture(MemoryMap initial, BehaviorControl<LivingEntity> behavior) {
            super(List.of(MemoryModuleType.HOME, MemoryModuleType.HOME), List.of(),
                List.of(ActivityData.create(Activity.IDLE, 0, ImmutableList.of(behavior),
                    Set.of(com.mojang.datafixers.util.Pair.of(MemoryModuleType.ATTACK_COOLING_DOWN, MemoryStatus.VALUE_PRESENT)))),
                initial, RandomSource.create(42));
        }
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        GlobalPos home = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 2, 3));
        MemoryMap initial = new MemoryMap.Builder()
            .add(MemoryModuleType.HOME, ExpirableValue.of(home, 50))
            .add(MemoryModuleType.ATTACK_COOLING_DOWN, ExpirableValue.of(true)).build();
        var behavior = new BrainLayoutCheck.Control() {
            public Set<MemoryModuleType<?>> getRequiredMemories() { return Set.of(MemoryModuleType.ATTACK_COOLING_DOWN); }
        };
        for (int i = 0; i < 100; i++) {
            var brain = new Fixture(initial, behavior);
            if (!brain.getMemory(MemoryModuleType.HOME).orElseThrow().equals(home)
                || brain.getTimeUntilExpiry(MemoryModuleType.HOME) != 50
                || !brain.getMemory(MemoryModuleType.ATTACK_COOLING_DOWN).orElseThrow()
                || !brain.isActive(Activity.IDLE)) throw new AssertionError("Construction lost memory or activity");
            if (!brain.pack().memories().get(MemoryModuleType.HOME).value().equals(home)) throw new AssertionError("Packed memory changed");
            brain.eraseMemory(MemoryModuleType.ATTACK_COOLING_DOWN);
            if (!brain.checkMemory(MemoryModuleType.ATTACK_COOLING_DOWN, MemoryStatus.VALUE_ABSENT)) throw new AssertionError("Cell publication failed");
        }
        System.out.println("BrainConstructionCheck passed: duplicates, required cells, initial values/expiry, activity and packing");
        System.exit(0);
    }
}
