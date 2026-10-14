package net.minecraft.world.level.parallel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Block and entity locks used by world events.
 *
 * <p>Block locks are hashed stripes. Adjacent positions spread over unrelated stripes, which
 * matches the fastest option in {@code tools/BlockLockBench.java} for independent neighbours
 * while keeping individual lock objects for hot blocks. A stripe collision only makes two
 * events wait for each other; it never lets them run together.
 */
public final class WorldLocks {
    private static final int BLOCK_STRIPES = 1 << 16;
    private final WorldLock[] blockLocks = new WorldLock[BLOCK_STRIPES];

    public WorldLocks() {
        for (int i = 0; i < this.blockLocks.length; i++) {
            this.blockLocks[i] = new WorldLock();
        }
    }

    public WorldLock forBlock(final long packedPos) {
        long h = packedPos * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return this.blockLocks[(int)h & (BLOCK_STRIPES - 1)];
    }

    /** Locks a block the current event writes, or whose block entity it uses. */
    public void lockBlock(final BlockPos pos) {
        WorldTxn txn = WorldTxn.current();
        if (txn != null) {
            txn.lock(this.forBlock(pos.asLong()));
        }
    }

    /** Locks a block the current event reads, if it is near the event origin. */
    public void lockBlockRead(final BlockPos pos) {
        WorldTxn txn = WorldTxn.current();
        if (txn != null && txn.shouldLockRead(pos)) {
            txn.lock(this.forBlock(pos.asLong()));
        }
    }

    public static void lockEntity(final @Nullable Entity entity) {
        if (entity != null) {
            WorldTxn txn = WorldTxn.current();
            if (txn != null) {
                txn.lock(entity.worldLock);
            }
        }
    }

    /**
     * Locks an entity for an optional action, such as merging two item stacks, unless waiting for it
     * could deadlock (see {@link WorldTxn#lockIfSafe}). Returns false if the action must be skipped.
     */
    public static boolean lockEntityIfSafe(final Entity entity) {
        WorldTxn txn = WorldTxn.current();
        return txn == null || txn.lockIfSafe(entity.worldLock);
    }

    /** Locks an entity with its vehicle and passengers, which update together. */
    public static void lockEntityTree(final Entity entity) {
        WorldTxn txn = WorldTxn.current();
        if (txn != null) {
            Entity root = entity.getRootVehicle();
            txn.lock(root.worldLock);
            for (Entity passenger : root.getIndirectPassengers()) {
                txn.lock(passenger.worldLock);
            }
        }
    }
}
