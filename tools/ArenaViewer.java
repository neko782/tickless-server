import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Launch the real, cached vanilla client offline and capture its rendered frames.
 * Used only in a separate demonstration run, never during CPU/performance trials. */
public class ArenaViewer {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty("arena.captureDir"));
        Files.createDirectories(output);
        AtomicInteger frames = new AtomicInteger();
        AtomicBoolean capturing = new AtomicBoolean();
        AtomicBoolean guiHidden = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().daemon().start(() -> {
            try {
                boolean connectRequested=false;
                while (!Files.exists(output.resolve("capture-start"))) {
                    Minecraft mc=Minecraft.getInstance();
                    if (!connectRequested && mc!=null && mc.isGameLoadFinished() && mc.player==null) {
                        connectRequested=true;
                        mc.execute(() -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                            mc.gui.screen(),mc,
                            net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(System.getProperty("arena.server")),
                            new net.minecraft.client.multiplayer.ServerData("Local arena",System.getProperty("arena.server"),net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null));
                    }
                    Thread.sleep(200);
                }
                long begin = System.nanoTime();
                while (System.nanoTime() - begin < 30_000_000_000L) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && mc.level != null && capturing.compareAndSet(false, true)) {
                        mc.execute(() -> {
                            try {
                                if (guiHidden.compareAndSet(false, true)) mc.gui.hud.toggle();
                                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                                    try {
                                        int frame=frames.getAndIncrement();
                                        image.writeToFile(output.resolve(String.format("frame-%05d.png", frame)));
                                        Files.writeString(output.resolve("frames.jsonl"),
                                            "{\"frame\":"+frame+",\"elapsed_ns\":"+(System.nanoTime()-begin)+"}\n",
                                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                                    } catch (Exception error) { error.printStackTrace(); }
                                    finally { image.close(); capturing.set(false); }
                                });
                            } catch (Throwable error) { error.printStackTrace(); capturing.set(false); }
                        });
                    }
                    Thread.sleep(125);
                }
                System.out.println("ARENA_CAPTURE_COMPLETE frames=" + frames.get());
                Files.writeString(output.resolve("capture-complete"), Integer.toString(frames.get()));
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
            } catch (Throwable error) { error.printStackTrace(); }
        });
        net.minecraft.client.main.Main.main(args);
    }
}
