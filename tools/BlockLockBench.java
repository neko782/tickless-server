// Synthetic lock-only throughput probe. Run with the same JVM and CPU allocation as the server.
// This does not model world access, redstone cascades, or allocations by the scheduler.
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class BlockLockBench {
    static final int WORDS = 64;
    static final int BLOCKS = WORDS * 64;
    static volatile long sink;
    interface LockSet { void lock(int index); void unlock(int index); }
    static class Packed implements LockSet {
        final AtomicLongArray words = new AtomicLongArray(WORDS);
        public void lock(int index) {
            int word = index >>> 6;
            long bit = 1L << index;
            int spins = 0;
            for (;;) {
                long value = words.get(word);
                if ((value & bit) == 0 && words.compareAndSet(word, value, value | bit)) return;
                if (++spins < 64) Thread.onSpinWait();
                else LockSupport.parkNanos(1000);
            }
        }
        public void unlock(int index) { words.getAndUpdate(index >>> 6, v -> v & ~(1L << index)); }
    }

    static class PackedPadded implements LockSet {
        final AtomicLongArray words = new AtomicLongArray(WORDS * 8);
        public void lock(int index) {
            int word = (index & 63) << 3;
            long bit = 1L << (index >>> 6);
            int spins = 0;
            for (;;) {
                long value = words.get(word);
                if ((value & bit) == 0 && words.compareAndSet(word, value, value | bit)) return;
                if (++spins < 64) Thread.onSpinWait();
                else LockSupport.parkNanos(1000);
            }
        }
        public void unlock(int index) { words.getAndUpdate((index & 63) << 3, v -> v & ~(1L << (index >>> 6))); }
    }
    static class Reentrant implements LockSet {
        final ReentrantLock[] locks = new ReentrantLock[BLOCKS];
        Reentrant() { for (int i=0;i<BLOCKS;i++) locks[i]=new ReentrantLock(); }
        public void lock(int index) { locks[index].lock(); }
        public void unlock(int index) { locks[index].unlock(); }
    }
    static long run(LockSet set, int threads, int available, long millis) throws Exception {
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(threads);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong total = new AtomicLong();
        for (int t=0;t<threads;t++) {
            final int id=t;
            Thread worker = new Thread(() -> {
                long count=0; int index=id + 1;
                try { start.await(); while (running.get()) {
                    index ^= index << 13; index ^= index >>> 17; index ^= index << 5;
                    int pos = available == 1 ? 0 : available == threads ? id : (index & (available-1));
                    set.lock(pos);
                    set.unlock(pos);
                    count++;
                }} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                total.addAndGet(count); done.countDown();
            });
            worker.start();
        }
        start.countDown(); Thread.sleep(millis); running.set(false); done.await();
        sink=total.get(); return total.get()*1000/millis;
    }
    public static void main(String[] args) throws Exception {
        for (int threads: new int[]{1, 8, 32}) for (int available: new int[]{1, threads, 4096}) {
            run(new Packed(), threads, available, 300); run(new PackedPadded(), threads, available, 300); run(new Reentrant(), threads, available, 300);
            long packed=run(new Packed(),threads,available,1000);
            long padded=run(new PackedPadded(),threads,available,1000);
            long reentrant=run(new Reentrant(),threads,available,1000);
            System.out.printf("threads=%d blocks=%d packed=%d/s padded=%d/s reentrant=%d/s ratios=%.2f %.2f%n",threads,available,packed,padded,reentrant,(double)packed/reentrant,(double)padded/reentrant);
        }
    }
}
