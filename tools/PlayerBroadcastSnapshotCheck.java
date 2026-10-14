import java.lang.reflect.Field;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import sun.misc.Unsafe;

/** The actual PlayerList constructor must publish safe broadcasts during respawn churn. */
public class PlayerBroadcastSnapshotCheck {
    static class Listener extends ServerGamePacketListenerImpl {
        AtomicInteger calls; Runnable action;
        Listener(){super(null,null,null,null);}
        public void send(Packet<?> packet){calls.incrementAndGet();if(action!=null)action.run();}
    }
    static void set(Object target,Class<?> owner,String name,Object value)throws Exception{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Unsafe unsafe=(Unsafe)singleton.get(null);
        var players=new PlayerList(null,null,null,null) {};
        ServerPlayer first=(ServerPlayer)unsafe.allocateInstance(ServerPlayer.class);
        ServerPlayer second=(ServerPlayer)unsafe.allocateInstance(ServerPlayer.class);
        first.setId(1);second.setId(2);
        var level=(ServerLevel)unsafe.allocateInstance(ServerLevel.class);set(level,Level.class,"dimension",Level.OVERWORLD);
        set(first,Entity.class,"level",level);set(second,Entity.class,"level",level);
        set(first,Entity.class,"position",Vec3.ZERO);set(second,Entity.class,"position",Vec3.ZERO);
        set(first,Player.class,"gameProfile",new GameProfile(UUID.randomUUID(),"first"));
        set(second,Player.class,"gameProfile",new GameProfile(UUID.randomUUID(),"second"));
        var a=(Listener)unsafe.allocateInstance(Listener.class);var b=(Listener)unsafe.allocateInstance(Listener.class);
        a.calls=new AtomicInteger();b.calls=new AtomicInteger();first.connection=a;second.connection=b;
        var packet=new ClientboundBlockChangedAckPacket(1);
        players.getPlayers().add(first);players.getPlayers().add(second);
        var retained=players.getPlayers().iterator();players.getPlayers().remove(first);
        int count=0;while(retained.hasNext()){retained.next();count++;}
        if(count!=2)throw new AssertionError("Membership snapshot changed during removal");
        var begin=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        Thread[] readers=new Thread[8];
        for(int i=0;i<readers.length;i++)readers[i]=Thread.ofVirtual().start(()->{
            try{begin.await();for(int n=0;n<30000;n++){
                for(var player:players.getPlayers())if(player==null)throw new AssertionError("Null broadcast player");
                players.broadcast(null,0,0,0,64,Level.OVERWORLD,packet);
                for(String name:players.getPlayerNamesArray())if(name==null)throw new AssertionError("Incomplete names snapshot");
                if(players.getPlayerByName("SECOND")!=second)throw new AssertionError("Lost name lookup");
            }}
            catch(Throwable error){failure.compareAndSet(null,error);}
        });
        begin.countDown();for(int n=0;n<30000;n++){players.getPlayers().add(first);players.getPlayers().remove(first);}
        for(var reader:readers){reader.join(30000);if(reader.isAlive())throw new AssertionError("Broadcast stalled");}
        if(failure.get()!=null)throw new AssertionError("Broadcast raced with respawn membership",failure.get());
        if(players.getPlayers().size()!=1||players.getPlayers().getFirst()!=second)throw new AssertionError("Lost player membership");
        players.getPlayers().clear();players.getPlayers().add(first);players.getPlayers().add(second);
        a.calls.set(0);b.calls.set(0);a.action=()->players.getPlayers().remove(first);
        players.broadcast(null,0,0,0,64,Level.OVERWORLD,packet);
        if(a.calls.get()!=1||b.calls.get()!=1)throw new AssertionError("Broadcast skipped a snapshot recipient after removal");
        System.out.println("PASS actual PlayerList broadcast snapshots during respawn churn");
        net.minecraft.util.Util.shutdownExecutors();
    }
}
