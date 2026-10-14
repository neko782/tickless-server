package net.minecraft.server.level;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;

/** Fixed-size striped locks for chunk generation steps that write neighboring chunks. */
final class ChunkNeighborhoodLocks {
    private static final int STRIPE_COUNT = 4096;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    ChunkNeighborhoodLocks() {
        for (int i = 0; i < this.stripes.length; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    <T> T withFeatureNeighborhood(final ChunkPos center, final Supplier<T> work) {
        int[] lockIds = new int[9];
        int count = 0;
        for (int x = center.x() - 1; x <= center.x() + 1; x++) {
            for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                lockIds[count++] = stripeFor(ChunkPos.pack(x, z));
            }
        }

        Arrays.sort(lockIds);
        int locked = 0;
        try {
            for (int i = 0; i < lockIds.length; i++) {
                if (i == 0 || lockIds[i] != lockIds[i - 1]) {
                    this.stripes[lockIds[i]].lock();
                    lockIds[locked++] = lockIds[i];
                }
            }

            return work.get();
        } finally {
            for (int i = locked - 1; i >= 0; i--) {
                this.stripes[lockIds[i]].unlock();
            }
        }
    }

    private static int stripeFor(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int)key & (STRIPE_COUNT - 1);
    }
}
