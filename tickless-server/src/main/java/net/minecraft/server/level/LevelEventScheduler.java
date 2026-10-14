package net.minecraft.server.level;

import com.mojang.logging.LogUtils;
import java.util.Queue;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.level.parallel.WorldLocks;
import net.minecraft.world.level.parallel.WorldTxn;
import net.minecraft.world.level.parallel.WorldWorkers;
import org.slf4j.Logger;

/**
 * Dispatches one level's world events to the world workers.
 *
 * <p>There is no level-wide tick phase. Ticking entities and chunks own timers that submit
 * work independently of the server's game clock update. Each chunk has a coalescing event
 * stream: at most one random-tick run is queued
 * per chunk, and scheduled block and fluid ticks have their own deadline timer. A chunk or entity that falls
 * behind skips the missed steps instead of queueing them, so overload slows down only the busy
 * area rather than the whole server. Every individual event (scheduled tick, random tick, block
 * event, block entity update, entity update) runs in its own world transaction.
 */
public final class LevelEventScheduler {
    /** Recorded by benchmark manifests so incompatible passenger counters cannot be compared. */
    public static final String ENTITY_STEP_METRIC = "committed-entities-and-passengers-v2";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STREAM_IDLE = 0;
    private static final int STREAM_QUEUED = 1;
    private static final int STREAM_RUNNING = 2;
    private static final int STREAM_RUNNING_REQUESTED = 3;
    private static final int ENTITY_BATCH = 16;
    private static final int BLOCK_ENTITY_BATCH = 8;
    /** Whether idle block entities stop ticking until woken; see {@link BlockEntity#isIdle()}. */
    private static final boolean SLEEPING_BLOCK_ENTITIES = Boolean.parseBoolean(System.getProperty("tickless.sleepingBlockEntities", "true"));
    /** Block entities fired in the current wheel slot; only the wheel's driver thread touches it. */
    private final List<BlockEntityTimer> blockEntityBatch = new java.util.ArrayList<>();
    private final ServerLevel level;
    private final WorldWorkers workers;
    private final ConcurrentHashMap<Entity, EntityTimer> tickingEntities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ChunkTimer> tickingChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledChunkTimer> scheduledChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockPos, BlockEntityTimer> blockEntityTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.Set<BlockEntityTimer>> blockEntitiesByChunk = new ConcurrentHashMap<>();
    private final List<PeriodicEvent> periodicEvents = new CopyOnWriteArrayList<>();
    private final Queue<Entity> pendingEntities = new ConcurrentLinkedQueue<>();
    private final AtomicInteger entityDrainers = new AtomicInteger();
    private final ScheduledThreadPoolExecutor eventTimers = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "World event timer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean timersStarted;
    /** Entity, chunk and block entity timers, which all repeat once per simulation step. */
    private final PhaseWheel wheel;
    /** The current game time and when the simulation advanced to it; published together. */
    private volatile GameClock clock;
    private final Queue<ClockWaiter> awaitingClock = new ConcurrentLinkedQueue<>();
    private final Queue<BlockEventData> deferredBlockEvents = new ConcurrentLinkedQueue<>();
    public final LongAdder chunkRuns = new LongAdder();
    public final LongAdder chunkRequests = new LongAdder();
    public final LongAdder entityRequests = new LongAdder();
    /** Successfully committed non-player entity steps, independent of timer requests. */
    public final LongAdder completedEntitySteps = new LongAdder();
    /** Advanced player simulation steps counted only after successful commit. */
    public final LongAdder completedPlayerSteps = new LongAdder();
    public final LongAdder skippedChunkSteps = new LongAdder();
    public final LongAdder skippedEntitySteps = new LongAdder();
    /** Block entity steps skipped because the block entity was asleep. */
    public final LongAdder sleepingBlockEntitySteps = new LongAdder();

