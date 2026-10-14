package net.minecraft.world.level.parallel;

import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

/**
 * Makes field writes on entities and block entities take their owner's world lock.
 *
 * <p>The build rewrites every {@code putfield} on a subclass of {@link Entity} or
 * {@link BlockEntity} (outside constructors, and except fields or methods marked
 * {@link Unowned}) into a call here followed by the original write. Vanilla code therefore
 * cannot change a world object without holding its lock, however it reached the object.
 *
 * <p>{@code -Dtickless.ownership} selects the mode:
 * <ul>
 * <li>{@code lock} (default): the write takes the owner's lock for the rest of the event;
 * <li>{@code check}: the write proceeds unlocked, and each write site that would have needed
 * the lock is logged once with a stack and counted, see {@link #logSummary};
 * <li>{@code off}: nothing.
 * </ul>
 *
 * <p>The owner of an entity is its own lock, and of a block entity the lock of its block. A
 * write by the thread that already owns the lock returns after two loads and a compare.
 * Writes outside world transactions (chunk loading and generation threads, objects not in a
 * level) and writes while workers are paused are not checked.
 */
public final class WorldOwnership {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SITES_RESOURCE = "tickless-ownership-sites.txt";
    private static final int MODE_OFF = 0;
    private static final int MODE_CHECK = 1;
    private static final int MODE_LOCK = 2;
    private static final int MODE = switch (System.getProperty("tickless.ownership", "lock")) {
        case "off" -> MODE_OFF;
        case "check" -> MODE_CHECK;
        case "lock" -> MODE_LOCK;
        default -> throw new IllegalArgumentException("tickless.ownership must be off, check or lock");
    };
    private static final int STACK_FRAMES = 14;
    private static final Sites SITES = MODE == MODE_CHECK ? Sites.load() : null;

    private WorldOwnership() {
    }

    public static boolean isChecking() {
        return MODE == MODE_CHECK;
    }

    /** Called before a field write on {@code entity}; {@code site} identifies the write. */
    public static void entityWrite(final Entity entity, final int site) {
        if (MODE == MODE_OFF) {
            return;
        }

        WorldLock lock = entity.worldLock;
        // Null while Entity's own field initializers run from a subclass constructor call chain.
        // Entities outside a level's storage (being built, loaded or already removed) are not
        // reachable by other events. Locking them on the server thread while it loads a chunk could
        // wait on a worker that is waiting for that chunk.
        if (lock != null && !ownedHere(lock) && entity.hasLevelCallback()) {
            slow(lock, site);
        }
    }

    /**
     * Called on entry to a method of an object an entity owns (its brain, equipment or inventory):
     * locks the owner, as a field write on the owner would. {@code owner} is null until adopted.
     */
    public static void ownedWrite(final @org.jspecify.annotations.Nullable Entity owner, final int site) {
        if (owner != null) {
            entityWrite(owner, site);
        }
    }

    /**
     * Called before an entity stores a brain, equipment or inventory in one of its fields: the
     * entity becomes its owner, unless another entity already is.
     */
    public static void adopt(final Entity entity, final @org.jspecify.annotations.Nullable Object helper) {
        if (helper instanceof net.minecraft.world.entity.ai.Brain<?> brain) {
            if (brain.worldOwner == null) {
                brain.worldOwner = entity;
            }
        } else if (helper instanceof net.minecraft.world.entity.EntityEquipment equipment) {
            if (equipment.worldOwner == null) {
                equipment.worldOwner = entity;
            }
        } else if (helper instanceof net.minecraft.world.SimpleContainer container) {
            if (container.worldOwner == null) {
                container.worldOwner = entity;
            }
        }
    }

    /** Called before a field write on {@code blockEntity}; {@code site} identifies the write. */
    public static void blockEntityWrite(final BlockEntity blockEntity, final int site) {
        if (MODE == MODE_OFF) {
            return;
        }

        Level level = blockEntity.getLevel();
        if (level != null && !level.isClientSide()) {
            WorldLock lock = level.worldLocks().forBlock(blockEntity.getBlockPos().asLong());
            if (!ownedHere(lock)) {
                slow(lock, site);
            }
        }
    }

