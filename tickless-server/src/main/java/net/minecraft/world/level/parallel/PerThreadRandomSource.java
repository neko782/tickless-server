package net.minecraft.world.level.parallel;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A level random backed by {@link ThreadLocalRandom}.
 *
 * <p>World events draw from the level random on many threads at once, and each event may run
 * on a fresh virtual thread, so per-thread generator objects would be allocated constantly.
 * {@link #setSeed} has no effect: the level random was never reproducible across runs.
 */
public final class PerThreadRandomSource implements RandomSource {
    @Override
    public RandomSource fork() {
        return new LegacyRandomSource(ThreadLocalRandom.current().nextLong());
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return new LegacyRandomSource(ThreadLocalRandom.current().nextLong()).forkPositional();
    }

    @Override
    public void setSeed(final long seed) {
    }

    @Override
    public int nextInt() {
        return ThreadLocalRandom.current().nextInt();
    }

    @Override
    public int nextInt(final int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return ThreadLocalRandom.current().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return ThreadLocalRandom.current().nextFloat();
    }

    @Override
    public double nextDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return ThreadLocalRandom.current().nextGaussian();
    }
}
