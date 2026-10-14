package net.minecraft.world.level.parallel;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;

/**
 * Exclusive lock for one block position stripe or one entity.
 *
 * <p>A transaction keeps every lock it takes until it ends. Uncontended acquisition is a
 * single CAS. When transactions wait on each other in a cycle, the deadlock resolver aborts
 * the youngest one that can be rolled back: it throws out of its wait, its writes are undone
 * and its locks released, and it runs again from the start.
 */
public final class WorldLock {
    private static final VarHandle OWNER;
    private static final int SPINS = 64;
    private static final long WOUND_NANOS = Long.getLong("tickless.woundMillis", 2L) * 1_000_000L;
    // A wait this long is checked for a cycle (the resolver confirms a cycle by reading it twice).
    // Waiting before the first check only delayed real deadlocks: the lock stress check took 32 s
    // with 1 ms against 5 s with none, and made as many rollbacks.
    private static final long CYCLE_CHECK_NANOS = Long.getLong("tickless.cycleCheckMicros", 0L) * 1_000L;
    private static volatile long lastLongWaitReport = System.nanoTime() - 2_000_000_000L;
    private static final VarHandle LONG_WAIT_REPORT;
    volatile @Nullable WorldTxn owner;
    private volatile int waiters;
    // Threads parked in acquireSlow. Guarded by this lock's monitor.
    private @Nullable ArrayList<Thread> parked;

    static {
        try {
            OWNER = MethodHandles.lookup().findVarHandle(WorldLock.class, "owner", WorldTxn.class);
            LONG_WAIT_REPORT = MethodHandles.lookup().findStaticVarHandle(WorldLock.class, "lastLongWaitReport", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public String toString() {
        return "WorldLock@" + Integer.toHexString(System.identityHashCode(this)) + "[waiters=" + this.waiters + "]";
    }

    public boolean isHeldBy(final WorldTxn txn) {
        return this.owner == txn;
    }

    public boolean isFree() {
        return this.owner == null;
    }

    void acquire(final WorldTxn txn) {
        WorldTxn current = this.owner;
        if (current == txn) {
            return;
        }

        if (current == null && OWNER.compareAndSet(this, null, txn)) {
            txn.held.add(this);
            return;
        }

        for (int i = 0; i < SPINS; i++) {
            Thread.onSpinWait();
            current = this.owner;
            if (current == null && OWNER.compareAndSet(this, null, txn)) {
                txn.held.add(this);
                return;
            }
        }

        this.acquireSlow(txn);
    }

    /** Takes the lock only if nobody else holds it; never waits. */
    boolean tryAcquire(final WorldTxn txn) {
        WorldTxn current = this.owner;
        if (current == txn) {
            return true;
        }

        if (current == null && OWNER.compareAndSet(this, null, txn)) {
            txn.held.add(this);
            return true;
        }

        return false;
    }

    private void acquireSlow(final WorldTxn txn) {
        if (txn.holdingAllLocks && !txn.revocable) {
            WorldTxn.reportInvisibleWait(txn, "waits for " + this + " after becoming irrevocable without the token");
        }

        synchronized (this) {
            this.waiters++;
            if (this.parked == null) {
                this.parked = new ArrayList<>(2);
            }
        }

        // The server thread can wait again inside a task it runs while pumping. Restore the outer
        // wait afterwards, or the deadlock resolver would no longer see the server thread waiting.
        WorldLock outerWait = txn.waitingFor;
        txn.waitingFor = this;
        try {
            long waitStart = System.nanoTime();
            boolean reported = false;
            long waitMillis = 1L;
            while (true) {
                synchronized (this) {
                    this.parked.remove(Thread.currentThread());
                    if (OWNER.compareAndSet(this, null, txn)) {
                        break;
                    }
                }

                txn.checkAbort(this);
                if (System.nanoTime() - waitStart >= CYCLE_CHECK_NANOS) {
                    DeadlockResolver.resolve(txn);
                    txn.checkAbort(this);
                }

                if (txn.mainThread) {
                    // The server thread must not wait long for one event: players' connections wait on
                    // it. After a short wait the holder is rolled back at its next lock operation (or
                    // at once, if it is parked) and runs again later.
                    WorldTxn holder = this.owner;
                    if (holder != null && holder != txn && System.nanoTime() - waitStart > WOUND_NANOS && holder.requestAbort()) {
                        WorldTxn.WOUNDS.increment();
                    }

                    // Workers can be waiting for the main thread to load a chunk.
                    WorldTxn.pumpMainThread();
                }

                synchronized (this) {
                    if (OWNER.compareAndSet(this, null, txn)) {
                        break;
                    }

                    this.parked.add(Thread.currentThread());
                }

                // Releases unpark waiters, so the timeout only paces deadlock checks. This must not be
                // Object.wait: on JDK 25, virtual threads looping on a contended monitor wait kept all
                // carriers busy, so an event unparked by the server thread after a chunk load never ran
                // again and the server hung behind its locks.
                LockSupport.parkNanos(this, waitMillis * 1_000_000L);
                waitMillis = Math.min(16L, waitMillis * 2L);

                if (!reported && System.nanoTime() - waitStart > 10_000_000_000L) {
                    reported = true;
                    // One line per second at most: with hundreds of waiters behind one long event,
                    // logging every wait flooded the console and slowed everything down.
                    long now = System.nanoTime();
                    long last = lastLongWaitReport;
                    if (now - last > 1_000_000_000L && LONG_WAIT_REPORT.compareAndSet(last, now)) {
                        WorldTxn.LOGGER.warn("{} has waited 10s for a world lock held by {}", txn, this.owner);
                    }

                    WorldTxn.reportWaitChain(txn);
                }
            }
        } finally {
            txn.waitingFor = outerWait;
            synchronized (this) {
                this.parked.remove(Thread.currentThread());
                this.waiters--;
            }
        }

        txn.held.add(this);
    }

    void release(final WorldTxn txn) {
        if (this.owner == txn && OWNER.compareAndSet(this, txn, null) && this.waiters != 0) {
            synchronized (this) {
                this.unparkWaiters();
            }
        }
    }

    private void unparkWaiters() {
        if (this.parked != null) {
            for (int i = 0; i < this.parked.size(); i++) {
                LockSupport.unpark(this.parked.get(i));
            }
        }
    }
}