    private static boolean ownedHere(final WorldLock lock) {
        WorldTxn owner = lock.owner;
        return owner != null && owner.thread == Thread.currentThread();
    }

    private static void slow(final WorldLock lock, final int site) {
        WorldTxn txn = WorldTxn.current();
        if (txn == null || txn.racyDepth > 0 || WorldTxn.isExclusive()) {
            return;
        }

        if (MODE == MODE_LOCK) {
            txn.lock(lock);
        } else {
            SITES.hit(site, txn);
        }
    }

    /**
     * Runs {@code action} with ownership checks off for this event. For deliberate races whose
     * writes happen in shared helpers (setters) that must stay checked elsewhere.
     */
    public static void racy(final Runnable action) {
        WorldTxn txn = WorldTxn.current();
        if (txn == null) {
            action.run();
            return;
        }

        txn.racyDepth++;
        try {
            action.run();
        } finally {
            txn.racyDepth--;
        }
    }

    public static <T> T racy(final Supplier<T> action) {
        WorldTxn txn = WorldTxn.current();
        if (txn == null) {
            return action.get();
        }

        txn.racyDepth++;
        try {
            return action.get();
        } finally {
            txn.racyDepth--;
        }
    }

    /** In check mode, logs every write site that ran without its owner's lock, most frequent first. */
    public static void logSummary() {
        if (SITES != null) {
            SITES.logSummary();
        }
    }

    private static final class Sites {
        private final String[] names;
        private final AtomicLongArray hits;
        private final java.util.Set<String> callers = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private Sites(final String[] names) {
            this.names = names;
            this.hits = new AtomicLongArray(names.length);
        }

        static Sites load() {
            List<String> names = new ArrayList<>();
            try (InputStream in = WorldOwnership.class.getClassLoader().getResourceAsStream(SITES_RESOURCE)) {
                if (in != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        names.add(line);
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Could not read {}", SITES_RESOURCE, e);
            }

            LOGGER.info("Ownership check mode: {} instrumented write sites", names.size());
            return new Sites(names.toArray(String[]::new));
        }

        String name(final int site) {
            if (site < 0 || site >= this.names.length) {
                return "site " + site;
            }

            // Field write sites also name the field, after a tab, for rollbacks.
            String name = this.names[site];
            int tab = name.indexOf('\t');
            return tab < 0 ? name : name.substring(0, tab);
        }

        void hit(final int site, final WorldTxn txn) {
            if (site < 0 || site >= this.names.length) {
                return;
            }

            long n = this.hits.getAndIncrement(site);
            // Sample callers: every early hit, then one in 1024. Log each new caller chain once.
            if (n >= 256 && (n & 1023) != 0) {
                return;
            }

            StackTraceElement[] stack = new Throwable().getStackTrace();
            // Skip hit, slow and the write hook itself.
            int from = 3;
            int to = Math.min(stack.length, from + STACK_FRAMES);
            StringBuilder key = new StringBuilder().append(site);
            for (int i = from; i < Math.min(to, from + 8); i++) {
                key.append('|').append(stack[i]);
            }

            if (this.callers.size() < 4096 && this.callers.add(key.toString())) {
                StringBuilder out = new StringBuilder("Unowned write: ").append(this.name(site)).append(" on ").append(txn.thread.getName().isEmpty() ? "worker" : txn.thread.getName());
                for (int i = from; i < to; i++) {
                    out.append("\n      at ").append(stack[i]);
                }

                LOGGER.warn(out.toString());
            }
        }

        void logSummary() {
            List<Integer> sites = new ArrayList<>();
            for (int i = 0; i < this.names.length; i++) {
                if (this.hits.get(i) != 0L) {
                    sites.add(i);
                }
            }

            sites.sort((a, b) -> Long.compare(this.hits.get(b), this.hits.get(a)));
            StringBuilder out = new StringBuilder("Ownership check summary: ").append(sites.size()).append(" unowned write sites");
            for (int site : sites) {
                out.append("\n  ").append(this.hits.get(site)).append('\t').append(this.name(site));
            }

            LOGGER.info(out.toString());
        }
    }
}
