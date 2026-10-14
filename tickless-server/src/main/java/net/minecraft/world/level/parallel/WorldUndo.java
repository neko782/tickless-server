package net.minecraft.world.level.parallel;

import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Records world writes so a transaction can be rolled back (see {@link WorldTxn}).
 *
 * <p>The build rewrites every field write the ownership hooks cover (entities and block entities),
 * and every field write on item stacks, container menus, inventories and food data, to log the
 * old value here after the owner's lock is taken: {@code entityX} for entities, which find their
 * transaction through the lock they hold, and {@code anyX} for the rest. Changes that are not a
 * single field log an undo action instead. Only worker transactions that can be rolled back log;
 * the server thread, irrevocable events and deliberate races ({@link WorldOwnership#racy},
 * {@link Unowned}) do not, so a rollback never overwrites a race's write with an older value.
 */
public final class WorldUndo {
    private WorldUndo() {
    }

    private static @Nullable WorldTxn entityTxn(final Entity entity) {
        WorldLock lock = entity.worldLock;
        if (lock == null) {
            return null;
        }

        WorldTxn txn = lock.owner;
        return txn != null && txn.thread == Thread.currentThread() && txn.isLogging() ? txn : null;
    }

    private static @Nullable WorldTxn loggingTxn() {
        WorldTxn txn = WorldTxn.currentWorker();
        return txn != null && txn.isLogging() ? txn : null;
    }

    /** Whether the current event logs its writes. */
    public static boolean isLogging() {
        return loggingTxn() != null;
    }

    /** Logs {@code undo} to run if the current event is rolled back. */
    public static void action(final Runnable undo) {
        WorldTxn txn = loggingTxn();
        if (txn != null) {
            txn.log().action(undo);
        }
    }

    public static void entityI(final Entity entity, final int old, final int site) {
        WorldTxn txn = entityTxn(entity);
        if (txn != null) {
            txn.log().field(entity, site, old, null);
        }
    }

    public static void entityJ(final Entity entity, final long old, final int site) {
        WorldTxn txn = entityTxn(entity);
        if (txn != null) {
            txn.log().field(entity, site, old, null);
        }
    }

    public static void entityF(final Entity entity, final float old, final int site) {
        WorldTxn txn = entityTxn(entity);
        if (txn != null) {
            txn.log().field(entity, site, Float.floatToRawIntBits(old), null);
        }
    }

    public static void entityD(final Entity entity, final double old, final int site) {
        WorldTxn txn = entityTxn(entity);
        if (txn != null) {
            txn.log().field(entity, site, Double.doubleToRawLongBits(old), null);
        }
    }

    public static void entityL(final Entity entity, final @Nullable Object old, final int site) {
        WorldTxn txn = entityTxn(entity);
        if (txn != null) {
            txn.log().field(entity, site, 0L, old);
        }
    }

    public static void anyI(final Object target, final int old, final int site) {
        WorldTxn txn = loggingTxn();
        if (txn != null) {
            txn.log().field(target, site, old, null);
        }
    }

    public static void anyJ(final Object target, final long old, final int site) {
        WorldTxn txn = loggingTxn();
        if (txn != null) {
            txn.log().field(target, site, old, null);
        }
    }

    public static void anyF(final Object target, final float old, final int site) {
        WorldTxn txn = loggingTxn();
        if (txn != null) {
            txn.log().field(target, site, Float.floatToRawIntBits(old), null);
        }
    }

    public static void anyD(final Object target, final double old, final int site) {
        WorldTxn txn = loggingTxn();
        if (txn != null) {
            txn.log().field(target, site, Double.doubleToRawLongBits(old), null);
        }
    }

    public static void anyL(final Object target, final @Nullable Object old, final int site) {
        WorldTxn txn = loggingTxn();
        if (txn != null) {
            txn.log().field(target, site, 0L, old);
        }
    }
}
