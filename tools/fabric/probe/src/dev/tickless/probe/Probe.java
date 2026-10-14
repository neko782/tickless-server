package dev.tickless.probe;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.parallel.ParallelSafe;
import net.minecraft.world.level.parallel.WorldTxn;

public final class Probe implements DedicatedServerModInitializer {
    static final LongAdder ticks = new LongAdder(), damageMixins = new LongAdder(), deathMixins = new LongAdder();
    static final LongAdder damageEvents = new LongAdder(), deathEvents = new LongAdder(), blocks = new LongAdder();
    static final LongAdder chunkUnloads = new LongAdder();
    static int legacyTicks;
    static final Set<Long> workerThreads = ConcurrentHashMap.newKeySet();
    static final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
    @Override
    public void onInitializeServer() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(Probe::afterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(Probe::afterDeath);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_UNLOAD.register(Probe::chunkUnload);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("ticklessprobe").then(Commands.literal("verify").executes(context -> {
                String counts = "ticks=" + ticks.sum() + " damage_mixins=" + damageMixins.sum() + " damage_events=" + damageEvents.sum()
                    + " death_mixins=" + deathMixins.sum() + " death_events=" + deathEvents.sum() + " block_mixins=" + blocks.sum()
                    + " chunk_unload_events=" + chunkUnloads.sum()
                    + " worker_threads=" + workerThreads.size() + " concurrent_ticks=" + maximum.get() + " legacy_main_ticks=" + legacyTicks;
                boolean pass = ticks.sum() >= 64 && damageMixins.sum() >= 64 && damageEvents.sum() >= 64
                    && deathMixins.sum() >= 64 && deathEvents.sum() >= 64 && blocks.sum() >= 64
                    && chunkUnloads.sum() >= 2 && workerThreads.size() > 1 && maximum.get() > 1 && legacyTicks == 16;
                context.getSource().sendSuccess(() -> Component.literal("TICKLESS_PROBE " + (pass ? "PASS " : "FAIL ") + counts), false);
                if (!pass) throw new IllegalStateException("Parallel Fabric probe failed: " + counts);
                return 1;
            }))));
        System.out.println("TICKLESS_PROBE initialized: tick/damage/death/block mixins and Fabric damage/death events");
    }

    public static void chunkUnload(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        int x = chunk.getPos().x(), z = chunk.getPos().z();
        if ((x == 512 || x == 513) && z == 512) {
            if (!WorldTxn.isServerThread(Thread.currentThread())
                || !(WorldTxn.isExclusive() || (!WorldTxn.isEnabled() && level.getServer().worldWorkers().isQuiescent())))
                throw new IllegalStateException("Fabric chunk unload did not run in the drained server lane");
            chunkUnloads.increment();
        }
    }

    @ParallelSafe
    public static void entityTick(Entity entity) {
        if (entity.entityTags().contains("tickless_legacy_probe")) { legacyTick(entity); return; }
        if (!entity.entityTags().contains("tickless_probe") || !(entity instanceof LivingEntity living) || !(entity.level() instanceof ServerLevel level)) return;
        if (!Thread.currentThread().isVirtual() || !WorldTxn.isParticipant()) throw new IllegalStateException("Probe tick did not run in a virtual worker transaction");
        workerThreads.add(Thread.currentThread().threadId());
        int current = active.incrementAndGet();
        maximum.accumulateAndGet(current, Math::max);
        try {
            // Keep independent workers overlapped long enough to measure the actual callback concurrency.
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
            BlockPos pos = entity.blockPosition().below();
            level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            living.hurtServer(level, living.damageSources().generic(), 1.0f);
            living.hurtServer(level, living.damageSources().genericKill(), Float.MAX_VALUE);
            WorldTxn.afterCommit(ticks::increment);
        } finally { active.decrementAndGet(); }
    }
    public static void legacyTick(Entity entity) {
        if (!WorldTxn.isServerThread(Thread.currentThread())) throw new IllegalStateException("Ordinary mod code escaped the server lane");
        legacyTicks++;
        entity.removeTag("tickless_legacy_probe");
        entity.level().setBlock(entity.blockPosition().below(), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
    }
    @ParallelSafe public static void afterDamage(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source, float base, float actual, boolean blocked) {
        if (entity.entityTags().contains("tickless_probe")) WorldTxn.afterCommit(damageEvents::increment);
    }
    @ParallelSafe public static void afterDeath(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
        if (entity.entityTags().contains("tickless_probe")) WorldTxn.afterCommit(deathEvents::increment);
    }
    @ParallelSafe public static void hurt(LivingEntity entity) {
        if (entity.entityTags().contains("tickless_probe")) WorldTxn.afterCommit(damageMixins::increment);
    }
    @ParallelSafe public static void die(LivingEntity entity) {
        if (entity.entityTags().contains("tickless_probe")) WorldTxn.afterCommit(deathMixins::increment);
    }
    @ParallelSafe public static void changed(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, boolean result) {
        if (state.is(Blocks.DIAMOND_BLOCK) && result && Thread.currentThread().isVirtual()) WorldTxn.afterCommit(blocks::increment);
    }
}
