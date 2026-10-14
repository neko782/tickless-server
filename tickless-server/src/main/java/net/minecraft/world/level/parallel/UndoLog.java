package net.minecraft.world.level.parallel;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * One transaction's undo log: old values of the fields it wrote, and undo actions for changes
 * that are not a single field (block states, list slots, entities added). Replayed newest first.
 *
 * <p>Entries live in parallel arrays, so logging a field write allocates nothing; a primitive old
 * value is kept as raw bits. Most events commit, so the arrays are pooled between events.
 */
final class UndoLog {
    private static final int POOL_SLOTS = 256;
    private static final int MAX_POOLED = 1 << 14;
    private static final AtomicReferenceArray<UndoLog> POOL = new AtomicReferenceArray<>(POOL_SLOTS);
    // Negative sites are undo actions: refs[i] is a Runnable.
    private static final int ACTION = -1;
    private Object[] targets = new Object[32];
    private Object[] refs = new Object[32];
    private long[] prims = new long[32];
    private int[] sites = new int[32];
    private int size;

    static UndoLog take() {
        int slot = (int)Thread.currentThread().threadId() & (POOL_SLOTS - 1);
        UndoLog log = POOL.getAndSet(slot, null);
        return log != null ? log : new UndoLog();
    }

    static void give(final UndoLog log) {
        log.clear();
        if (log.sites.length <= MAX_POOLED) {
            POOL.compareAndSet((int)Thread.currentThread().threadId() & (POOL_SLOTS - 1), null, log);
        }
    }

    void field(final Object target, final int site, final long prim, final Object ref) {
        int i = this.size;
        if (i == this.sites.length) {
            this.grow();
        }

        this.targets[i] = target;
        this.sites[i] = site;
        this.prims[i] = prim;
        this.refs[i] = ref;
        this.size = i + 1;
    }

    void action(final Runnable undo) {
        this.field(null, ACTION, 0L, undo);
    }

    private void grow() {
        int length = this.sites.length * 2;
        this.targets = Arrays.copyOf(this.targets, length);
        this.refs = Arrays.copyOf(this.refs, length);
        this.prims = Arrays.copyOf(this.prims, length);
        this.sites = Arrays.copyOf(this.sites, length);
    }

    /** Restores every logged value, newest first, and empties the log. */
    void undo() {
        for (int i = this.size - 1; i >= 0; i--) {
            try {
                if (this.sites[i] == ACTION) {
                    ((Runnable)this.refs[i]).run();
                } else {
                    UndoFields.restore(this.targets[i], this.sites[i], this.prims[i], this.refs[i]);
                }
            } catch (Throwable t) {
                WorldTxn.LOGGER.warn("Could not undo a write at site {}", this.sites[i], t);
            }
        }

        this.clear();
    }

    void clear() {
        Arrays.fill(this.targets, 0, this.size, null);
        Arrays.fill(this.refs, 0, this.size, null);
        this.size = 0;
    }
}
