package net.minecraft.world.level.parallel;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The world locks held by one thread for one event.
 *
 * <p>World workers run each event (a scheduled tick, random tick, block event, block entity
 * or entity update) in its own transaction and release its locks when the event ends. The
 * server thread keeps one implicit transaction whose locks are released at safe points
 * between packets and tasks. Other threads never take world locks.
 *
 * <p>Reads through the level API lock blocks near the event origin; writes and block entity
 * access always lock. This keeps read-modify-write sequences such as crop growth or hopper
 * transfers atomic without making long-range scans (pathfinding, AI searches) serialize
 * whole areas.
 *
 * <p>A worker transaction can be rolled back. Its writes to world state are recorded in an undo
 * log (see {@link WorldUndo}); when it is chosen to break a wait cycle, or the server thread has
 * waited too long for one of its locks, it throws {@link Abort} out of its next wait or lock
 * operation, the log is replayed backwards, its locks are released, and the event runs again
 * from the start. It keeps its start stamp across retries, and the youngest transaction in a
 * cycle is the one rolled back, so every event eventually finishes.
 */
public final class WorldTxn {
    private static final boolean FABRIC = Boolean.getBoolean("tickless.fabric");
    private static final boolean FABRIC_AGENT = Boolean.getBoolean("tickless.fabric.agent");
    static final Logger LOGGER = LogUtils.getLogger();
    public static final int READ_LOCK_RADIUS = Integer.getInteger("tickless.readRadius", 2);
    public static final LongAdder ABORTS = new LongAdder();
    // Rollbacks asked for by the server thread after waiting for a lock (the rest break cycles).
    public static final LongAdder WOUNDS = new LongAdder();
    // Start stamps: the smaller, the older. Survives retries; the server thread is oldest.
    private static final java.util.concurrent.atomic.AtomicLong STAMPS = new java.util.concurrent.atomic.AtomicLong();
    // A worker's transaction is bound for the length of its event. Every block access asks for
    // it, and a scoped value lookup is cheaper than a thread local on short-lived virtual threads.
    private static final ScopedValue<Boolean> EVENT_SOURCE = ScopedValue.newInstance();
    private static final ScopedValue<WorldTxn> SCOPE = ScopedValue.newInstance();
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> MOD_REPLAYS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Set<String> ROUTED_MODS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean replayingMods;
    private static volatile @Nullable Throwable fabricFailure;
    // Only Fabric callbacks allocate thread-local state; ordinary transactions keep their original layout.
    private static final @Nullable ThreadLocal<String> MOD_ROUTE = FABRIC ? new ThreadLocal<>() : null;
    private static volatile @Nullable WorldTxn mainTxn;
    /**
     * Never acquired: always owned by the server thread's transaction. A worker blocked on the
     * server thread (a chunk load) waits for it, so the deadlock resolver sees that edge.
     */
    static final WorldLock SERVER_THREAD = new WorldLock();
    private static final Object[] NO_MONITORS = new Object[0];
    // Transactions that entered a monitor during their event, to find who holds a monitor. A
    // transaction joins on its first monitor and leaves when its event ends: joining and leaving
    // around every outermost monitor made this shared set's counters a hot spot.
    static final Set<WorldTxn> MONITOR_HOLDERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Transactions running outside the server thread. A striped counter works for any number of
    // (virtual) threads without a registry.
    private static final LongAdder ACTIVE = new LongAdder();
    private static final AtomicBoolean REPORTED_CHAIN = new AtomicBoolean();
    private static volatile boolean enabled;
    // Workers are paused and the server thread is running exclusive work.
    private static volatile boolean exclusive;
    // Exclusive work waiting for running events to finish; while any waits, new events wait at their gate.
    private static final java.util.concurrent.atomic.AtomicInteger EXCLUSIVE_PENDING = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> EXCLUSIVE_REQUESTS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // New events wait at their gate while this is set. It closes for at most GATE_MILLIS per attempt:
    // holding it until a storm of long explosions finished froze the world for 38 s, players included.
    private static volatile boolean gateClosed;
    private static final long GATE_NANOS = Long.getLong("tickless.exclusiveGateMillis", 250L) * 1_000_000L;
    private static final long RETRY_NANOS = Long.getLong("tickless.exclusiveRetryMillis", 5_000L) * 1_000_000L;
    private static long gateClosedAt;
    private static long nextAttemptAt;
    private static volatile @Nullable Thread mainThreadRef;
    private static volatile @Nullable Runnable mainThreadPump;
    private static boolean pumping;
    final Thread thread;
    final boolean mainThread;
    final ArrayList<WorldLock> held = new ArrayList<>();
    volatile @Nullable WorldLock waitingFor;
    // The monitor this transaction is blocked entering, if any (see beforeMonitor).
    volatile @Nullable Object waitingMonitor;
    // Monitors held, innermost last; read by other threads only to find a monitor's owner.
    volatile Object[] monitors = NO_MONITORS;
    int monitorCount;
    // In MONITOR_HOLDERS until the event ends.
    private boolean monitorHolder;
    final long stamp;
    // Whether this transaction's writes are logged and it can be rolled back: a worker event
    // (or a scoped part of one) that has not become irrevocable.
    volatile boolean revocable;
    // Set by the deadlock resolver or the waiting server thread; the transaction rolls back at
    // its next wait or lock operation.
    private volatile boolean abortRequested;
    // Replaying the undo log; writes are not logged and nothing aborts.
    boolean undoing;
    // What the transaction waited for when it was aborted: a WorldLock or a future. It waits for
    // it, holding nothing, before it runs again, so it does not run straight into the same cycle.
    private @Nullable Object abortedOn;
    private @Nullable UndoLog log;
    // Inside a WorldTxn.scoped part.
    private boolean inScoped;
    // Work deferred to the end of the event (see defer), and the keys already deferred.
    private @Nullable ArrayList<Runnable> deferred;
    private java.util.@Nullable HashSet<Object> deferredKeys;
    // Effects that cannot be taken back, run when the event commits (see afterCommit).
    private @Nullable ArrayList<Runnable> commitActions;
    private int depth;
    private boolean hasOrigin;
    private int originX;
    private int originY;
    private int originZ;
    private int readRadius = READ_LOCK_RADIUS;
    // Nesting depth of WorldOwnership.racy scopes; field writes inside them are not locked.
    int racyDepth;
    // The last chunk this worker event looked up. Events touch few chunks but many blocks, and the
    // off-thread chunk lookup is a hash map probe. Only workers cache: an event is short, while the
    // server thread's transaction lives for the whole run.
    private @Nullable Object cachedChunkLevel;
    private long cachedChunkKey;
    private @Nullable Object cachedChunk;