    public LevelEventScheduler(final ServerLevel level, final WorldWorkers workers) {
        this.level = level;
        this.workers = workers;
        this.clock = new GameClock(level.getGameTime(), System.nanoTime());
        this.eventTimers.setRemoveOnCancelPolicy(true);
        this.wheel = new PhaseWheel("World event phases", () -> this.level.tickRateManager().nanosecondsPerTick(), this::flushBlockEntityBatch);
    }

    public WorldWorkers workers() {
        return this.workers;
    }

    public void onTickingStart(final Entity entity) {
        EntityTimer timer = new EntityTimer(entity);
        if (this.tickingEntities.putIfAbsent(entity, timer) == null && this.timersStarted) {
            this.wheel.add(timer);
        }
    }

    public void onTickingEnd(final Entity entity) {
        EntityTimer timer = this.tickingEntities.remove(entity);
        if (timer != null) {
            timer.active = false;
        }
    }

    public boolean isTicking(final Entity entity) {
        return this.tickingEntities.containsKey(entity);
    }

    public int tickingEntityCount() {
        return this.tickingEntities.size();
    }

    /** Starts timers only after world transactions and the server event loop are ready. */
    public void startTimers() {
        this.timersStarted = true;
        for (EntityTimer timer : this.tickingEntities.values()) {
            this.wheel.add(timer);
        }
        for (ChunkTimer timer : this.tickingChunks.values()) {
            this.wheel.add(timer);
        }
        for (ScheduledChunkTimer timer : this.scheduledChunks.values()) {
            this.refreshScheduledTimer(timer);
        }
        for (BlockEntityTimer timer : this.blockEntityTimers.values()) {
            if (this.tickingChunks.containsKey(timer.chunkKey)) {
                this.resumeBlockEntity(timer);
            }
        }
        for (PeriodicEvent timer : this.periodicEvents) {
            this.schedulePeriodic(timer);
        }
    }

    /** Stops producers before the server drains world workers during shutdown. */
    public void stopTimers() {
        this.timersStarted = false;
        for (Entity entity : this.tickingEntities.keySet()) {
            this.onTickingEnd(entity);
        }
        for (long key : this.tickingChunks.keySet()) {
            this.onChunkTickingEnd(key);
        }
        for (BlockPos pos : this.blockEntityTimers.keySet()) {
            this.onBlockEntityTickerRemoved(pos);
        }
        for (PeriodicEvent timer : this.periodicEvents) {
            synchronized (timer) {
                timer.active = false;
                if (timer.future != null) {
                    timer.future.cancel(false);
                }
            }
        }
        this.eventTimers.shutdownNow();
        this.wheel.stop();
    }

    public void addPeriodicEvent(final Runnable event, final Object description, final boolean serverThread) {
        PeriodicEvent timer = new PeriodicEvent(event, description, serverThread);
        this.periodicEvents.add(timer);
        if (this.timersStarted) {
            this.schedulePeriodic(timer);
        }
    }

    private void schedulePeriodic(final PeriodicEvent timer) {
        synchronized (timer) {
            if (!timer.active || !this.timersStarted || this.eventTimers.isShutdown()) {
                return;
            }
            long delay = this.nextDelayNanos(timer);
            timer.future = this.eventTimers.schedule(() -> {
                try {
                    if (timer.active && this.timersStarted && timer.queued.compareAndSet(false, true)) {
                        Runnable action = () -> {
                            try {
                                timer.event.run();
                            } finally {
                                timer.queued.set(false);
                            }
                        };
                        if (timer.serverThread) {
                            this.level.getServer().execute(action);
                        } else {
                            this.workers.execute(() -> this.workers.runEvent(0, 0, 0, false, action, timer.description));
                        }
                    }
                } catch (Throwable error) {
                    timer.queued.set(false);
                    LOGGER.error("Could not schedule periodic event {}", timer.description, error);
                } finally {
                    this.schedulePeriodic(timer);
                }
            }, delay, TimeUnit.NANOSECONDS);
        }
    }

