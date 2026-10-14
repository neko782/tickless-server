package net.minecraft.world.level.parallel;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/**
 * A long-keyed map for data that world workers read far more often than anyone writes it.
 *
 * <p>{@code ConcurrentHashMap<Long, V>} is a poor fit for packed chunk positions: every lookup
 * boxes its key, and {@code Long.hashCode} folds x and z together, so a square of chunks piles
 * into a few bins that turn into trees, whose lookups reflect on the key class. This map keeps
 * fastutil's mixed hashing and primitive keys. Reads are optimistic and lock-free unless a write
 * overlaps; writes take an exclusive lock.
 */
public final class ConcurrentLong2ObjectMap<V> {
    private final StampedLock lock = new StampedLock();
    private final Long2ObjectOpenHashMap<V> map = new Long2ObjectOpenHashMap<>();

    public @Nullable V get(final long key) {
        long stamp = this.lock.tryOptimisticRead();
        if (stamp != 0L) {
            try {
                V value = this.map.get(key);
                if (this.lock.validate(stamp)) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // A concurrent rehash; retry under the read lock.
            }
        }

        stamp = this.lock.readLock();
        try {
            return this.map.get(key);
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public V getOrDefault(final long key, final V defaultValue) {
        V value = this.get(key);
        return value != null ? value : defaultValue;
    }

    public boolean containsKey(final long key) {
        return this.get(key) != null;
    }

    public @Nullable V put(final long key, final V value) {
        long stamp = this.lock.writeLock();
        try {
            return this.map.put(key, value);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public @Nullable V remove(final long key) {
        long stamp = this.lock.writeLock();
        try {
            return this.map.remove(key);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    /** Returns the existing value, or stores and returns a new one. {@code factory} runs under the write lock. */
    public V computeIfAbsent(final long key, final LongFunction<? extends V> factory) {
        V value = this.get(key);
        if (value != null) {
            return value;
        }

        long stamp = this.lock.writeLock();
        try {
            value = this.map.get(key);
            if (value == null) {
                value = factory.apply(key);
                this.map.put(key, value);
            }

            return value;
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public int size() {
        long stamp = this.lock.readLock();
        try {
            return this.map.size();
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public boolean isEmpty() {
        return this.size() == 0;
    }

    public void clear() {
        long stamp = this.lock.writeLock();
        try {
            this.map.clear();
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public LongList keysSnapshot() {
        long stamp = this.lock.readLock();
        try {
            return new LongArrayList(this.map.keySet());
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public List<V> valuesSnapshot() {
        long stamp = this.lock.readLock();
        try {
            return new ArrayList<>(this.map.values());
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public List<Long2ObjectMap.Entry<V>> entriesSnapshot() {
        long stamp = this.lock.readLock();
        try {
            List<Long2ObjectMap.Entry<V>> entries = new ArrayList<>(this.map.size());
            for (Long2ObjectMap.Entry<V> entry : this.map.long2ObjectEntrySet()) {
                entries.add(new it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap.BasicEntry<>(entry.getLongKey(), entry.getValue()));
            }

            return entries;
        } finally {
            this.lock.unlockRead(stamp);
        }
    }
}
