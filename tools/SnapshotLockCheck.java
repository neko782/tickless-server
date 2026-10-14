package net.minecraft.world.level.parallel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;

/** A new main read lock is short; existing/worker ownership must survive the helper. */
public class SnapshotLockCheck {
    static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Snapshot lock blocked unrelated progress");}
        catch(InterruptedException e){throw new AssertionError(e);}
    }
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        WorldTxn.enable(Thread.currentThread(),()->{});
        var source=new WorldLock();var outer=new WorldLock();var main=WorldTxn.current();
        main.lock(outer);
        WorldTxn.withMainSnapshotLock(source,()->{
            if(!source.isHeldBy(main)||!outer.isHeldBy(main))throw new AssertionError("Unprotected snapshot");
            WorldTxn.withMainSnapshotLock(source,()->{});
            if(!source.isHeldBy(main))throw new AssertionError("Nested snapshot released outer ownership");
        });
        boolean fabric=Boolean.getBoolean("tickless.fabric");
        if(source.isHeldBy(main)!=fabric||!outer.isHeldBy(main))throw new AssertionError("Invalid source/outer lifetime");
        if(!fabric) {
            var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
            Thread.ofVirtual().start(()->{try{WorldTxn.run(0,0,0,false,()->WorldTxn.current().lock(source));}catch(Throwable t){failure.set(t);}finally{done.countDown();}});
            await(done);if(failure.get()!=null)throw new AssertionError(failure.get());
            if(!outer.isHeldBy(main))throw new AssertionError("Dropped unrelated outer lock");
        }
        WorldTxn.mainSafePoint();
        main.lock(source);WorldTxn.withMainSnapshotLock(source,()->{});
        if(!source.isHeldBy(main))throw new AssertionError("Released existing main ownership");WorldTxn.mainSafePoint();
        if(!fabric) {
            try{WorldTxn.withMainSnapshotLock(source,()->{throw new IllegalArgumentException("expected");});throw new AssertionError("Suppressed snapshot failure");}
            catch(IllegalArgumentException expected){}
            if(source.isHeldBy(main))throw new AssertionError("Leaked failed snapshot lock");
        }
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread.ofVirtual().start(()->{
            try{WorldTxn.run(0,0,0,false,()->{
                WorldTxn.withMainSnapshotLock(source,()->{});
                if(!source.isHeldBy(WorldTxn.current()))throw new AssertionError("Released worker transaction lock");
                entered.countDown();await(release);
            });}catch(Throwable t){failure.set(t);}finally{done.countDown();}
        });
        await(entered);boolean acquired=source.tryAcquire(main);release.countDown();await(done);
        if(acquired||failure.get()!=null)throw new AssertionError("Worker ownership did not survive snapshot",failure.get());
        WorldTxn.disable();net.minecraft.util.Util.shutdownExecutors();
        System.out.println("PASS short main snapshots, nested/outer ownership, exceptions and retained worker/Fabric locks");
    }
}
