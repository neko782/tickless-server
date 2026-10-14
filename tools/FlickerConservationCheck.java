import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.parallel.WorldTxn;

/** Deterministic terrain demotion and stale item-pickup checks from the flicker workload. */
public class FlickerConservationCheck {
    private static volatile MinecraftServer server;
    private static final BlockPos POS = new BlockPos(16384, 200, -16384);

    private static void await(BooleanSupplier condition, String what, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new AssertionError("Timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private static <T> T onServer(java.util.function.Supplier<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        server.execute(() -> {
            try { result.complete(work.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void probe() throws Exception {
        await(() -> System.getProperties().get("tickless.test.server") instanceof MinecraftServer running && running.isReady(), "server startup", 45);
        server = (MinecraftServer)System.getProperties().get("tickless.test.server");
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        ChunkPos position = new ChunkPos(POS.getX() >> 4, POS.getZ() >> 4);
        LevelChunk chunk = onServer(() -> {
            level.getChunkSource().updateChunkForced(position, true);
            level.setBlock(POS, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            return level.getChunkAt(POS);
        });
        checkPickup(level);
        var holders = net.minecraft.server.level.ChunkMap.class.getDeclaredField("updatingChunkMap");
        holders.setAccessible(true);
        var holder = onServer(() -> {
            try {
                return (net.minecraft.server.level.ChunkHolder)((it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>)holders.get(level.getChunkSource().chunkMap)).get(position.pack());
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        await(() -> holder.getFullChunkFuture().getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK).isSuccess(), "accessible chunk", 10);
        CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        server.worldWorkers().execute(() -> {
            try {
                WorldTxn.run(POS.getX(), POS.getY(), POS.getZ(), false, () -> {
                    captured.countDown();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Collision event release timed out");
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                    var terrain = level.getChunkForCollisions(position.x(), position.z());
                    if (terrain == null || !terrain.getBlockState(POS).is(Blocks.STONE)) {
                        throw new AssertionError("Ticket demotion made solid terrain disappear from an active event");
                    }
                });
                finished.complete(null);
            } catch (Throwable failure) { finished.completeExceptionally(failure); }
        });
        if (!captured.await(5, TimeUnit.SECONDS)) throw new AssertionError("Collision event failed to enter");
        try {
            onServer(() -> { level.getChunkSource().updateChunkForced(position, false); return null; });
            await(() -> !holder.getFullChunkFuture().getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK).isSuccess(), "ticket demotion", 10);
            if (!chunk.loaded()) throw new AssertionError("Unload overtook the admitted event");
        } finally { release.countDown(); }
        finished.get(10, TimeUnit.SECONDS);
        System.out.println("FlickerConservationCheck passed: active collision read retained the stone after ticket demotion");
    }

    private static void checkPickup(ServerLevel level) throws Exception {
        var collectors = onServer(() -> {
            var allays = new net.minecraft.world.entity.animal.allay.Allay[2];
            for (int i = 0; i < allays.length; i++) {
                var allay = net.minecraft.world.entity.EntityTypes.ALLAY.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                allay.setNoAi(true);
                allay.setPos(POS.getX() + 2, POS.getY() + 2, POS.getZ() + 2);
                allay.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
                level.addFreshEntity(allay);
                allays[i] = allay;
            }
            return allays;
        });
        var item = onServer(() -> {
            var dropped = new net.minecraft.world.entity.item.ItemEntity(level, POS.getX() + 2, POS.getY() + 2, POS.getZ() + 2,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
            dropped.setNeverPickUp();
            level.addFreshEntity(dropped);
            return dropped;
        });
        // Both collectors found the same item before either pickup. The second still has its
        // reference after the first event commits removal, as a worker can after waiting for a lock.
        for (var collector : collectors) {
            var done = new CompletableFuture<Void>();
            server.worldWorkers().execute(() -> {
                try {
                    WorldTxn.run(POS.getX(), POS.getY(), POS.getZ(), false, () -> {
                        net.minecraft.world.level.parallel.WorldLocks.lockEntity(collector);
                        net.minecraft.world.entity.npc.InventoryCarrier.pickUpItem(level, collector, collector, item);
                    });
                    done.complete(null);
                } catch (Throwable failure) { done.completeExceptionally(failure); }
            });
            done.get(5, TimeUnit.SECONDS);
        }
        int count = onServer(() -> java.util.Arrays.stream(collectors).mapToInt(allay -> allay.getInventory().getItem(0).getCount()).sum());
        if (!item.isRemoved() || count != 5) throw new AssertionError("Stale pickup copied a removed item: expected 5 diamonds, found " + count);
        System.out.println("FlickerConservationCheck: stale pickup conserved all five diamonds");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // A child JVM runs the normal server entry point in its own fresh world. The agent
            // captures its instance; no production hooks or altered Minecraft classes are shipped.
            Path dir = Files.createTempDirectory(Path.of("."), "flicker-conservation-").toAbsolutePath();
            Path agent = dir.resolve("probe.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Premain-Class", "ChunkUnloadCheck$Agent");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(agent), manifest)) {
                for (String name : new String[] {"ChunkUnloadCheck$Agent.class", "ChunkUnloadCheck$Agent$1.class"}) {
                    jar.putNextEntry(new JarEntry(name));
                    try (var source = FlickerConservationCheck.class.getResourceAsStream("/" + name)) { source.transferTo(jar); }
                    jar.closeEntry();
                }
            }
            Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
            Files.writeString(dir.resolve("server.properties"), "online-mode=false\nserver-port=0\nlevel-seed=123456789\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-tick-time=30000\npause-when-empty-seconds=-1\n");
            String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx2g", "-XX:+UseG1GC", "-Dtickless.workers=4", "-javaagent:" + agent,
                "-cp", classpath, "FlickerConservationCheck", "server").directory(dir.toFile()).inheritIO().start();
            if (!child.waitFor(80, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("Flicker conservation regression exceeded 80 seconds");
            }
            if (child.exitValue() != 0) throw new AssertionError("Flicker conservation regression failed (exit " + child.exitValue() + ")");
            return;
        }
        Thread.ofPlatform().name("Flicker conservation probe").start(() -> {
            int status = 0;
            try { probe(); }
            catch (Throwable failure) { failure.printStackTrace(); status = 1; }
            finally {
                if (server != null) server.halt(true);
            }
            System.exit(status);
        });
        net.minecraft.server.Main.main(new String[] {"--nogui"});
    }
}