    private WorldTxn(final Thread thread, final boolean mainThread) {
        this.thread = thread;
        this.mainThread = mainThread;
        this.stamp = mainThread ? 0L : STAMPS.incrementAndGet();
    }

    /**
     * Thrown out of a wait or lock operation in a transaction that is being rolled back. An Error,
     * so that vanilla code catching exceptions lets it through; code that catches everything and
     * goes on is still rolled back when the event returns.
     */
    public static final class Abort extends Error {
        static final Abort INSTANCE = new Abort();

        private Abort() {
            super("world transaction rolled back", null, false, false);
        }
    }

    /** Starts lock tracking. World workers must be running before events use locks. */
    public static void enable(final Thread mainThread, final Runnable pump) {
        mainThreadRef = mainThread;
        fabricFailure = null;
        mainTxn = new WorldTxn(mainThread, true);
        SERVER_THREAD.owner = mainTxn;
        mainThreadPump = pump;
        enabled = true;
    }

    public static void disable() {
        mainSafePoint();
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isParticipant() {
        return current() != null;
    }

    /** The current worker transaction, or {@code null} on the server thread and outside events. */
    static @Nullable WorldTxn currentWorker() {
        return enabled && SCOPE.isBound() ? SCOPE.get() : null;
    }

    public static @Nullable WorldTxn current() {
        if (!enabled) {
            return null;
        }

        WorldTxn main = mainTxn;
        if (main != null && Thread.currentThread() == main.thread) {
            return main;
        }

        return SCOPE.isBound() ? SCOPE.get() : null;
    }

    /**
     * Runs one event in a transaction. On a world worker the event gets its own transaction, whose
     * locks are released when it returns; nested calls join the outer event. On the server thread
     * the event joins the implicit transaction.
     */
    public static void run(final int x, final int y, final int z, final boolean hasOrigin, final Runnable event) {
        WorldTxn main = mainTxn;
        if (main != null && Thread.currentThread() == main.thread) {
            main.depth++;
            try {
                event.run();
            } finally {
                main.depth--;
            }

            return;
        }

        if (SCOPE.isBound()) {
            event.run();
            return;
        }

        WorldTxn txn = new WorldTxn(Thread.currentThread(), false);
        txn.enterGate();
        txn.hasOrigin = hasOrigin;
        txn.originX = x;
        txn.originY = y;
        txn.originZ = z;
        try {
            ScopedValue.where(SCOPE, txn).run(() -> txn.attempt(event));
        } finally {
            if (txn.monitorHolder) {
                MONITOR_HOLDERS.remove(txn);
            }

            if (FABRIC) MOD_ROUTE.remove();
            txn.revocable = false;
            txn.releaseAll();
            txn.freeLog();
            ACTIVE.decrement();
        }
    }

    /**
     * Protects a worker's event producer from save/unload while it holds chunk data or extracts
     * scheduled ticks. Individual events still get independent, rollback-capable transactions.
     * Nested events may enter a closed gate: their already-admitted producer is being drained.
     */
    public static void runEventSource(final Runnable source) {
        if (current() != null || EVENT_SOURCE.isBound()) {
            source.run();
            return;
        }
        WorldTxn guard = new WorldTxn(Thread.currentThread(), false);
        guard.enterGate();
        try {
            ScopedValue.where(EVENT_SOURCE, true).run(source);
        } finally {
            ACTIVE.decrement();
        }
    }

    /** Runs {@code work} until it commits, rolling it back and running it again when aborted. */
    private void attempt(final Runnable work) {
        while (true) {
            this.revocable = true;
            try {
                work.run();
                // Code that caught the abort and went on still ends here.
                this.checkAbort(null);
                this.commit();
                return;
            } catch (Throwable t) {
                if (!this.rollbackIfAborted()) {
                    // A real failure: keep what the event did, as before, but run its effects that
                    // wait for the commit, such as removing entities it killed.
                    this.revocable = false;
                    this.runCommitActions();
                    throw t;
                }
            }

            if (FABRIC && MOD_ROUTE.get() != null) {
                this.replayOnMain(work);
                return;
            }
            this.awaitConflict();
        }
    }

    /**
     * Guard installed at ordinary mod callback entries, after Mixin has merged them.
     * No mod code executes in a rollbackable worker transaction unless it declares ParallelSafe.
     */
    public static void modCallback(final String source) {
        WorldTxn txn = currentWorker();
        if (txn == null || txn.undoing) return;
        if (FABRIC_AGENT && RuntimeOwnershipSites.isInfrastructure()) return;
        if (!txn.revocable) {
            IllegalStateException failure = new IllegalStateException("Tickless Fabric cannot route " + source
                + " after a partial commit. The mod must mark a transaction-safe callback @ParallelSafe"
                + " or invoke it before the event commits; refusing an unsafe off-thread callback.");
            fabricFailure = failure;
            throw failure;
        }
        MOD_ROUTE.set(source);
        txn.abortRequested = true;
        throw Abort.INSTANCE;
    }

    /** Turns a compatibility failure into a normal server crash/shutdown instead of dropping callbacks. */
    public static void throwIfFabricFailed() {
        if (!FABRIC) return;
        Throwable failure = fabricFailure;
        if (failure != null) throw new IllegalStateException("Tickless Fabric stopped because a mod is incompatible", failure);
    }

    public static void reportFabricFailure(final Throwable failure) {
        if (FABRIC && failure instanceof LinkageError) fabricFailure = failure;
    }

    private void replayOnMain(final Runnable work) {
        String source = MOD_ROUTE.get();
        MOD_ROUTE.remove();
        this.abortedOn = null;
        if (ROUTED_MODS.add(source)) LOGGER.info("Tickless Fabric: routing callback {} with its enclosing transaction to the server thread", source);
        java.util.concurrent.CompletableFuture<Void> complete = new java.util.concurrent.CompletableFuture<>();
        MOD_REPLAYS.add(() -> {
            try { WorldTxn.run(this.originX, this.originY, this.originZ, this.hasOrigin, work); complete.complete(null); }
            catch (Throwable failure) { complete.completeExceptionally(failure); }
        });
        // This event holds no locks and does not prevent a queued save from making progress.
        ACTIVE.decrement();
        try { awaitServerThread(complete); }
        finally { ACTIVE.increment(); }
    }

    private static void pollModReplays() {
        if (!FABRIC || replayingMods || exclusive || gateClosed || Thread.currentThread() != mainThreadRef) return;
        WorldTxn main = mainTxn;
        if (main == null || main.depth != 0) return;
        replayingMods = true;
        try {
            // Bound each batch so a mod receiving every entity step cannot starve networking.
            for (int i = 0; i < 64; i++) {
                Runnable work = MOD_REPLAYS.poll();
                if (work == null) break;
                work.run();
                main.releaseAll();
            }
        } finally { replayingMods = false; }
    }

    /** Runs deferred work and commit actions, then releases every lock. Nothing done so far can be undone after this. */
    private void commit() {
        this.runDeferred();
        this.revocable = false;
        this.runCommitActions();
        if (this.log != null) {
            this.log.clear();
        }

        this.releaseAll();
    }

    private boolean rollbackIfAborted() {
        if (!this.abortRequested || !this.revocable) {
            return false;
        }

        this.revocable = false;
        this.undoing = true;
        try {
            if (this.log != null) {
                this.log.undo();
            }
        } finally {
            this.undoing = false;
        }

        this.deferred = null;
        this.deferredKeys = null;
        this.commitActions = null;
        // Monitors are exited while the abort unwinds; a hand-written latch without hooks on its
        // exception path must not leave this transaction looking like a monitor holder.
        if (this.monitorCount != 0) {
            java.util.Arrays.fill(this.monitors, 0, this.monitorCount, null);
            this.monitorCount = 0;
        }

        this.releaseAll();
        this.abortRequested = false;
        ABORTS.increment();
        return true;
    }

    /**
     * Waits, holding nothing, for what the aborted attempt waited for: its lock (kept for the next
     * attempt) or its chunk load. Holding nothing, this wait cannot be part of a cycle.
     */
    private void awaitConflict() {
        Object on = this.abortedOn;
        this.abortedOn = null;
        try {
            if (on instanceof WorldLock lock) {
                this.lock(lock);
            } else if (on instanceof java.util.concurrent.CompletableFuture<?> future) {
                future.handle((result, error) -> null).join();
            } else {
                Thread.yield();
            }
        } catch (Abort abort) {
            // The server thread wanted the lock just taken; let it go and start over.
            this.abortRequested = false;
            this.releaseAll();
        }
    }

    /**
     * Rolls this transaction back at its next wait or lock operation, or at once if it is waiting.
     * Does nothing to the server thread's transaction or to one that cannot be rolled back.
     */
    boolean requestAbort() {
        if (!this.mainThread && this.revocable && !this.abortRequested) {
            this.abortRequested = true;
            LockSupport.unpark(this.thread);
            return true;
        }

        return false;
    }

    /** Throws {@link Abort} if this transaction is being rolled back; {@code on} is what it waits for. */
    void checkAbort(final @Nullable Object on) {
        if (this.abortRequested && this.revocable && !this.undoing) {
            if (on != null) {
                this.abortedOn = on;
            }

            throw Abort.INSTANCE;
        }
    }

    /** Whether the deadlock resolver may roll this transaction back now. */
    boolean canAbort() {
        return !this.mainThread && this.revocable && !this.undoing && this.waitingFor != null;
    }

    /**
     * Stops logging for the rest of this event (or scoped part): it will not be rolled back. For
     * changes that cannot be undone, such as moving an entity to another dimension.
     */
    public static void makeIrrevocable() {
        WorldTxn txn = currentWorker();
        if (txn != null && txn.revocable) {
            txn.revocable = false;
            if (txn.log != null) {
                txn.log.clear();
            }
        }
    }

    /** Whether writes in the current event are being logged for a rollback. */
    boolean isLogging() {
        return this.revocable && !this.undoing && this.racyDepth == 0;
    }

    UndoLog log() {
        UndoLog log = this.log;
        if (log == null) {
            log = UndoLog.take();
            this.log = log;
        }

        return log;
    }

    private void freeLog() {
        UndoLog log = this.log;
        if (log != null) {
            this.log = null;
            UndoLog.give(log);
        }
    }

    /**
     * Runs {@code action} when the current event commits, or now if it cannot be rolled back. For
     * effects that another event could see and act on before a rollback, such as queueing a block
     * event or taking an entity out of the level.
     */
    public static void afterCommit(final Runnable action) {
        WorldTxn txn = currentWorker();
        if (txn == null || !txn.revocable || txn.undoing) {
            action.run();
            return;
        }

        if (txn.commitActions == null) {
            txn.commitActions = new ArrayList<>(2);
        }

        txn.commitActions.add(action);
    }

    private void runCommitActions() {
        // Commit actions can add more; run until none is left.
        while (this.commitActions != null) {
            ArrayList<Runnable> actions = this.commitActions;
            this.commitActions = null;
            for (Runnable action : actions) {
                action.run();
            }
        }
    }

    /**
     * Blocks a worker event until {@code future} completes, when completing it needs the server
     * thread. While blocked, the event counts as waiting for the server thread: if the server
     * thread then waits for one of this event's locks, the deadlock resolver rolls this event back
     * instead of both waiting forever; it waits for the future holding nothing and runs again. A
     * fireball explosion holding hundreds of entity locks waited here for a chunk load while the
     * server thread waited for one of them.
     */
    public static <T> T awaitServerThread(final java.util.concurrent.CompletableFuture<T> future) {
        WorldTxn txn = SCOPE.isBound() ? SCOPE.get() : null;
        if (txn == null || txn.waitingFor != null || future.isDone()) {
            return future.join();
        }

        if (txn.monitorCount > 0) {
            // A monitor is not in the rollback's reach: if the server thread needs it, nothing can break the wait.
            reportInvisibleWait(txn, "waits for the server thread while holding a monitor");
        }

        txn.waitingFor = SERVER_THREAD;
        try {
            Thread thread = Thread.currentThread();
            future.whenComplete((result, error) -> LockSupport.unpark(thread));
            while (!future.isDone()) {
                txn.checkAbort(future);
                LockSupport.parkNanos(future, 16_000_000L);
            }
        } finally {
            txn.waitingFor = null;
        }

        return future.join();
    }

    /**
     * Runs a self-contained part of the current event as its own transaction. Everything the event
     * did before is committed and its locks released; the part then runs, is rolled back and run
     * again on its own if it is aborted, and commits when it returns. A big explosion otherwise
     * kept every entity it hit and every block it broke locked for the seconds it spent on the
     * rest, and a rollback would have thrown all of that away.
     *
     * <p>The event cannot run again from its start once a part has committed, so what it does
     * between and after parts is irrevocable: it is not logged and never rolled back. Keep that
     * short. Nested parts join the enclosing part. Do not use this around code whose writes must
     * stay atomic with earlier or later ones.
     */
    public static void scoped(final Runnable part) {
        WorldTxn txn = currentWorker();
        if (txn == null || txn.inScoped || txn.undoing) {
            part.run();
            return;
        }

        txn.commit();
        txn.inScoped = true;
        try {
            txn.attempt(part);
        } finally {
            txn.inScoped = false;
            txn.revocable = false;
        }
    }

    // Monitor hooks. The build inserts these around every monitorenter and monitorexit, and
    // around synchronized methods, so a transaction blocked on a Java monitor is an edge in the
    // wait graph: world code takes world locks inside latches since field writes lock their owner.

    public static void beforeMonitor(final Object monitor) {
        WorldTxn txn = current();
        if (txn != null) {
            txn.waitingMonitor = monitor;
        }
    }

    public static void enteredMonitor(final Object monitor) {
        WorldTxn txn = current();
        if (txn != null) {
            txn.waitingMonitor = null;
            Object[] stack = txn.monitors;
            if (txn.monitorCount == stack.length) {
                stack = java.util.Arrays.copyOf(stack, Math.max(4, stack.length * 2));
                txn.monitors = stack;
            }

            stack[txn.monitorCount++] = monitor;
            if (!txn.monitorHolder) {
                txn.monitorHolder = true;
                MONITOR_HOLDERS.add(txn);
            }
        }
    }

    /** Called right after monitorexit. */
    public static void exitedMonitor(final Object monitor) {
        WorldTxn txn = current();
        if (txn != null && txn.monitorCount > 0) {
            Object[] stack = txn.monitors;
            for (int i = txn.monitorCount - 1; i >= 0; i--) {
                if (stack[i] == monitor) {
                    System.arraycopy(stack, i + 1, stack, i, txn.monitorCount - i - 1);
                    stack[--txn.monitorCount] = null;
                    break;
                }
            }
        }
    }

    boolean holdsMonitor(final Object monitor) {
        Object[] stack = this.monitors;
        for (Object held : stack) {
            if (held == monitor) {
                return true;
            }
        }

        return false;
    }

    /** The transaction holding {@code monitor}, if a transaction does. */
    static @Nullable WorldTxn monitorOwner(final Object monitor) {
        for (WorldTxn txn : MONITOR_HOLDERS) {
            if (txn.holdsMonitor(monitor)) {
                return txn;
            }
        }

        return null;
    }

    /** The transaction {@code txn} waits for, whatever it waits on, or {@code null}. */
    static @Nullable WorldTxn waitsFor(final WorldTxn txn) {
        WorldLock lock = txn.waitingFor;
        if (lock != null) {
            return lock.owner;
        }

        Object monitor = txn.waitingMonitor;
        return monitor != null ? monitorOwner(monitor) : null;
    }

    private static final AtomicBoolean REPORTED_INVISIBLE = new AtomicBoolean();

    static void reportInvisibleWait(final WorldTxn txn, final String what) {
        if (REPORTED_INVISIBLE.compareAndSet(false, true)) {
            StringBuilder out = new StringBuilder("World wait outside the lock graph: ").append(txn).append(' ').append(what);
            for (StackTraceElement frame : txn.thread.getStackTrace()) {
                out.append("\n      at ").append(frame);
            }

            LOGGER.warn(out.toString());
        }
    }

    /**
     * Runs {@code work} at the end of the current worker event, once per {@code key}; on the
     * server thread or outside events it runs now. For neighbour notifications that only read
     * (comparator updates after a container changes): run in the middle of a transfer, their
     * block reads could wait for other events in the middle of a transfer.
     */
    public static void defer(final Object key, final Runnable work) {
        WorldTxn txn = current();
        if (txn == null || txn.mainThread) {
            work.run();
            return;
        }

        if (txn.deferredKeys == null) {
            txn.deferredKeys = new java.util.HashSet<>();
            txn.deferred = new ArrayList<>();
        }

        if (txn.deferredKeys.add(key)) {
            txn.deferred.add(work);
        }
    }

    private void runDeferred() {
        // Deferred work can defer more; run until none is left.
        while (this.deferred != null && !this.deferred.isEmpty()) {
            ArrayList<Runnable> work = this.deferred;
            this.deferred = new ArrayList<>();
            this.deferredKeys.clear();
            for (Runnable runnable : work) {
                runnable.run();
            }
        }
    }

    /** The chunk this worker event last looked up at {@code key} in {@code level}, or {@code null}. */
    public @Nullable Object cachedChunk(final Object level, final long key) {
        return this.cachedChunkLevel == level && this.cachedChunkKey == key ? this.cachedChunk : null;
    }

    public void cacheChunk(final Object level, final long key, final Object chunk) {
        if (!this.mainThread) {
            this.cachedChunkLevel = level;
            this.cachedChunkKey = key;
            this.cachedChunk = chunk;
        }
    }

    /**
     * Stops read locks for the rest of this event. Entity updates use this: they read many blocks
     * around them and rarely write them, and every write still locks.
     */
    public void disableReadLocks() {
        this.readRadius = -1;
    }

    /** Releases the server thread's implicit locks. Call only where no world state is half-updated. */
    public static void mainSafePoint() {
        WorldTxn txn = mainTxn;
        if (txn != null && Thread.currentThread() == txn.thread && txn.depth == 0) {
            txn.releaseAll();
            if (FABRIC) pollModReplays();
        }
    }

    private void releaseAll() {
        ArrayList<WorldLock> locks = this.held;
        for (int i = locks.size() - 1; i >= 0; i--) {
            locks.get(i).release(this);
        }

        locks.clear();
    }

    public boolean shouldLockRead(final BlockPos pos) {
        if (this.readRadius < 0) {
            return false;
        }

        return !this.hasOrigin
            || Math.abs(pos.getX() - this.originX) <= this.readRadius
                && Math.abs(pos.getY() - this.originY) <= this.readRadius
                && Math.abs(pos.getZ() - this.originZ) <= this.readRadius;
    }

    public void lock(final WorldLock lock) {
        if (this.abortRequested) {
            this.checkAbort(null);
        }

        lock.acquire(this);
    }

    public int heldCount() {
        return this.held.size();
    }

    private void enterGate() {
        ACTIVE.increment();
        if (EVENT_SOURCE.isBound()) {
            return;
        }
        while (gateClosed || exclusive) {
            ACTIVE.decrement();
            // Parks rather than Object.wait, which can keep virtual thread carriers busy (see WorldLock).
            while (gateClosed || exclusive) {
                LockSupport.parkNanos(1_000_000L);
            }

            ACTIVE.increment();
        }
    }

    /**
     * Runs {@code action} on the server thread while no world event runs, without making any thread
     * wait for that. New events wait at their gate; the server thread keeps serving connections and
     * runs the action from {@link #pollExclusive} once the running events have finished. If they do
     * not finish within {@code tickless.exclusiveGateMillis}, the gate opens again and the action
     * waits for a later attempt, so a long storm postpones a save instead of freezing the world. Used for
     * global swaps: saving (which serializes entities and block entities) and reloading data.
     *
     * <p>This used to block the server thread until every running event ended; a storm of
     * power-127 fireballs runs events for tens of seconds, and every connection stalled that long
     * on each autosave.
     */
    public static void requestExclusive(final Runnable action) {
        if (!enabled && Thread.currentThread() == mainThreadRef) {
            action.run();
            return;
        }

        // Counted first, so the count never drops below the queued work.
        EXCLUSIVE_PENDING.incrementAndGet();
        EXCLUSIVE_REQUESTS.add(action);
    }

    /** Whether save/unload work still needs to run before world shutdown. */
    public static boolean hasPendingExclusive() {
        return EXCLUSIVE_PENDING.get() != 0;
    }

    /**
     * Runs requested exclusive work if no world event is running. Server thread only, at a point
     * where its own transaction holds nothing half-updated. Returns whether it ran anything.
     */
    public static boolean pollExclusive() {
        if (EXCLUSIVE_PENDING.get() == 0 || exclusive || Thread.currentThread() != mainThreadRef) {
            return false;
        }

        WorldTxn main = mainTxn;
        if (main != null && main.depth != 0) {
            return false;
        }

        long now = System.nanoTime();
        if (!gateClosed) {
            if (now - nextAttemptAt < 0L) {
                return false;
            }

            gateClosed = true;
            gateClosedAt = now;
        }

        // Chunk tasks can lock blocks, and a worker we are waiting for may need them.
        mainSafePoint();
        if (ACTIVE.sum() != 0L) {
            if (now - gateClosedAt > GATE_NANOS) {
                // Long events are running; let the world go on and try again later.
                gateClosed = false;
                nextAttemptAt = now + RETRY_NANOS;
            }

            return false;
        }

        exclusive = true;
        try {
            Runnable action;
            while ((action = EXCLUSIVE_REQUESTS.poll()) != null) {
                try {
                    action.run();
                } catch (Throwable t) {
                    LOGGER.error("Exception in exclusive world work", t);
                } finally {
                    EXCLUSIVE_PENDING.decrementAndGet();
                }
            }
        } finally {
            mainSafePoint();
            gateClosed = false;
            exclusive = false;
        }

        return true;
    }

    public static boolean isServerThread(final @Nullable Thread thread) {
        return thread != null && thread == mainThreadRef;
    }

    /** A closed admission gate needs servicing before the next network deadline. */
    public static boolean isDrainingExclusive() {
        return gateClosed && !exclusive;
    }

    public static boolean isExclusive() {
        return exclusive;
    }

    static void pumpMainThread() {
        Runnable pump = mainThreadPump;
        if (pump != null && !pumping && Thread.currentThread() == mainThreadRef) {
            pumping = true;
            try {
                pump.run();
                if (FABRIC) pollModReplays();
            } finally {
                pumping = false;
            }
        }
    }

    /**
     * Logs the chain of transactions a long wait depends on, with each owner's stack. Only the
     * first long wait is reported: later ones are usually the same stall seen from another waiter.
     */
    static void reportWaitChain(final WorldTxn start) {
        if (!REPORTED_CHAIN.compareAndSet(false, true)) {
            return;
        }

        StringBuilder out = new StringBuilder("World lock wait chain:");
        Set<WorldTxn> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        WorldTxn current = start;
        while (current != null) {
            if (!seen.add(current)) {
                out.append("\n  ...back to ").append(current).append(" (cycle)");
                break;
            }

            if (seen.size() > 16) {
                out.append("\n  ...");
                break;
            }

            WorldLock waitingFor = current.waitingFor;
            Object waitingMonitor = current.waitingMonitor;
            if (waitingFor == SERVER_THREAD) {
                out.append("\n  ").append(current).append(" waits for the server thread");
            } else if (waitingFor != null) {
                out.append("\n  ").append(current).append(" waits for ").append(waitingFor).append(" owned by ").append(waitingFor.owner);
            } else if (waitingMonitor != null) {
                out.append("\n  ").append(current).append(" waits for monitor ").append(waitingMonitor.getClass().getName()).append(" owned by ").append(monitorOwner(waitingMonitor));
            } else {
                Thread.State state = current.thread.getState();
                // The server thread parks in its own loop between passes; workers waiting "for the
                // server thread" may really be waiting for a chunk it only started loading.
                boolean blocked = !current.mainThread
                    && (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED);
                out.append("\n  ").append(current).append(blocked
                    ? " is blocked (" + state + ") OUTSIDE THE WAIT GRAPH: no rollback can break this; add a wait point for it"
                    : " is running (" + state + "), not waiting");
            }

            for (StackTraceElement frame : current.thread.getStackTrace()) {
                out.append("\n      at ").append(frame);
            }

            current = waitsFor(current);
        }

        LOGGER.warn(out.toString());
    }

    @Override
    public String toString() {
        return "WorldTxn@" + Integer.toHexString(System.identityHashCode(this)) + "[" + this.thread + ", stamp=" + this.stamp + ", held=" + this.held.size() + ", revocable=" + this.revocable + "]";
    }
}
