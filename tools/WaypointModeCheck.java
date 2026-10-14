import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;
import sun.misc.Unsafe;

/** Reuse absolute-coordinate connections until the selected display type changes. */
public class WaypointModeCheck {
    static class Source extends LivingEntity {
        double range;
        Source(){super(null,null);}
        public HumanoidArm getMainArm(){return HumanoidArm.RIGHT;}
        public double getAttributeValue(Holder<Attribute> attribute){return range;}
    }
    static class Receiver extends ServerPlayer {
        ChunkTrackingView view;
        Receiver(){super(null,null,null,null);}
        public boolean isSpectator(){return false;}
        public double getAttributeValue(Holder<Attribute> attribute){return 10000;}
        public ChunkTrackingView getChunkTrackingView(){return view;}
    }
    static class Listener extends ServerGamePacketListenerImpl {
        List<Packet<?>> packets;
        Listener(){super(null,null,null,null);}
        public void send(Packet<?> packet){packets.add(packet);}
    }
    static void set(Object o,Class<?> owner,String name,Object value)throws Exception{
        Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    static void position(Entity entity,double x)throws Exception{
        set(entity,Entity.class,"position",new Vec3(x,0,0));
        var pos=BlockPos.containing(x,0,0);set(entity,Entity.class,"blockPosition",pos);
        set(entity,Entity.class,"chunkPosition",ChunkPos.containing(pos));
    }
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe u=(Unsafe)f.get(null);
        var source=(Source)u.allocateInstance(Source.class);source.range=10000;
        var receiver=(Receiver)u.allocateInstance(Receiver.class);receiver.view=ChunkTrackingView.of(new ChunkPos(0,0),2);
        var listener=(Listener)u.allocateInstance(Listener.class);listener.packets=new ArrayList<>();receiver.connection=listener;
        set(source,Entity.class,"passengers",ImmutableList.of());set(source,Entity.class,"uuid",UUID.randomUUID());
        position(source,1);position(receiver,0);
        var block=new WaypointTransmitter.EntityBlockConnection(source,new Waypoint.Icon(),receiver);
        position(source,8);
        if(block.isBroken())throw new AssertionError("Recreated unchanged block display after multiple-block movement");
        block.update();
        if(listener.packets.size()!=1||!((Object)((ClientboundTrackedWaypointPacket)listener.packets.getFirst()).operation()).toString().equals("UPDATE"))throw new AssertionError("Did not send coordinate update");
        block.update();if(listener.packets.size()!=1)throw new AssertionError("Sent duplicate stationary update");
        position(source,80);if(!block.isBroken())throw new AssertionError("Kept block display outside visible chunks");
        var chunk=new WaypointTransmitter.EntityChunkConnection(source,new Waypoint.Icon(),receiver);
        position(source,128);if(chunk.isBroken())throw new AssertionError("Recreated unchanged chunk display after multiple-chunk movement");
        chunk.update();if(listener.packets.size()!=2)throw new AssertionError("Missing chunk-coordinate update");
        position(source,8);if(!chunk.isBroken())throw new AssertionError("Missed transition into block display");
        position(source,400);if(!chunk.isBroken())throw new AssertionError("Missed transition into azimuth display");
        var azimuth=new WaypointTransmitter.EntityAzimuthConnection(source,new Waypoint.Icon(),receiver);
        receiver.view=ChunkTrackingView.of(new ChunkPos(0,0),64);
        if(azimuth.isBroken())throw new AssertionError("Recreated far azimuth although mode selection still chooses it");
        position(source,300);if(!azimuth.isBroken())throw new AssertionError("Missed return from far display");
        position(source,8);source.range=4;
        if(!block.isBroken()||!chunk.isBroken()||!azimuth.isBroken())throw new AssertionError("Kept hidden/out-of-range waypoint");
        var removed=(ItemEntity)u.allocateInstance(ItemEntity.class);set(removed,Entity.class,"removalReason",Entity.RemovalReason.DISCARDED);
        var map=(ChunkMap)u.allocateInstance(ChunkMap.class);
        var add=ChunkMap.class.getDeclaredMethod("addEntity",Entity.class);add.setAccessible(true);add.invoke(map,removed);
        Class<?> trackedClass=Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity");
        Object tracked=u.allocateInstance(trackedClass);set(tracked,trackedClass,"entity",removed);
        var update=trackedClass.getDeclaredMethod("updatePlayer",ServerPlayer.class);update.setAccessible(true);update.invoke(tracked,receiver);
        System.out.println("PASS waypoint coordinate reuse, display transitions, visibility/range and removed-item registration");
        net.minecraft.util.Util.shutdownExecutors();
    }
}