    public void updateGameTime(final long gameTime) {
        if (gameTime == this.clock.gameTime()) {
            return;
        }
        this.clock = new GameClock(gameTime, System.nanoTime());
        // Release scheduled-tick timers that fired before this step started.
        int count = this.awaitingClock.size();
        for (int i = 0; i < count; i++) {
            ClockWaiter waiter = this.awaitingClock.poll();
            if (waiter == null) {
                break;
            }
            this.fireScheduledTimer(waiter.timer(), waiter.generation());
        }
    }

    public void onChunkTickingStart(final LevelChunk chunk) {
        ChunkTimer timer = new ChunkTimer(chunk);
        if (this.tickingChunks.putIfAbsent(chunk.getPos().pack(), timer) == null && this.timersStarted) {
            this.wheel.add(timer);
        }
        ScheduledChunkTimer scheduled = new ScheduledChunkTimer(chunk);
        if (this.scheduledChunks.putIfAbsent(chunk.getPos().pack(), scheduled) == null && this.timersStarted) {
            this.refreshScheduledTimer(scheduled);
        }
        for (TickingBlockEntity ticker : chunk.getBlockEntityTickers()) {
            this.onBlockEntityTickerAdded(ticker);
        }
        // Registration can reach the scheduler before the chunk publishes its
        // wrapper. Resume the registry as well so promotion cannot miss it.
        java.util.Set<BlockEntityTimer> blockEntities = this.blockEntitiesByChunk.get(chunk.getPos().pack());
        if (blockEntities != null) {
            for (BlockEntityTimer blockEntityTimer : blockEntities) {
                this.resumeBlockEntity(blockEntityTimer);
            }
        }
    }

    public void onChunkTickingEnd(final long chunkKey) {
        ChunkTimer timer = this.tickingChunks.remove(chunkKey);
        if (timer != null) {
            timer.active = false;
        }
        ScheduledChunkTimer scheduled = this.scheduledChunks.remove(chunkKey);
        if (scheduled != null) {
            synchronized (scheduled) {
                scheduled.active = false;
                scheduled.generation++;
                if (scheduled.future != null) {
                    scheduled.future.cancel(false);
                }
            }
        }
        java.util.Set<BlockEntityTimer> blockEntities = this.blockEntitiesByChunk.get(chunkKey);
        if (blockEntities != null) {
            for (BlockEntityTimer blockEntityTimer : blockEntities) {
                this.pauseBlockEntity(blockEntityTimer);
            }
        }
    }

    public void onScheduledTickAdded(final long chunkKey, final long triggerTick) {
        ScheduledChunkTimer timer = this.scheduledChunks.get(chunkKey);
        if (timer != null) {
            this.scheduleScheduledTimer(timer, triggerTick);
        }
    }

    /** Recomputes game-time deadlines when the number of nanoseconds per step changes. */
    public void onTickRateChanged() {
        for (ScheduledChunkTimer timer : this.scheduledChunks.values()) {
            synchronized (timer) {
                if (timer.queued.get()) {
                    continue;
                }
                timer.generation++;
                if (timer.future != null) {
                    timer.future.cancel(false);
                    timer.future = null;
                }
                timer.nextTriggerTick = Long.MAX_VALUE;
            }
            this.refreshScheduledTimer(timer);
        }
    }

    public void onBlockEntityTickerAdded(final TickingBlockEntity ticker) {
        BlockPos pos = ticker.getPos().immutable();
        BlockEntityTimer timer = new BlockEntityTimer(pos, ticker, ChunkPos.pack(pos));
        BlockEntityTimer existing = this.blockEntityTimers.putIfAbsent(pos, timer);
        if (existing != null) {
            // A chunk can stop ticking and become available again before it is
            // fully unloaded. Its wrappers and paused timers then still exist.
            if (this.timersStarted && this.tickingChunks.containsKey(existing.chunkKey)) {
                this.resumeBlockEntity(existing);
            }
            return;
        }
        this.blockEntitiesByChunk.compute(timer.chunkKey, (key, blockEntities) -> {
            java.util.Set<BlockEntityTimer> result = blockEntities == null ? ConcurrentHashMap.newKeySet() : blockEntities;
            result.add(timer);
            return result;
        });
        if (this.timersStarted && this.tickingChunks.containsKey(timer.chunkKey)) {
            this.resumeBlockEntity(timer);
        }
    }

