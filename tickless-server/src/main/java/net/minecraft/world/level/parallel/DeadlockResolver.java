package net.minecraft.world.level.parallel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Breaks wait cycles between world transactions by rolling one of them back.
 *
 * <p>This runs only after a transaction fails to get a lock by spinning. Only one thread walks
 * the wait graph at a time; others skip their check and retry after their next wait, so a
 * crowd of waiters never queues up behind the resolver.
 *
 * <p>The victim is the youngest member of the cycle (largest start stamp) that can be rolled
 * back: a worker transaction that is logging and waits for a world lock or for the server
 * thread. A retried transaction keeps its stamp, so the oldest one is never chosen again and
 * every event finishes (the wait-for-graph form of wound-wait). Members that hold no monitor are
 * preferred: unwinding out of a latch can leave the data it guards half updated.
 */
final class DeadlockResolver {
    private static final ReentrantLock GRAPH = new ReentrantLock();
    // -Dtickless.traceCycles=N logs the first N cycles broken, with each member's stack.
    private static final AtomicInteger TRACE = new AtomicInteger(Integer.getInteger("tickless.traceCycles", 0));

    private DeadlockResolver() {
    }

    static void resolve(final WorldTxn start) {
        if (!GRAPH.tryLock()) {
            return;
        }

        try {
            List<WorldTxn> chain = new ArrayList<>();
            Map<WorldTxn, Integer> index = new IdentityHashMap<>();
            WorldTxn current = start;

            while (true) {
                index.put(current, chain.size());
                chain.add(current);
                // Edges: a world lock, the server thread (a pseudo-lock the server thread owns), or a
                // Java monitor held by another transaction.
                WorldTxn owner = WorldTxn.waitsFor(current);
                if (owner == null || owner == current) {
                    return;
                }

                Integer cycleStart = index.get(owner);
                if (cycleStart != null) {
                    // The graph is read while everyone runs, so a cycle can be an artifact of edges
                    // read at different times. A real one does not go away: read it again.
                    for (int i = cycleStart; i < chain.size(); i++) {
                        WorldTxn next = i + 1 < chain.size() ? chain.get(i + 1) : chain.get(cycleStart);
                        if (WorldTxn.waitsFor(chain.get(i)) != next) {
                            return;
                        }
                    }

                    WorldTxn victim = null;
                    for (int pass = 0; pass < 2 && victim == null; pass++) {
                        for (int i = cycleStart; i < chain.size(); i++) {
                            WorldTxn member = chain.get(i);
                            if (member.canAbort() && (pass == 1 || member.monitorCount == 0) && (victim == null || member.stamp > victim.stamp)) {
                                victim = member;
                            }
                        }
                    }

                    // With no victim the cycle is either stale (the graph is read without stopping
                    // anyone) or made only of monitors, the server thread and irrevocable events; a
                    // real one shows up in the 10 s long-wait report.
                    if (victim != null) {
                        victim.requestAbort();
                        if (TRACE.get() > 0 && TRACE.getAndDecrement() > 0) {
                            trace(chain.subList(cycleStart, chain.size()), victim);
                        }
                    }

                    return;
                }

                current = owner;
            }
        } finally {
            GRAPH.unlock();
        }
    }

    private static void trace(final List<WorldTxn> cycle, final WorldTxn victim) {
        StringBuilder out = new StringBuilder("Wait cycle, rolling back ").append(victim);
        for (WorldTxn member : cycle) {
            WorldLock lock = member.waitingFor;
            out.append("\n  ").append(member).append(" waits for ")
                .append(lock == WorldTxn.SERVER_THREAD ? "the server thread" : lock != null ? lock : "monitor " + member.waitingMonitor);
            StackTraceElement[] stack = member.thread.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 40); i++) {
                out.append("\n      at ").append(stack[i]);
            }
        }

        WorldTxn.LOGGER.info(out.toString());
    }
}
