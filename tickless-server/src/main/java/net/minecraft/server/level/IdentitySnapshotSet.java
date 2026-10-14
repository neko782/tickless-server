package net.minecraft.server.level;

import java.util.AbstractSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Identity membership with immutable iteration snapshots for worker packet broadcasts.
 * Membership changes publish a new array; sending packets never holds the membership monitor. */
public final class IdentitySnapshotSet<T> extends AbstractSet<T> {
    private final IdentityHashMap<T, Boolean> members = new IdentityHashMap<>();
    private volatile Object[] snapshot = new Object[0];

    @Override
    public synchronized boolean add(T value) {
        if (this.members.put(value, Boolean.TRUE) != null) return false;
        this.snapshot = this.members.keySet().toArray();
        return true;
    }

    @Override
    public synchronized boolean remove(Object value) {
        if (this.members.remove(value) == null) return false;
        this.snapshot = this.members.keySet().toArray();
        return true;
    }

    @Override
    public synchronized boolean contains(Object value) {
        return this.members.containsKey(value);
    }

    @Override
    public int size() {
        return this.snapshot.length;
    }

    @Override
    public synchronized void clear() {
        this.members.clear();
        this.snapshot = new Object[0];
    }

    @Override
    public Iterator<T> iterator() {
        Object[] values = this.snapshot;
        return new Iterator<>() {
            private int index;
            public boolean hasNext() { return this.index < values.length; }
            @SuppressWarnings("unchecked")
            public T next() {
                if (!this.hasNext()) throw new NoSuchElementException();
                return (T)values[this.index++];
            }
        };
    }
}
