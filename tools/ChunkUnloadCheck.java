import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
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

/** Exercise a real chunk unload/save/reload while a transaction retains its chunk. */
public class ChunkUnloadCheck {
    private static volatile MinecraftServer server;
    private static final BlockPos POS = new BlockPos(16384, 200, -16384);

    public static class Agent {
        public static void premain(String args, Instrumentation instrumentation) {
            instrumentation.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                        java.security.ProtectionDomain protection, byte[] bytes) {
                    if (!name.equals("net/minecraft/server/MinecraftServer")) return null;
                    ClassFile cf = ClassFile.of();
                    return cf.transformClass(cf.parse(bytes), (builder, element) -> {
                        if (element instanceof java.lang.classfile.MethodModel method && method.methodName().equalsString("<init>")) {
                            builder.transformMethod(method, (mb, me) -> {
                                if (me instanceof java.lang.classfile.CodeModel code) {
                                    mb.transformCode(code, (cb, ce) -> {
                                        if (ce instanceof ReturnInstruction) {
                                            cb.invokestatic(ClassDesc.of("java.lang.System"), "getProperties",
                                                MethodTypeDesc.of(ClassDesc.of("java.util.Properties")))
                                                .ldc("tickless.test.server").aload(0)
                                                .invokevirtual(ClassDesc.of("java.util.Properties"), "put",
                                                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object))
                                                .pop();
                                        }
                                        cb.with(ce);
                                    });
                                } else mb.with(me);
                            });
                        } else builder.with(element);
                    });
                }
            });
        }
    }

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
        var holders = net.minecraft.server.level.ChunkMap.class.getDeclaredField("updatingChunkMap");
        holders.setAccessible(true);
        var holder = onServer(() -> {
            try {
                return (net.minecraft.server.level.ChunkHolder)((it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>)holders.get(level.getChunkSource().chunkMap)).get(position.pack());
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        await(() -> holder.getSaveSyncFuture().isDone(), "generation/save futures", 10);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        level.getServer().worldWorkers().execute(() -> {
            try {
                WorldTxn.run(POS.getX(), POS.getY(), POS.getZ(), true, () -> {
                    // A real event can hold a chunk between lookup and its last block write.
                    // Chunk serialization does not acquire this block's world lock.
                    level.worldLocks().lockBlock(POS);
                    captured.countDown();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Event release timed out");
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                    chunk.setBlockState(POS, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                });
                finished.complete(null);
            } catch (Throwable failure) { finished.completeExceptionally(failure); }
        });
        if (!captured.await(5, TimeUnit.SECONDS)) throw new AssertionError("Event did not capture chunk");
        boolean premature;
        try {
            onServer(() -> { level.getChunkSource().updateChunkForced(position, false); return null; });
            // Observe the actual holder moving to pending unload, or the old engine already
            // completing unload. Reflection only reads server-owned lifecycle state on its thread.
            var pending = net.minecraft.server.level.ChunkMap.class.getDeclaredField("pendingUnloads");
            pending.setAccessible(true);
            await(() -> {
                try {
                    return !chunk.loaded() || onServer(() -> {
                        try {
                            return ((it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>)pending.get(level.getChunkSource().chunkMap)).containsKey(position.pack());
                        } catch (IllegalAccessException e) { throw new AssertionError(e); }
                    });
                } catch (Exception e) { throw new RuntimeException(e); }
            }, "unload attempt", 10);
            await(() -> holder.getSaveSyncFuture().isDone(), "unload save-sync future", 10);
            // Exercise the real queue callback as soon as generation has drained. A tick's
            // normal time budget can otherwise leave it queued until after our event resumes.
            var process = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("processUnloads", BooleanSupplier.class);
            process.setAccessible(true);
            onServer(() -> {
                try { process.invoke(level.getChunkSource().chunkMap, (BooleanSupplier)() -> true); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                return null;
            });
            Thread.sleep(100);
            premature = !chunk.loaded();
        } finally {
            release.countDown();
        }
        finished.get(5, TimeUnit.SECONDS);
        await(() -> !chunk.loaded(), "chunk unload after event completion", 10);
        String savedBlock = onServer(() -> level.getBlockState(POS).getBlock().toString());
        System.out.println("ChunkUnloadCheck: unloaded while event paused=" + premature + "; reloaded block=" + savedBlock + " (expected minecraft:diamond_block)");
        if (premature || !savedBlock.equals("Block{minecraft:diamond_block}")) throw new AssertionError("Unload saved before the active event's final writes");
        System.out.println("ChunkUnloadCheck passed: unload waited for the event; the final diamond block survived disk save/reload");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // A child JVM runs the normal server entry point in its own fresh world. The agent
            // captures its instance; no production hooks or altered Minecraft classes are shipped.
            Path dir = Files.createTempDirectory(Path.of("."), "chunk-unload-").toAbsolutePath();
            Path agent = dir.resolve("probe.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Premain-Class", "ChunkUnloadCheck$Agent");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(agent), manifest)) {
                for (String name : new String[] {"ChunkUnloadCheck$Agent.class", "ChunkUnloadCheck$Agent$1.class"}) {
                    jar.putNextEntry(new JarEntry(name));
                    try (var source = ChunkUnloadCheck.class.getResourceAsStream("/" + name)) { source.transferTo(jar); }
                    jar.closeEntry();
                }
            }
            Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
            Files.writeString(dir.resolve("server.properties"), "online-mode=false\nserver-port=0\nlevel-seed=123456789\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-tick-time=30000\npause-when-empty-seconds=-1\n");
            String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx2g", "-XX:+UseG1GC", "-Dtickless.workers=4", "-javaagent:" + agent,
                "-cp", classpath, "ChunkUnloadCheck", "server").directory(dir.toFile()).inheritIO().start();
            if (!child.waitFor(80, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("Chunk unload regression exceeded 80 seconds");
            }
            if (child.exitValue() != 0) throw new AssertionError("Chunk unload regression failed (exit " + child.exitValue() + ")");
            return;
        }
        Thread.ofPlatform().name("Chunk unload probe").start(() -> {
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
