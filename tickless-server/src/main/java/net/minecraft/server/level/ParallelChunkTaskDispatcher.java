package net.minecraft.server.level;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.CrashReport;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

/** Runs generation tasks for distinct centers in parallel. Actual neighboring
 * block writes are serialized by ChunkMap's feature-step neighborhood locks. */
public class ParallelChunkTaskDispatcher extends ChunkTaskDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final LongSet activeChunks = new LongOpenHashSet();
    private final int maxParallelChunks;
    private volatile int activeCount;

    public ParallelChunkTaskDispatcher(final TaskScheduler<Runnable> executor, final Executor dispatcherExecutor, final int maxParallelChunks) {
        super(executor, dispatcherExecutor);
        this.maxParallelChunks = Math.max(1, maxParallelChunks);
    }

    @Override
    public boolean hasWork() {
        return this.activeCount != 0 || super.hasWork();
    }

    @Override
    protected void pollTask() {
        this.dispatcher.schedule(new StrictQueue.RunnableWithPriority(3, () -> {
            if (this.activeChunks.size() >= this.maxParallelChunks) {
                this.sleeping = true;
                return;
            }

            ChunkTaskPriorityQueue.TasksForChunk tasks = this.queue.pop(this::canRun);
            if (tasks == null) {
                this.sleeping = true;
                return;
            }

            this.activeChunks.add(tasks.chunkPos());
            this.activeCount = this.activeChunks.size();
            this.scheduleForExecution(tasks);
            if (this.activeCount < this.maxParallelChunks && this.queue.hasWork()) {
                this.pollTask();
            } else {
                this.sleeping = true;
            }
        }));
    }

    private boolean canRun(final long chunkPos) {
        return !this.activeChunks.contains(chunkPos);
    }

    @Override
    protected void scheduleForExecution(final ChunkTaskPriorityQueue.TasksForChunk tasks) {
        CompletableFuture<Void> finished = this.executor.scheduleWithResult(completion -> {
            try {
                for (Runnable task : tasks.tasks()) {
                    task.run();
                }

                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        finished.whenComplete((unused, failure) -> this.dispatcher.schedule(new StrictQueue.RunnableWithPriority(3, () -> {
            this.activeChunks.remove(tasks.chunkPos());
            this.activeCount = this.activeChunks.size();
            if (failure != null) {
                LOGGER.error("Chunk generation task failed at {}", ChunkPos.unpack(tasks.chunkPos()), failure);
                BlockableEventLoop.relayDelayCrash(
                    CrashReport.forThrowable(new IllegalStateException("Parallel chunk generation failed", failure), "Parallel chunk generation task")
                );
            }

            this.sleeping = false;
            this.pollTask();
        })));
    }
}
