import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import sun.misc.Unsafe;

/** Stop between the dispatch check and execute: the callback must not recurse on a worker. */
public class ChunkMoveShutdownCheck {
    static class InlineStopServer extends DedicatedServer {
        boolean stopped; int calls;
        InlineStopServer() { super(null,null,null,null,Optional.empty(),null,null,null,null,null); }
        public boolean isRunning() { return true; }
        public boolean isStopped() { return this.stopped; }
        public void execute(Runnable command) {
            if (++this.calls > 4) throw new AssertionError("Movement rerouted recursively after stop");
            this.stopped=true; command.run();
        }
    }
    static void field(Object object, Class<?> owner, String name, Object value) throws Exception {
        Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Unsafe unsafe=(Unsafe)singleton.get(null);
        var server=(InlineStopServer)unsafe.allocateInstance(InlineStopServer.class);
        var level=(ServerLevel)unsafe.allocateInstance(ServerLevel.class);
        var cache=(ServerChunkCache)unsafe.allocateInstance(ServerChunkCache.class);
        var player=(ServerPlayer)unsafe.allocateInstance(ServerPlayer.class);
        field(level,ServerLevel.class,"server",server);
        field(level,ServerLevel.class,"chunkSource",cache);
        field(cache,ServerChunkCache.class,"level",level);
        field(cache,ServerChunkCache.class,"mainThread",new Thread());
        field(player,Entity.class,"level",level);
        field(player,ServerPlayer.class,"chunkMoveQueued",new AtomicBoolean());
        cache.move(player);
        if(server.calls!=1||player.chunkMoveQueued.get())throw new AssertionError("Invalid stopped movement dispatch");
        cache.move(player);
        if(server.calls!=1)throw new AssertionError("Queued movement after stop");
        System.out.println("PASS chunk movement shutdown dispatch without worker recursion");
        net.minecraft.util.Util.shutdownExecutors();
    }
}
