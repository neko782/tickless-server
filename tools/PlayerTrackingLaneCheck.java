import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerLane;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldTxn;
import net.minecraft.world.level.parallel.WorldWorkers;
import sun.misc.Unsafe;

/** Actual player-lane dispatch: concurrent players, ordered work, coalescing and removal. */
public class PlayerTrackingLaneCheck {
    static class FakeServer extends DedicatedServer {
        WorldWorkers workers; boolean running=true;
        FakeServer() { super(null,null,null,null,Optional.empty(),null,null,null,null,null); }
        public boolean isRunning() { return running; }
        public WorldWorkers worldWorkers() { return workers; }
    }
    static class Tracker extends ServerEntity {
        Runnable action;
        Tracker() { super(null,null,null,false,null); }
        public void sendChanges() { action.run(); }
    }
    static Unsafe unsafe;
    static void set(Object target,Class<?> owner,String name,Object value) throws Exception {
        Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    static ServerPlayer player(PlayerLane lane) throws Exception {
        var p=(ServerPlayer)unsafe.allocateInstance(ServerPlayer.class);
        set(p,Entity.class,"worldLock",new WorldLock());
        set(p,Entity.class,"passengers",ImmutableList.of());
        set(p,Entity.class,"blockPosition",BlockPos.ZERO);
        var connection=(ServerGamePacketListenerImpl)unsafe.allocateInstance(ServerGamePacketListenerImpl.class);
        set(connection,ServerGamePacketListenerImpl.class,"lane",lane);p.connection=connection;
        return p;
    }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Tracking lane stalled"); }
        catch(InterruptedException e) { throw new AssertionError(e); }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);unsafe=(Unsafe)field.get(null);
        WorldTxn.enable(Thread.currentThread(),()->{});
        var server=(FakeServer)unsafe.allocateInstance(FakeServer.class);server.running=true;server.workers=new WorldWorkers(4);
        var a=new PlayerLane(server);var b=new PlayerLane(server);var pa=player(a);var pb=player(b);
        Thread main=Thread.currentThread();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var done=new CountDownLatch(2);var valid=new AtomicBoolean(true);
        for(var pair:new Object[][]{{a,pa},{b,pb}}) {
            var lane=(PlayerLane)pair[0];var p=(ServerPlayer)pair[1];
            if(!lane.submitTracking(p,()->{
                if(Thread.currentThread()==main || !lane.isRunningHere() || !p.worldLock.isHeldBy(WorldTxn.current()))valid.set(false);
                entered.countDown();await(release);
            },done::countDown))throw new AssertionError("Tracking was not queued");
        }
        await(entered);release.countDown();await(done);
        if(!valid.get())throw new AssertionError("Tracking did not run on independent locked worker lanes");
        var started=new CountDownLatch(1);var resume=new CountDownLatch(1);var ordered=new CountDownLatch(1);var calls=new AtomicInteger();
        var tracker=(Tracker)unsafe.allocateInstance(Tracker.class);
        set(tracker,ServerEntity.class,"entity",pa);set(tracker,ServerEntity.class,"playerTrackingQueued",new AtomicBoolean());
        tracker.action=()->{calls.incrementAndGet();started.countDown();await(resume);};
        if(!tracker.sendChangesOnPlayerLane())throw new AssertionError("Tracker fell back to main");
        await(started);
        for(int i=0;i<100;i++)if(!tracker.sendChangesOnPlayerLane())throw new AssertionError("Coalescing fell back to main");
        a.submitTracking(pa,()->{if(calls.get()!=1)valid.set(false);},ordered::countDown);
        resume.countDown();await(ordered);
        if(calls.get()!=1 || !valid.get())throw new AssertionError("Duplicate or unordered tracking work");
        set(pa,Entity.class,"removalReason",Entity.RemovalReason.KILLED);var removed=new CountDownLatch(1);
        a.submitTracking(pa,()->valid.set(false),removed::countDown);await(removed);
        if(!valid.get())throw new AssertionError("Tracked a removed player");
        server.running=false;
        if(tracker.sendChangesOnPlayerLane())throw new AssertionError("Queued tracking after stop");
        server.workers.close();net.minecraft.util.Util.shutdownExecutors();
        System.out.println("PASS parallel locked player tracking, packet order, coalescing and removal");
    }
}