    public void onBlockEntityTickerRemoved(final BlockPos pos) {
        BlockEntityTimer timer = this.blockEntityTimers.remove(pos);
        if (timer != null) {
            this.pauseBlockEntity(timer);
            this.blockEntitiesByChunk.computeIfPresent(timer.chunkKey, (key, blockEntities) -> {
                blockEntities.remove(timer);
                return blockEntities.isEmpty() ? null : blockEntities;
            });
        }
    }

    private void resumeBlockEntity(final BlockEntityTimer timer) {
        synchronized (timer) {
            if (timer.active || !this.timersStarted) {
                return;
            }
            timer.active = true;
        }
        this.wheel.add(timer);
    }

    private void pauseBlockEntity(final BlockEntityTimer timer) {
        timer.active = false;
    }

    /** Runs a block-local event at {@code pos}, locking that block first. */
    public void runBlockEvent(final BlockPos pos, final Runnable event, final Object description) {
        this.workers.runEvent(pos.getX(), pos.getY(), pos.getZ(), true, () -> {
            this.level.worldLocks().lockBlock(pos);
            event.run();
        }, description);
    }

    /** Queues a block-local event to run on a world worker. */
    public void runBlockEventAsync(final BlockPos pos, final Runnable event, final Object description) {
        this.workers.execute(() -> this.runBlockEvent(pos, event, description));
    }

    /** Queues an event that is not tied to one block, such as a raid or custom spawner. */
    public void submitGlobal(final Runnable event, final Object description) {
        this.workers.execute(() -> this.workers.runEvent(0, 0, 0, false, event, description));
    }

    // Chunk streams

    private void refreshScheduledTimer(final ScheduledChunkTimer timer) {
        long next = Long.MAX_VALUE;
        LevelChunkTicks<?> blocks = this.level.getBlockTicks().getContainer(timer.chunkKey);
        LevelChunkTicks<?> fluids = this.level.getFluidTicks().getContainer(timer.chunkKey);
        ScheduledTick<?> blockTick = blocks == null ? null : blocks.peek();
        ScheduledTick<?> fluidTick = fluids == null ? null : fluids.peek();
        if (blockTick != null) {
            next = blockTick.triggerTick();
        }
        if (fluidTick != null) {
            next = Math.min(next, fluidTick.triggerTick());
        }
        this.scheduleScheduledTimer(timer, next);
    }

