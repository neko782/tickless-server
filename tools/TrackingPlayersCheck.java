import java.util.IdentityHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.IdentitySnapshotSet;

/** Broadcast iteration must survive pairing churn and preserve identity, not equals. */
public class TrackingPlayersCheck {
    static final class Connection {
        public boolean equals(Object other) { return other instanceof Connection; }
        public int hashCode() { return 1; }
    }
    public static void main(String[] args) throws Exception {
        var set = new IdentitySnapshotSet<Connection>();
        var first = new Connection(); var second = new Connection();
        if (!set.add(first) || !set.add(second) || set.add(first) || set.size()!=2) throw new AssertionError("Identity membership changed");
        var iterator = set.iterator();
        set.remove(first);
        int retained=0; while (iterator.hasNext()) { iterator.next(); retained++; }
        if (retained!=2 || set.contains(first) || !set.contains(second)) throw new AssertionError("Snapshot changed after removal");
        set.clear();
        Connection[] values=new Connection[32];
        for(int i=0;i<values.length;i++) values[i]=new Connection();
        var begin=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread[] readers=new Thread[16];
        for(int i=0;i<readers.length;i++) readers[i]=Thread.ofVirtual().start(()->{
            try {
                begin.await();
                for(int n=0;n<10000;n++) {
                    var unique=new IdentityHashMap<Connection,Boolean>();
                    for(var value:set) if(unique.put(value,true)!=null) throw new AssertionError("Duplicate broadcast target");
                }
            } catch(Throwable error) { failure.compareAndSet(null,error); }
        });
        begin.countDown();
        for(int n=0;n<10000;n++) { var value=values[n%values.length];set.add(value);if((n&1)==0)set.remove(value); }
        for(var reader:readers) { reader.join(30000);if(reader.isAlive())throw new AssertionError("Broadcast stalled"); }
        if(failure.get()!=null)throw new AssertionError(failure.get());
        set.clear();for(var value:values)set.add(value);
        if(set.size()!=32)throw new AssertionError("Lost pairing");
        try { var snapshot=set.iterator();snapshot.next();snapshot.remove();throw new AssertionError("Reader changed membership"); }
        catch(UnsupportedOperationException expected) {}
        System.out.println("PASS identity membership and concurrent tracking broadcast snapshots");
    }
}
