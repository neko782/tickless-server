package net.minecraft.server.network;

import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.parallel.WorldLocks;
import net.minecraft.world.level.parallel.WorldTxn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Runs one player's packets and simulation steps in order, on world workers.
 *
 * <p>The server thread keeps the connection alive, sends chunks and maintains tracker membership; it
 * never runs a player's world work, so it never waits for a world lock on a player's behalf. Before,
 * a player's movement or block packet that needed a lock held by a big explosion stopped the server
 * thread, and with it every player's connection, for as long as the explosion ran.
 *
 * <p>Each world packet and each step is one worker transaction. Packets that change global state
 * (respawning, game rules, command blocks, client settings) still run on the server thread: the
 * lane hands them over and waits, so they keep their order with the player's other packets. The
 * lane holds no world locks while it waits.
 */
public final class PlayerLane {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BATCH = 64;
    // -Dtickless.immediateAcks=false acknowledges block actions at the connection tick, as before.
    private static final boolean IMMEDIATE_ACKS = Boolean.parseBoolean(System.getProperty("tickless.immediateAcks", "true"));
    private final MinecraftServer server;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    // A drainer is queued or running. Only the drainer runs items, so they run one at a time.
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicInteger queuedTicks = new AtomicInteger();
    // The thread running this lane's current item: a worker, or the server thread for a handed-over packet.
    private volatile @Nullable Thread runner;
    private volatile boolean closed;

    public PlayerLane(final MinecraftServer server) {
        this.server = server;
    }

    /** Whether new work goes to this lane; otherwise it runs on the server thread as in vanilla. */
    public boolean isActive() {
        return !this.closed && WorldTxn.isEnabled() && this.server.isRunning();
    }

    /** Whether the current thread is running this lane's current item. */
    public boolean isRunningHere() {
        return this.runner == Thread.currentThread();
    }

    public <T extends PacketListener> void submitPacket(final T listener, final Packet<T> packet, final boolean serverThread) {
        if (serverThread) {
            this.submit(() -> this.runOnServerThread(() -> PacketProcessor.handlePacket(listener, packet)));
        } else {
            this.submit(() -> this.server.worldWorkers().runEvent(0, 0, 0, false, () -> handleOnWorker(listener, packet), packet));
        }
    }

    private static <T extends PacketListener> void handleOnWorker(final T listener, final Packet<T> packet) {
        WorldTxn txn = WorldTxn.current();
        if (!IMMEDIATE_ACKS || !(listener instanceof ServerGamePacketListenerImpl game) || txn == null) {
            PacketProcessor.handlePacket(listener, packet);
            return;
        }

        // Block actions are acknowledged when this event commits, not at the next connection tick.
        game.beginLaneAcks(txn);
        try {
            PacketProcessor.handlePacket(listener, packet);
        } finally {
            game.finishLaneAcks(txn);
        }
    }

    /** Queues a chat message or command handler. */
    public void submitChat(final Runnable handler, final boolean serverThread) {
        if (serverThread) {
            this.submit(() -> this.runOnServerThread(handler));
        } else {
            this.submit(() -> this.server.worldWorkers().runEvent(0, 0, 0, false, handler, "player command"));
        }
    }

    /** Queues one simulation step for {@code player}. Returns false if the lane is not active. */
    public boolean submitTick(final ServerPlayer player, final Runnable step) {
        if (!this.isActive()) {
            return false;
        }

        this.submit(() -> {
            BlockPos pos = player.blockPosition();
            this.server.worldWorkers().runEvent(pos.getX(), pos.getY(), pos.getZ(), true, () -> {
                WorldTxn txn = WorldTxn.current();
                if (txn != null) {
                    // Like other entity updates: the step reads many blocks around it, and every write still locks.
                    txn.disableReadLocks();
                }

                WorldLocks.lockEntityTree(player);
                step.run();
            }, player);
        });
        return true;
    }

    /** Tracking shares the player's packet order, without making the server thread wait. */
    public boolean submitTracking(final ServerPlayer player, final Runnable action, final Runnable completed) {
        if (!this.isActive()) return false;
        this.submit(() -> {
            try {
                BlockPos pos = player.blockPosition();
                this.server.worldWorkers().runEvent(pos.getX(), pos.getY(), pos.getZ(), true, () -> {
                    WorldTxn txn = WorldTxn.current();
                    if (txn != null) txn.disableReadLocks();
                    WorldLocks.lockEntityTree(player);
                    if (!player.isRemoved()) {
                        // Tracker codec/cache mutations are not world undo-log state. Acquire the
                        // source entity first, then finish this short update without replaying it.
                        // It takes no more locks, so it skips the irrevocable token and players
                        // are still tracked in parallel.
                        WorldTxn.makeIrrevocableHoldingLocks();
                        action.run();
                    }
                }, "player tracking");
            } finally {
                completed.run();
            }
        });
        return true;
    }

    /**
     * Stops the lane and runs {@code action} (removing the player) on the server thread after
     * whatever the lane is running now. Runs it at once when the lane is idle; otherwise it runs
     * as the lane's last item, so the player is never removed in the middle of one of its packets.
     */
    public void close(final Runnable action) {
        this.closed = true;
        this.queue.clear();
        if (this.scheduled.compareAndSet(false, true)) {
            Thread outer = this.runner;
            this.runner = Thread.currentThread();
            try {
                action.run();
            } finally {
                this.runner = outer;
                this.scheduled.set(false);
            }

            this.startDrainer();
        } else {
            this.queue.add(() -> this.runOnServerThread(action));
            this.startDrainer();
        }
    }

    private void submit(final Runnable item) {
        this.queue.add(item);
        this.startDrainer();
    }

    private void startDrainer() {
        if (!this.queue.isEmpty() && this.scheduled.compareAndSet(false, true)) {
            this.server.worldWorkers().execute(this::drain);
        }
    }

    private void drain() {
        this.runner = Thread.currentThread();
        try {
            for (int i = 0; i < BATCH; i++) {
                Runnable item = this.queue.poll();
                if (item == null) {
                    break;
                }

                try {
                    item.run();
                } catch (Throwable t) {
                    LOGGER.error("Exception in player lane", t);
                }
            }
        } finally {
            this.runner = null;
            this.scheduled.set(false);
        }

        // Items added after the last poll found the drainer still marked as scheduled.
        this.startDrainer();
    }

    /** Runs {@code action} on the server thread as this lane's current item and waits for it. */
    private void runOnServerThread(final Runnable action) {
        Thread lane = Thread.currentThread();
        AtomicBoolean claimed = new AtomicBoolean();
        CompletableFuture<Void> done = new CompletableFuture<>();
        this.server.execute(() -> {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }

            this.runner = Thread.currentThread();
            try {
                action.run();
            } finally {
                this.runner = lane;
                done.complete(null);
            }
        });

        while (true) {
            try {
                // No world locks are held here, so this wait cannot close a cycle.
                done.get(100L, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException e) {
                // A stopping server may never run the task; drop it so worker shutdown can finish.
                if (!this.server.isRunning() && claimed.compareAndSet(false, true)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                return;
            }
        }
    }
}