    /**
     * Arms the chunk's timer for {@code triggerTick} unless it is already armed for an earlier step.
     * Deadlines count from when the simulation reached the current game time, so chained ticks do
     * not accumulate dispatch latency.
     */
    private void scheduleScheduledTimer(final ScheduledChunkTimer timer, final long triggerTick) {
        synchronized (timer) {
            if (!timer.active || !this.timersStarted || this.eventTimers.isShutdown() || timer.queued.get()) {
                return;
            }
            // A chunk drains at most once per game time, like vanilla; later additions wait for the next step.
            long target = triggerTick == Long.MAX_VALUE ? triggerTick : Math.max(triggerTick, timer.lastRunTick + 1L);
            if (timer.nextTriggerTick <= target) {
                return;
            }
            long generation = ++timer.generation;
            if (timer.future != null) {
                timer.future.cancel(false);
                timer.future = null;
            }
            timer.nextTriggerTick = target;
            if (target == Long.MAX_VALUE) {
                return;
            }
            GameClock clock = this.clock;
            long delay = 0L;
            if (target > clock.gameTime()) {
                long interval = Math.max(1L, this.level.tickRateManager().nanosecondsPerTick());
                long steps = target - clock.gameTime();
                delay = steps > Long.MAX_VALUE / interval
                    ? Long.MAX_VALUE
                    : Math.max(0L, steps * interval - (System.nanoTime() - clock.startNanos()));
            }
            try {
                timer.future = this.eventTimers.schedule(() -> this.fireScheduledTimer(timer, generation), delay, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Stopping.
            }
        }
    }

    private void fireScheduledTimer(final ScheduledChunkTimer timer, final long generation) {
        long gameTime;
        synchronized (timer) {
            if (timer.generation != generation) {
                return;
            }
            timer.future = null;
            if (!timer.active || !this.timersStarted || timer.queued.get()) {
                return;
            }
            gameTime = this.clock.gameTime();
            if (timer.nextTriggerTick > gameTime) {
                // The simulation step is late or frozen. Wait for the clock instead of polling.
                this.awaitingClock.add(new ClockWaiter(timer, generation));
                return;
            }
            timer.generation++;
            timer.nextTriggerTick = Long.MAX_VALUE;
            timer.lastRunTick = gameTime;
            timer.queued.set(true);
        }
        try {
            this.workers.execute(() -> {
                try {
                    WorldTxn.runEventSource(() -> {
                        if (timer.active && this.level.tickRateManager().runsNormally() && !this.level.isDebug()) {
                            this.level.getBlockTicks().runDueTicks(timer.chunkKey, gameTime, 65536,
                                (scheduled, run) -> this.runBlockEvent(scheduled.pos(), run, scheduled), this.level::tickBlock);
                            this.level.getFluidTicks().runDueTicks(timer.chunkKey, gameTime, 65536,
                                (scheduled, run) -> this.runBlockEvent(scheduled.pos(), run, scheduled), this.level::tickFluid);
                        }
                    });
                } finally {
                    timer.queued.set(false);
                    this.refreshScheduledTimer(timer);
                }
            });
        } catch (Throwable error) {
            timer.queued.set(false);
            LOGGER.error("Could not queue scheduled ticks for {}", timer.chunk.getPos(), error);
            this.refreshScheduledTimer(timer);
        }
    }

    /** Whether the timer's block entity went idle and nothing has woken it since. */
    private boolean asleep(final BlockEntityTimer timer) {
        Sleep sleep = timer.sleep;
        if (sleep != null && sleep.blockEntity.wakeups() == sleep.wakeups && timer.ticker.sleeper() == sleep.blockEntity) {
            this.sleepingBlockEntitySteps.increment();
            return true;
        }

        return false;
    }

    /** Runs on the wheel's driver thread. */
    private void fireBlockEntity(final BlockEntityTimer timer) {
        if (this.timersStarted && this.tickingChunks.containsKey(timer.chunkKey)
            && !timer.ticker.isRemoved() && this.level.tickRateManager().runsNormally()
            && this.level.shouldTickBlocksAt(timer.chunkKey) && !this.asleep(timer) && timer.queued.compareAndSet(false, true)) {
            this.blockEntityBatch.add(timer);
            if (this.blockEntityBatch.size() >= BLOCK_ENTITY_BATCH) {
                this.flushBlockEntityBatch();
            }
        }
    }

    /**
     * Queues the block entities that fired in the current wheel slot, a few per task. Each still
     * ticks in its own transaction; a task per ticker woke a carrier per tick, which cost more
     * than small tickers such as moving pistons. A slow ticker delays at most the others in its
     * batch. Runs on the wheel's driver thread.
     */
    private void flushBlockEntityBatch() {
        if (this.blockEntityBatch.isEmpty()) {
            return;
        }

        BlockEntityTimer[] batch = this.blockEntityBatch.toArray(new BlockEntityTimer[0]);
        this.blockEntityBatch.clear();
        try {
            this.workers.execute(() -> {
                for (BlockEntityTimer timer : batch) {
                    try {
                        // Read before the tick takes the block's lock: a change that wakes the
                        // block entity after this read keeps it awake.
                        BlockEntity sleeper = SLEEPING_BLOCK_ENTITIES ? timer.ticker.sleeper() : null;
                        long wakeups = sleeper == null ? 0L : sleeper.wakeups();
                        boolean committed = this.workers.tryRunEvent(timer.pos.getX(), timer.pos.getY(), timer.pos.getZ(), true, () -> {
                            this.level.worldLocks().lockBlock(timer.pos);
                            // A task can have waited at the exclusive gate while its chunk unloaded.
                            if (timer.active && !timer.ticker.isRemoved()) {
                                timer.ticker.tick();
                            }
                        }, timer.ticker);
                        if (committed && sleeper != null && sleeper.isIdle()) {
                            timer.sleep = new Sleep(sleeper, wakeups);
                        }
                    } finally {
                        timer.queued.set(false);
                    }
                }
            });
        } catch (Throwable error) {
            for (BlockEntityTimer timer : batch) {
                timer.queued.set(false);
            }

            throw error;
        }
    }


    public void requestChunk(final LevelChunk chunk, final long gameTime) {
        LevelChunk.EventStream stream = chunk.eventStream();
        stream.requestedTick = gameTime;
        this.chunkRequests.increment();
        AtomicInteger state = stream.state;

        while (true) {
            int current = state.get();
            if (current == STREAM_IDLE) {
                if (state.compareAndSet(STREAM_IDLE, STREAM_QUEUED)) {
                    this.workers.execute(() -> this.runChunk(chunk));
                    return;
                }
            } else if (current == STREAM_RUNNING) {
                if (state.compareAndSet(STREAM_RUNNING, STREAM_RUNNING_REQUESTED)) {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void runChunk(final LevelChunk chunk) {
        LevelChunk.EventStream stream = chunk.eventStream();
        stream.state.set(STREAM_RUNNING);
        this.chunkRuns.increment();

        try {
            long tick = stream.requestedTick;
            if (tick > stream.lastTick) {
                if (stream.lastTick != Long.MIN_VALUE && tick > stream.lastTick + 1L) {
                    this.skippedChunkSteps.add(tick - stream.lastTick - 1L);
                }

                stream.lastTick = tick;
                this.runChunkStep(chunk);
            }
        } finally {
            if (!stream.state.compareAndSet(STREAM_RUNNING, STREAM_IDLE)) {
                // Asked again while running. Requeue instead of looping so other chunks get a turn.
                stream.state.set(STREAM_QUEUED);
                this.workers.execute(() -> this.runChunk(chunk));
            }
        }
    }

    private void runChunkStep(final LevelChunk chunk) {
        WorldTxn.runEventSource(() -> this.runChunkStepWhileLoaded(chunk));
    }

    private void runChunkStepWhileLoaded(final LevelChunk chunk) {
        if (!chunk.loaded() || !chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
            return;
        }
        if (this.level.tickRateManager().runsNormally() && !this.level.isDebug()) {
            this.level.tickChunk(chunk, this.level.randomTickSpeed());
        }

    }

    // Block events

    public void queueBlockEvent(final BlockEventData event) {
        if (!WorldTxn.isEnabled()) {
            // Before the world starts ticking (spawn preparation) and while stopping.
            this.deferredBlockEvents.add(event);
            return;
        }

        // Queued when the event that raised it commits: a rolled back event raises it again.
        WorldTxn.afterCommit(() -> this.workers.execute(() -> this.runQueuedBlockEvent(event)));
    }

    private void runQueuedBlockEvent(final BlockEventData event) {
        if (!this.level.shouldTickBlocksAt(event.pos())) {
            this.deferredBlockEvents.add(event);
            return;
        }

        this.runBlockEvent(event.pos(), () -> this.level.runBlockEvent(event), event);
    }

    public void retryDeferredBlockEvents() {
        int count = this.deferredBlockEvents.size();
        for (int i = 0; i < count; i++) {
            BlockEventData event = this.deferredBlockEvents.poll();
            if (event == null) {
                break;
            }

            if (this.level.isLoaded(event.pos())) {
                this.queueBlockEvent(event);
            }
        }
    }

    public void removeBlockEventsIf(final java.util.function.Predicate<BlockEventData> filter) {
        this.deferredBlockEvents.removeIf(filter);
    }

    // Entities

    private void requestEntity(final Entity entity) {
        boolean player = entity instanceof ServerPlayer;
        // Player lanes still process connection timers while frozen. Other frozen entities
        // cannot advance: avoid filling the worker queue with transactions that only return.
        if (!player && this.level.tickRateManager().isEntityFrozen(entity)) {
            return;
        }
        if (!player) {
            this.entityRequests.increment();
        }
        if (!entity.worldTickQueued.compareAndSet(false, true)) {
            if (!player) {
                this.skippedEntitySteps.increment();
            }
            return;
        }

        if (player) {
            ServerPlayer serverPlayer = (ServerPlayer)entity;
            Runnable step = () -> {
                try {
                    int before = entity.tickCount;
                    this.level.tickEntityEvent(entity);
                    if (serverPlayer.connection != null) {
                        serverPlayer.connection.tickPlayerTimer();
                    }
                    if (entity.tickCount != before) {
                        WorldTxn.afterCommit(this.completedPlayerSteps::increment);
                    }
                } finally {
                    entity.worldTickQueued.set(false);
                }
            };
            // On the player's lane, in order with its packets; the server thread only when the lane is off.
            if (serverPlayer.connection == null || !serverPlayer.connection.lane().submitTick(serverPlayer, step)) {
                this.level.getServer().execute(step);
            }
        } else {
            this.pendingEntities.add(entity);
            this.startEntityDrainer();
        }
    }

    private void startEntityDrainer() {
        int maximum = Math.max(1, this.workers.threads() * 4);
        while (!this.pendingEntities.isEmpty()) {
            int current = this.entityDrainers.get();
            if (current >= maximum) {
                return;
            }
            if (this.entityDrainers.compareAndSet(current, current + 1)) {
                this.workers.execute(this::drainEntities);
                return;
            }
        }
    }

    private void drainEntities() {
        try {
            for (int i = 0; i < ENTITY_BATCH; i++) {
                Entity entity = this.pendingEntities.poll();
                if (entity == null) {
                    break;
                }
                this.runEntity(entity);
            }
        } finally {
            this.entityDrainers.decrementAndGet();
            this.startEntityDrainer();
        }
    }

    private void runEntity(final Entity entity) {
        try {
            Entity vehicle = entity.getVehicle();
            if (vehicle != null && !vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                // The vehicle's step moves its passengers. This read races with riding changes; at
                // worst a just-dismounted passenger waits one step. Running the event only to find
                // out locked the whole stack and waited for the vehicle's own step.
                return;
            }

            int before = entity.tickCount;
            BlockPos pos = entity.blockPosition();
            if (this.workers.tryRunEvent(pos.getX(), pos.getY(), pos.getZ(), true, () -> {
                WorldTxn txn = WorldTxn.current();
                if (txn != null) {
                    txn.disableReadLocks();
                }

                WorldLocks.lockEntityTree(entity);
                this.level.tickEntityEvent(entity);
            }, entity) && entity.tickCount != before && !entity.isPassenger()) {
                // A vehicle's step also moves its passengers; a passenger's own event does not.
                this.completedEntitySteps.add(entity.isVehicle() ? entity.getPassengersAndSelf().count() : 1);
            }
        } finally {
            entity.worldTickQueued.set(false);
        }
    }

    private long nextDelayNanos(final TimerHandle timer) {
        long now = System.nanoTime();
        long interval = Math.max(1L, this.level.tickRateManager().nanosecondsPerTick());
        if (timer.nextDeadlineNanos == 0L) {
            timer.nextDeadlineNanos = now + interval;
        } else {
            long missed = Math.max(0L, (now - timer.nextDeadlineNanos) / interval);
            timer.nextDeadlineNanos += (missed + 1L) * interval;
        }
        return Math.max(1L, timer.nextDeadlineNanos - now);
    }

    private abstract static class TimerHandle {
        volatile boolean active = true;
        long nextDeadlineNanos;
        ScheduledFuture<?> future;
    }

    private final class EntityTimer extends PhaseWheel.Timer {
        private final Entity entity;

        private EntityTimer(final Entity entity) {
            this.entity = entity;
        }

        @Override
        void fire() {
            if (LevelEventScheduler.this.timersStarted) {
                LevelEventScheduler.this.requestEntity(this.entity);
            }
        }

        @Override
        public String toString() {
            return "entity " + this.entity;
        }
    }

    private final class ChunkTimer extends PhaseWheel.Timer {
        private final LevelChunk chunk;

        private ChunkTimer(final LevelChunk chunk) {
            this.chunk = chunk;
        }

        @Override
        void fire() {
            if (LevelEventScheduler.this.timersStarted) {
                LevelEventScheduler.this.requestChunk(this.chunk, LevelEventScheduler.this.clock.gameTime());
            }
        }

        @Override
        public String toString() {
            return "chunk " + this.chunk.getPos();
        }
    }

    private final class BlockEntityTimer extends PhaseWheel.Timer {
        private final BlockPos pos;
        private final TickingBlockEntity ticker;
        private final long chunkKey;
        private final AtomicBoolean queued = new AtomicBoolean();
        /** The last idle tick, written by the worker that ran it before it clears {@link #queued}. */
        private volatile @org.jspecify.annotations.Nullable Sleep sleep;

        private BlockEntityTimer(final BlockPos pos, final TickingBlockEntity ticker, final long chunkKey) {
            this.pos = pos;
            this.ticker = ticker;
            this.chunkKey = chunkKey;
            this.active = false;
        }

        @Override
        void fire() {
            LevelEventScheduler.this.fireBlockEntity(this);
        }

        @Override
        public String toString() {
            return "block entity at " + this.pos;
        }
    }

    /** A block entity that was idle after a tick, and its wake count read before that tick. */
    private record Sleep(BlockEntity blockEntity, long wakeups) {
    }

    private static final class ScheduledChunkTimer extends TimerHandle {
        private final LevelChunk chunk;
        private final long chunkKey;
        private final AtomicBoolean queued = new AtomicBoolean();
        /** Earliest armed or awaited game time; {@code Long.MAX_VALUE} while idle. */
        private long nextTriggerTick = Long.MAX_VALUE;
        private long lastRunTick = Long.MIN_VALUE;
        /** Invalidates stale timer callbacks and clock waiters. */
        private long generation;

        private ScheduledChunkTimer(final LevelChunk chunk) {
            this.chunk = chunk;
            this.chunkKey = chunk.getPos().pack();
        }
    }

    private record GameClock(long gameTime, long startNanos) {
    }

    private record ClockWaiter(ScheduledChunkTimer timer, long generation) {
    }

    private static final class PeriodicEvent extends TimerHandle {
        private final Runnable event;
        private final Object description;
        private final boolean serverThread;
        private final AtomicBoolean queued = new AtomicBoolean();

        private PeriodicEvent(final Runnable event, final Object description, final boolean serverThread) {
            this.event = event;
            this.description = description;
            this.serverThread = serverThread;
        }
    }

    public boolean isIdle() {
        return this.workers.isQuiescent();
    }

    public static long chunkKey(final BlockPos pos) {
        return ChunkPos.pack(pos);
    }
}
