package net.minecraft.server.level;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/**
 * Fires many timers that share one period but have their own phase: every entity, ticking chunk
 * and block entity asks to run once per simulation step.
 *
 * <p>A period is split into {@link #SLOTS} phase slots. A timer lives in one slot for as long as it
 * is active, and a single driver thread walks the slots in time order and fires what it finds.
 * Firing a timer is one callback, where a delay queue would pay a heap removal, a heap insertion
 * and a new future every period; with a hundred thousand falling blocks that heap work alone
 * saturated the timer thread and starved chunk timers.
 *
 * <p>Like the timers it replaces, the wheel coalesces: if the driver falls a whole period behind,
 * each slot fires once and the rest is skipped. Stopped timers are dropped the next time their
 * slot comes round.
 */
final class PhaseWheel {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int SLOTS = 64;
    private static final long MIN_SLOT_NANOS = 20_000L;
    private final ArrayList<Timer>[] slots;
    private final LongSupplier periodNanos;
    private final Runnable afterSlot;
    private final Thread driver;
    private volatile boolean running = true;
    private int nextSlot;

    /** One repeating timer. {@link #active} is the caller's; the wheel only reads it. */
    abstract static class Timer {
        volatile boolean active = true;
        /** Whether the timer sits in a slot. Guarded by the timer's monitor. */
        private boolean inWheel;

        /** Runs on the driver thread, so it must only queue work. */
        abstract void fire();
    }

    @SuppressWarnings("unchecked")
    PhaseWheel(final String name, final LongSupplier periodNanos, final Runnable afterSlot) {
        this.periodNanos = periodNanos;
        this.afterSlot = afterSlot;
        this.slots = new ArrayList[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            this.slots[i] = new ArrayList<>();
        }

        this.driver = new Thread(this::drive, name);
        this.driver.setDaemon(true);
        this.driver.start();
    }

    /**
     * Adds an active timer to the slot the driver reaches last, so it first fires about one period
     * from now. Does nothing if the timer is already in the wheel.
     */
    void add(final Timer timer) {
        synchronized (timer) {
            if (timer.inWheel || !this.running) {
                return;
            }

            timer.inWheel = true;
        }

        int slot = Math.floorMod(this.nextSlot - 1, SLOTS);
        ArrayList<Timer> list = this.slots[slot];
        synchronized (list) {
            list.add(timer);
        }
    }

    void stop() {
        this.running = false;
        LockSupport.unpark(this.driver);
    }

    private void drive() {
        long slotStart = System.nanoTime();
        while (this.running) {
            long period = Math.max(1L, this.periodNanos.getAsLong());
            long width = Math.max(MIN_SLOT_NANOS, period / SLOTS);
            long now = System.nanoTime();
            if (now - slotStart < width) {
                LockSupport.parkNanos(slotStart + width - now);
                continue;
            }

            if (now - slotStart >= period) {
                // A whole period behind: fire every slot once and skip the rest.
                for (int i = 0; i < SLOTS && this.running; i++) {
                    this.fireSlot(this.nextSlot);
                    this.nextSlot = (this.nextSlot + 1) % SLOTS;
                }

                slotStart = System.nanoTime();
                this.afterSlot.run();
                continue;
            }

            this.fireSlot(this.nextSlot);
            this.nextSlot = (this.nextSlot + 1) % SLOTS;
            slotStart += width;
            this.afterSlot.run();
        }
    }

    private void fireSlot(final int index) {
        ArrayList<Timer> list = this.slots[index];
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Timer timer = list.get(i);
                if (!timer.active) {
                    boolean drop;
                    synchronized (timer) {
                        drop = !timer.active;
                        if (drop) {
                            timer.inWheel = false;
                        }
                    }

                    if (drop) {
                        // Order within a slot does not matter, so swap-remove.
                        int last = list.size() - 1;
                        list.set(i, list.get(last));
                        list.remove(last);
                        continue;
                    }
                }

                try {
                    timer.fire();
                } catch (Throwable error) {
                    LOGGER.error("Timer {} failed", timer, error);
                }
            }
        }
    }
}
