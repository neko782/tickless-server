package net.minecraft.world.level.parallel;

import com.mojang.logging.LogUtils;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Catches threads using a collection that is not thread-safe at the same time.
 *
 * <p>World locks only cover entities, block entities and the blocks an event uses. Collections
 * on server-wide objects (the waypoint manager, the player list, the entity tracker, the
 * structure cache) are not covered, and each of them crashed a stress run with a
 * {@code ConcurrentModificationException} or a corrupted hash map before it was fixed by hand.
 * This finds the next one in a stress run, with both threads' stacks.
 *
 * <p>Built with {@code -PraceCheck}, the jar rewrite sends every call the game makes on a
 * collection (java.util, fastutil, Guava) or its iterators through this class; run that server
 * with {@code -Dtickless.raceCheck=true}. A call marks the collection as in use by its thread
 * until it returns, and an iterator marks it as being iterated until it is exhausted or the
 * method that created it returns. Views ({@code keySet}, {@code values}, ...) count as their map. A race is logged once per pair of call sites when:
 * <ul>
 * <li>a thread enters a collection while another thread is inside it, and either one changes it;
 * <li>a thread changes a collection another thread is iterating;
 * <li>the server thread changes a collection its own iteration is going over, from a chunk task
 * it ran while that iteration waited for a world lock.
 * </ul>
 *
 * <p>Only accesses that really overlap are reported, so collections handed between threads in
 * order (chunk generation stages, light engine tasks, pooled buffers) are not. To make overlaps
 * likely, one call in {@code tickless.raceCheckDelayOneIn} (default 32) spins for
 * {@code tickless.raceCheckDelayMicros} (default 20) while it is marked, the technique of
 * DataCollider (Erickson et al., 2010).
 */
