import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.players.PlayerList;
import sun.misc.Unsafe;

/** Background startup monitors wait for players; the initializing thread stays unblocked. */
public final class FabricStartupCheck {
    public static void main(String[] args) throws Exception {
        System.setProperty("tickless.fabric", "true");
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe)singleton.get(null);
        MinecraftServer server = (MinecraftServer)unsafe.allocateInstance(DedicatedServer.class);
        set(server, "serverThread", Thread.currentThread());
        set(server, "playerListReady", new CompletableFuture<PlayerList>());
        if (server.getPlayerList() != null) throw new AssertionError("Initializing thread must not wait");
        var started = new CountDownLatch(1);
        var result = new CompletableFuture<PlayerList>();
        Thread reader = Thread.ofPlatform().daemon().start(() -> {
            started.countDown();
            try { result.complete(server.getPlayerList()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Monitor did not start");
        try {
            result.get(100, TimeUnit.MILLISECONDS);
            throw new AssertionError("Background monitor observed uninitialized players");
        } catch (java.util.concurrent.TimeoutException expected) {
            // The monitor must remain parked until initialization publishes the list.
        }
        PlayerList players = (PlayerList)unsafe.allocateInstance(DedicatedPlayerList.class);
        server.setPlayerList(players);
        if (result.get(5, TimeUnit.SECONDS) != players || server.getPlayerList() != players)
            throw new AssertionError("Player-list publication lost");
        reader.join(5000);
        if (reader.isAlive()) throw new AssertionError("Monitor remained parked after initialization");
        System.out.println("FabricStartupCheck passed: nonblocking initializer, waiting background monitor, player-list publication");
    }
    private static void set(MinecraftServer server, String name, Object value) throws Exception {
        Field field = MinecraftServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(server, value);
    }
}
