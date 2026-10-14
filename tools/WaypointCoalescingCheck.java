import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.parallel.WorldLock;
import net.minecraft.world.level.parallel.WorldLocks;
import net.minecraft.world.level.parallel.WorldTxn;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;
import sun.misc.Unsafe;

/** Movement must publish after commit without waiting for the manager monitor. */
public class WaypointCoalescingCheck {
    static class TestLevel extends ServerLevel {
        GameRules rules;
        TestLevel() { super(null,null,null,null,null,null,false,0,List.of(),false); }
        public GameRules getGameRules() { return rules; }
    }
    static class TestPlayer extends ServerPlayer {
        TestPlayer() { super(null,null,null,null); }
        public boolean isTransmittingWaypoint() { return false; }
        public int hashCode() { return System.identityHashCode(this); }
        public boolean equals(Object other) { return this == other; }
    }
    static class Source implements WaypointTransmitter {
        int connects,updates,disconnects; boolean broken;
        public boolean isTransmittingWaypoint() { return true; }
        public Waypoint.Icon waypointIcon() { return null; }
        public Optional<WaypointTransmitter.Connection> makeWaypointConnectionWith(ServerPlayer player) {
            return Optional.of(new WaypointTransmitter.Connection() {
                public void connect() { connects++; }
                public void update() { updates++; }
                public void disconnect() { disconnects++; }
                public boolean isBroken() { return broken; }
            });
        }
    }
    static void field(Object target,Class<?> owner,String name,Object value) throws Exception {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe unsafe=(Unsafe)f.get(null);
        var level=(TestLevel)unsafe.allocateInstance(TestLevel.class);level.rules=new GameRules(List.of(GameRules.LOCATOR_BAR));
        var player=(TestPlayer)unsafe.allocateInstance(TestPlayer.class);
        field(player,Entity.class,"level",level);field(player,Entity.class,"worldLock",new WorldLock());
        var manager=new ServerWaypointManager(level);var source=new Source();manager.trackWaypoint(source);manager.addPlayer(player);
        if(source.connects!=1)throw new AssertionError("Missing initial connection");
        WorldTxn.enable(Thread.currentThread(),()->{});
        var entered=new CountDownLatch(1);var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        synchronized(manager) {
            Thread.ofVirtual().start(()->{
                try { WorldTxn.run(0,0,0,false,()->{
                    WorldLocks.lockEntity(player);
                    for(int i=0;i<100;i++){manager.updateWaypoint(source);manager.updatePlayer(player);}
                    entered.countDown();
                    if(source.updates!=0)throw new AssertionError("Refreshed before commit");
                }); } catch(Throwable t){failure.set(t);} finally{done.countDown();}
            });
            if(!entered.await(5,TimeUnit.SECONDS)||!done.await(5,TimeUnit.SECONDS))throw new AssertionError("Movement waited for waypoint monitor");
        }
        if(failure.get()!=null)throw new AssertionError(failure.get());
        manager.flushUpdates();if(source.updates!=1)throw new AssertionError("Source/receiver updates not coalesced");
        manager.flushUpdates();if(source.updates!=1)throw new AssertionError("Replayed clean update");
        // An aborted movement cannot publish a dirty identity from its discarded attempt.
        var attempts=new AtomicInteger();var rolledBack=new CountDownLatch(1);
        var abort=WorldTxn.class.getDeclaredMethod("requestAbort");abort.setAccessible(true);
        Thread.ofVirtual().start(()->{
            try { WorldTxn.run(0,0,0,false,()->{
                if(attempts.getAndIncrement()==0) {
                    manager.updateWaypoint(source);
                    try { if(!Boolean.TRUE.equals(abort.invoke(WorldTxn.current())))throw new AssertionError("Abort not requested"); }
                    catch(ReflectiveOperationException e){throw new AssertionError(e);}
                }
            }); } catch(Throwable t){failure.set(t);} finally{rolledBack.countDown();}
        });
        if(!rolledBack.await(5,TimeUnit.SECONDS)||failure.get()!=null||attempts.get()!=2)throw new AssertionError("Rollback did not replay",failure.get());
        manager.flushUpdates();if(source.updates!=1)throw new AssertionError("Published rolled-back movement");
        manager.updateWaypoint(source);manager.removePlayer(player);manager.flushUpdates();
        if(source.updates!=1||source.disconnects!=1)throw new AssertionError("Resurrected removed receiver");
        manager.addPlayer(player);manager.updateWaypoint(source);manager.untrackWaypoint(source);manager.flushUpdates();
        if(source.updates!=1||source.disconnects!=2)throw new AssertionError("Resurrected removed source");
        manager.trackWaypoint(source);source.broken=true;manager.updateWaypoint(source);manager.flushUpdates();
        if(source.connects!=4)throw new AssertionError("Broken connection not recreated");
        source.broken=false;level.rules.set(GameRules.LOCATOR_BAR,false,null);manager.updatePlayer(player);manager.flushUpdates();
        if(source.updates!=1)throw new AssertionError("Refreshed disabled locator bar");
        WorldTxn.disable();net.minecraft.util.Util.shutdownExecutors();
        System.out.println("PASS committed waypoint coalescing without worker monitor waits, removal and reconnect");
    }
}