public final class RaceCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = Boolean.getBoolean("tickless.raceCheck");
    private static final int STRIPES = 1024;
    private static final int STACK_FRAMES = 18;
    private static final long DELAY_NANOS = Long.getLong("tickless.raceCheckDelayMicros", 20L) * 1_000L;
    private static final int DELAY_ONE_IN = Integer.getInteger("tickless.raceCheckDelayOneIn", 32);
    private static final ClassValue<Boolean> UNSAFE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            return isUnsafe(type.getName());
        }
    };
    // Views and iterators are nested classes; a top-level collection class is never a view.
    private static final ClassValue<Boolean> NESTED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            return type.getName().indexOf('$') >= 0;
        }
    };
    // How deep the current thread is in chunk tasks run while waiting for a world lock.
    private static final ThreadLocal<int[]> PUMP_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final Stripe[] TABLE = new Stripe[STRIPES];
    private static final Map<String, Long> REPORTED = new ConcurrentHashMap<>();
    // Iterations the current thread started in methods that have not returned yet, oldest first.
    private static final ThreadLocal<ArrayList<Lease>> OPEN = ThreadLocal.withInitial(ArrayList::new);
    private static final LongAdder RACES = new LongAdder();
    private static volatile String @Nullable [] siteNames;

    static {
        for (int i = 0; i < STRIPES; i++) {
            TABLE[i] = new Stripe();
        }
    }

    private RaceCheck() {
    }

    /** A thread inside a call on a collection. */
    private static final class Flight {
        final Thread thread;
        final int site;
        boolean write;
        int depth;

        Flight(final Thread thread, final int site, final boolean write) {
            this.thread = thread;
            this.site = site;
            this.write = write;
        }
    }

    /** An open iteration over a collection. */
    private static final class Lease {
        final Thread thread;
        final int site;
        final int pumpDepth;
        volatile boolean done;

        Lease(final Thread thread, final int site, final int pumpDepth) {
            this.thread = thread;
            this.site = site;
            this.pumpDepth = pumpDepth;
        }

        boolean open() {
            return !this.done;
        }
    }

    private static final class Shadow extends WeakReference<Object> {
        final int hash;
        @Nullable Shadow next;
        // For a view or an iterator: the collection it goes over.
        @Nullable Object root;
        // For an iterator: its iteration of root.
        @Nullable Lease lease;
        @Nullable ArrayList<Flight> flights;
        @Nullable ArrayList<Lease> leases;
        // The last thread that used it, and whether another thread ever did.
        @Nullable Thread lastThread;
        boolean shared;

        Shadow(final Object referent, final int hash, final ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = hash;
        }
    }

    private static final class Stripe {
        final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Shadow[] buckets = new Shadow[16];
        int size;

        @Nullable Shadow find(final Object object, final int hash, final boolean create) {
            this.expunge();
            Shadow[] buckets = this.buckets;
            int index = (hash >>> 10) & (buckets.length - 1);
            for (Shadow shadow = buckets[index]; shadow != null; shadow = shadow.next) {
                if (shadow.hash == hash && shadow.get() == object) {
                    return shadow;
                }
            }

            if (!create) {
                return null;
            }

            Shadow shadow = new Shadow(object, hash, this.queue);
            shadow.next = buckets[index];
            buckets[index] = shadow;
            if (++this.size > buckets.length * 2) {
                this.grow();
            }

            return shadow;
        }

        private void grow() {
            Shadow[] old = this.buckets;
            Shadow[] grown = new Shadow[old.length * 2];
            for (Shadow head : old) {
                for (Shadow shadow = head; shadow != null; ) {
                    Shadow next = shadow.next;
                    int index = (shadow.hash >>> 10) & (grown.length - 1);
                    shadow.next = grown[index];
                    grown[index] = shadow;
                    shadow = next;
                }
            }

            this.buckets = grown;
        }

        private void expunge() {
            for (Object cleared = this.queue.poll(); cleared != null; cleared = this.queue.poll()) {
                Shadow dead = (Shadow)cleared;
                int index = (dead.hash >>> 10) & (this.buckets.length - 1);
                Shadow previous = null;
                for (Shadow shadow = this.buckets[index]; shadow != null; previous = shadow, shadow = shadow.next) {
                    if (shadow == dead) {
                        if (previous == null) {
                            this.buckets[index] = shadow.next;
                        } else {
                            previous.next = shadow.next;
                        }

                        this.size--;
                        break;
                    }
                }
            }
        }
    }

    /** What a collection is in use by, at the moment a conflicting access arrived. */
    private record Conflict(String what, int site, Thread thread) {
    }

    /** Whether instances of the class named {@code name} need a lock to be shared. */
    static boolean isUnsafe(final String name) {
        if (name.contains("Synchronized") || name.contains("Unmodifiable") || name.contains("Immutable")
            || name.contains("Concurrent") || name.contains("CopyOnWrite") || name.contains("Singleton")
            || name.contains("$Empty") || name.contains(".Empty") || name.equals("java.util.Hashtable")
            || name.equals("java.util.Vector") || name.equals("java.util.Stack") || name.equals("java.util.Properties")) {
            return false;
        }

        if (name.startsWith("java.util.")) {
            return !name.startsWith("java.util.concurrent.") && !name.startsWith("java.util.ImmutableCollections")
                && !name.startsWith("java.util.stream.") && !name.startsWith("java.util.function.");
        }

        return name.startsWith("it.unimi.dsi.fastutil.") || name.startsWith("com.google.common.collect.");
    }

    private static boolean tracked(final @Nullable Object object) {
        return ENABLED && object != null && UNSAFE.get(object.getClass());
    }

    private static Stripe stripe(final int hash) {
        return TABLE[hash & (STRIPES - 1)];
    }

    /** The collection {@code object} stands for: itself, or the map behind a view. */
    private static Object root(final Object object) {
        if (!NESTED.get(object.getClass())) {
            return object;
        }

        int hash = System.identityHashCode(object);
        Stripe stripe = stripe(hash);
        synchronized (stripe) {
            Shadow shadow = stripe.find(object, hash, false);
            Object root = shadow == null ? null : shadow.root;
            return root != null ? root : object;
        }
    }

    /** Called before a call on a collection; {@code write} if the method changes it. */
    public static void enter(final Object collection, final int site, final boolean write) {
        if (!tracked(collection)) {
            return;
        }

        Object root = root(collection);
        Thread thread = Thread.currentThread();
        int hash = System.identityHashCode(root);
        Stripe stripe = stripe(hash);
        Conflict conflict;
        boolean shared;
        synchronized (stripe) {
            Shadow shadow = stripe.find(root, hash, true);
            if (shadow.lastThread != thread) {
                shadow.shared |= shadow.lastThread != null;
                shadow.lastThread = thread;
            }

            shared = shadow.shared;
            conflict = shared ? conflictWith(shadow, thread, write) : null;
            ArrayList<Flight> flights = shadow.flights;
            if (flights == null) {
                flights = shadow.flights = new ArrayList<>(2);
            }

            Flight mine = null;
            for (Flight flight : flights) {
                if (flight.thread == thread) {
                    mine = flight;
                    break;
                }
            }

            if (mine == null) {
                mine = new Flight(thread, site, write);
                flights.add(mine);
            }

            mine.write |= write;
            mine.depth++;
        }

        if (conflict != null) {
            report(root, site, write, conflict);
        }

        if (shared) {
            maybeDelay();
        }
    }

    /** Called when a call on a collection returns or throws. */
    public static void exit(final Object collection) {
        if (!tracked(collection)) {
            return;
        }

        Object root = root(collection);
        Thread thread = Thread.currentThread();
        int hash = System.identityHashCode(root);
        Stripe stripe = stripe(hash);
        synchronized (stripe) {
            Shadow shadow = stripe.find(root, hash, false);
            ArrayList<Flight> flights = shadow == null ? null : shadow.flights;
            if (flights != null) {
                for (int i = 0; i < flights.size(); i++) {
                    Flight flight = flights.get(i);
                    if (flight.thread == thread) {
                        if (--flight.depth == 0) {
                            flights.remove(i);
                        }

                        break;
                    }
                }

                if (flights.isEmpty()) {
                    shadow.flights = null;
                }
            }
        }
    }

    /** What another thread is doing with the collection that {@code write} by this thread races with. */
    private static @Nullable Conflict conflictWith(final Shadow shadow, final Thread thread, final boolean write) {
        if (shadow.flights != null) {
            for (Flight flight : shadow.flights) {
                if (flight.thread != thread && (write || flight.write)) {
                    return new Conflict(flight.write ? "changing it" : "using it", flight.site, flight.thread);
                }
            }
        }

        ArrayList<Lease> leases = shadow.leases;
        if (write && leases != null) {
            int pumpDepth = PUMP_DEPTH.get()[0];
            leases.removeIf(lease -> !lease.open());
            for (Lease lease : leases) {
                if (lease.thread != thread) {
                    return new Conflict("iterating it", lease.site, lease.thread);
                }

                if (lease.pumpDepth < pumpDepth) {
                    return new Conflict("iterating it while waiting for a world lock", lease.site, lease.thread);
                }
            }

            if (leases.isEmpty()) {
                shadow.leases = null;
            }
        }

        return null;
    }

    /** Called after a call returned {@code view}, a view of {@code collection} (keySet, values, ...). */
    public static void view(final Object collection, final @Nullable Object view) {
        if (!tracked(collection) || view == null || view == collection || !UNSAFE.get(view.getClass())) {
            return;
        }

        Object root = root(collection);
        int hash = System.identityHashCode(view);
        Stripe stripe = stripe(hash);
        synchronized (stripe) {
            stripe.find(view, hash, true).root = root;
        }
    }

    /** Called after {@code iterator()} on {@code collection} returned {@code iterator}. */
    public static void iterating(final Object collection, final @Nullable Object iterator, final int site) {
        if (!tracked(collection) || iterator == null) {
            return;
        }

        Object root = root(collection);
        Thread thread = Thread.currentThread();
        Lease lease = new Lease(thread, site, PUMP_DEPTH.get()[0]);
        OPEN.get().add(lease);
        int rootHash = System.identityHashCode(root);
        Stripe rootStripe = stripe(rootHash);
        synchronized (rootStripe) {
            Shadow shadow = rootStripe.find(root, rootHash, true);
            if (shadow.leases == null) {
                shadow.leases = new ArrayList<>(2);
            }

            shadow.leases.add(lease);
        }

        int hash = System.identityHashCode(iterator);
        Stripe stripe = stripe(hash);
        synchronized (stripe) {
            Shadow shadow = stripe.find(iterator, hash, true);
            shadow.root = root;
            shadow.lease = lease;
        }
    }

    /**
     * Called before a step of an iterator: renews its iteration, and checks the collection is not
     * being changed by another thread. {@code write} for {@code Iterator.remove} and the like,
     * which change the collection.
     */
    public static void step(final Object iterator, final int site, final boolean write) {
        if (!ENABLED || iterator == null) {
            return;
        }

        int hash = System.identityHashCode(iterator);
        Stripe stripe = stripe(hash);
        Object root;
        Lease lease;
        synchronized (stripe) {
            Shadow shadow = stripe.find(iterator, hash, false);
            if (shadow == null || shadow.root == null) {
                return;
            }

            root = shadow.root;
            lease = shadow.lease;
        }

        int rootHash = System.identityHashCode(root);
        Stripe rootStripe = stripe(rootHash);
        Conflict conflict = null;
        boolean shared = false;
        synchronized (rootStripe) {
            Shadow shadow = rootStripe.find(root, rootHash, false);
            if (shadow != null && shadow.shared) {
                shared = true;
                Thread thread = Thread.currentThread();
                if (write) {
                    // Its own iteration is not a conflict.
                    ArrayList<Lease> leases = shadow.leases;
                    shadow.leases = null;
                    conflict = conflictWith(shadow, thread, true);
                    if (leases != null) {
                        shadow.leases = leases;
                        if (conflict == null) {
                            for (Lease other : leases) {
                                if (other != lease && other.thread != thread && other.open()) {
                                    conflict = new Conflict("iterating it", other.site, other.thread);
                                    break;
                                }
                            }
                        }
                    }
                } else if (shadow.flights != null) {
                    for (Flight flight : shadow.flights) {
                        if (flight.thread != thread && flight.write) {
                            conflict = new Conflict("changing it", flight.site, flight.thread);
                            break;
                        }
                    }
                }
            }
        }

        if (conflict != null) {
            report(root, site, write, conflict);
        }

        if (shared) {
            maybeDelay();
        }
    }

    /** Called when an iterator's {@code hasNext} returned false. */
    public static void done(final Object iterator) {
        if (!ENABLED || iterator == null) {
            return;
        }

        int hash = System.identityHashCode(iterator);
        Stripe stripe = stripe(hash);
        synchronized (stripe) {
            Shadow shadow = stripe.find(iterator, hash, false);
            if (shadow != null && shadow.lease != null) {
                shadow.lease.done = true;
            }
        }
    }

    /** Called on entry to a method that starts iterations; returns what {@link #release} needs. */
    public static int mark() {
        return ENABLED ? OPEN.get().size() : 0;
    }

    /** Called when a method that starts iterations returns or throws: its iterations have ended. */
    public static void release(final int mark) {
        if (ENABLED) {
            ArrayList<Lease> open = OPEN.get();
            for (int i = open.size() - 1; i >= mark; i--) {
                open.remove(i).done = true;
            }
        }
    }

    /** Called around chunk tasks the server thread runs while it waits for a world lock. */
    static void pumping(final boolean start) {
        PUMP_DEPTH.get()[0] += start ? 1 : -1;
    }

    private static void maybeDelay() {
        if (DELAY_NANOS > 0L && ThreadLocalRandom.current().nextInt(DELAY_ONE_IN) == 0) {
            long until = System.nanoTime() + DELAY_NANOS;
            while (System.nanoTime() - until < 0L) {
                Thread.onSpinWait();
            }
        }
    }

    private static void report(final Object collection, final int site, final boolean write, final Conflict conflict) {
        RACES.increment();
        String key = siteName(Math.min(conflict.site(), site)) + " | " + siteName(Math.max(conflict.site(), site));
        if (REPORTED.merge(key, 1L, Long::sum) != 1L || REPORTED.size() > 4096) {
            return;
        }

        StringBuilder out = new StringBuilder("Collection race: ").append(collection.getClass().getName())
            .append(write ? " changed at " : " used at ").append(siteName(site))
            .append(" on ").append(threadName(Thread.currentThread()))
            .append(" while ").append(threadName(conflict.thread())).append(" was ").append(conflict.what())
            .append(" at ").append(siteName(conflict.site()));
        StackTraceElement[] stack = new Throwable().getStackTrace();
        // Skip report, the hook and the bridge method.
        for (int i = 3; i < Math.min(stack.length, 3 + STACK_FRAMES); i++) {
            out.append("\n      at ").append(stack[i]);
        }

        if (conflict.thread() != Thread.currentThread()) {
            // Taken after the fact: the other thread may have moved on a little.
            StackTraceElement[] other = conflict.thread().getStackTrace();
            out.append("\n    ").append(threadName(conflict.thread())).append(" is now at:");
            for (int i = 0; i < Math.min(other.length, STACK_FRAMES); i++) {
                out.append("\n      at ").append(other[i]);
            }
        }

        LOGGER.warn(out.toString());
    }

    private static String threadName(final Thread thread) {
        String name = thread.getName();
        return name.isEmpty() ? "a world worker" : name;
    }

    private static String siteName(final int site) {
        String[] names = siteNames;
        if (names == null) {
            names = loadSites();
        }

        return site >= 0 && site < names.length ? names[site] : "site " + site;
    }

    private static synchronized String[] loadSites() {
        String[] loaded = siteNames;
        if (loaded != null) {
            return loaded;
        }

        List<String> names = new ArrayList<>();
        try (java.io.InputStream in = RaceCheck.class.getClassLoader().getResourceAsStream("tickless-race-sites.txt")) {
            if (in != null) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    names.add(line);
                }
            }
        } catch (java.io.IOException e) {
            LOGGER.warn("Could not read race check sites", e);
        }

        loaded = names.toArray(String[]::new);
        siteNames = loaded;
        return loaded;
    }

    /** How many racing accesses were seen, reported or not. */
    public static long racesSeen() {
        return RACES.sum();
    }

    /** Logs how often each pair of call sites raced. */
    public static void logSummary() {
        if (!ENABLED) {
            return;
        }

        List<Map.Entry<String, Long>> races = new ArrayList<>(REPORTED.entrySet());
        races.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder("Race check summary: ").append(races.size()).append(" racing site pairs, ")
            .append(RACES.sum()).append(" racing accesses");
        for (Map.Entry<String, Long> race : races) {
            out.append("\n  ").append(race.getValue()).append('\t').append(race.getKey());
        }

        LOGGER.info(out.toString());
    }
}
