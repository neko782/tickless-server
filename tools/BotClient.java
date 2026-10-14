import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Headless test players.
 *
 * <p>Each bot logs in offline, walks around, and repeatedly places and breaks a block next to
 * itself. It decodes only the play packets it must answer and the block-change acks it uses to
 * measure how long the server takes to handle a player action.
 *
 * <p>usage: BotClient host port count seconds [name-prefix]
 *
 * <p>With {@code -Dbot.command=CMD}, the first bot sends CMD as a chat command once, after
 * {@code -Dbot.commandDelay} seconds of play (default 5). The bot must be an operator.
 */
public final class BotClient {
    private static final boolean DEBUG = Boolean.getBoolean("bot.debug");
    private static final boolean OBSERVE_ENTITIES = Boolean.getBoolean("bot.entities");
    private static final String COMMAND = System.getProperty("bot.command");
    private static final int COMMAND_DELAY_TICKS = Integer.getInteger("bot.commandDelay", 5) * 20;
    private static ProtocolInfo<ServerGamePacketListener> serverGame;
    private static ProtocolInfo<ClientGamePacketListener> clientGame;
    private static final Map<Integer, PacketType<?>> WANTED = new ConcurrentHashMap<>();
    private static final LongAdder JOINED = new LongAdder();
    private static final LongAdder DISCONNECTED = new LongAdder();
    private static final LongAdder ACTIONS = new LongAdder();
    private static final LongAdder ACKS = new LongAdder();
    private static final LongAdder TELEPORTS = new LongAdder();
    private static final List<Long> ACK_NANOS = Collections.synchronizedList(new ArrayList<>());

    public static void main(final String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int count = Integer.parseInt(args[2]);
        int seconds = Integer.parseInt(args[3]);
        String prefix = args.length > 4 ? args[4] : "Bot";
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        serverGame = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), new GameProtocols.Context() {
            @Override
            public boolean hasInfiniteMaterials() {
                return true;
            }

            @Override
            public boolean canUseCommandBlocks() {
                return false;
            }
        });
        clientGame = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
        List<PacketType<?>> wanted = new ArrayList<>(List.of(
            CommonPacketTypes.CLIENTBOUND_KEEP_ALIVE,
            CommonPacketTypes.CLIENTBOUND_PING,
            CommonPacketTypes.CLIENTBOUND_DISCONNECT,
            GamePacketTypes.CLIENTBOUND_PLAYER_POSITION,
            GamePacketTypes.CLIENTBOUND_CHUNK_BATCH_FINISHED,
            GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK
        ));
        if (OBSERVE_ENTITIES) {
            wanted.add(GamePacketTypes.CLIENTBOUND_ADD_ENTITY);
            wanted.add(GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA);
        }
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (wanted.contains(type)) {
                WANTED.put(id, type);
            }
        });

        EventLoopGroupHolder group = EventLoopGroupHolder.remote(false);
        ScheduledExecutorService ticker = Executors.newScheduledThreadPool(2);
        List<Bot> bots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Bot bot = new Bot(prefix + i, i);
            bots.add(bot);
            if (DEBUG) {
                System.out.println("BOT connecting " + bot.name);
            }

            try {
                bot.connect(host, port, group);
            } catch (Exception e) {
                System.out.println("BOT " + bot.name + " failed to connect: " + e);
            }

            Thread.sleep(50L);
        }

        ticker.scheduleAtFixedRate(() -> {
            for (Bot bot : bots) {
                try {
                    bot.tick();
                } catch (Throwable t) {
                    System.out.println("BOT " + bot.name + " tick failed: " + t);
                }
            }
        }, 50L, 50L, TimeUnit.MILLISECONDS);
        ticker.scheduleAtFixedRate(BotClient::report, 5L, 5L, TimeUnit.SECONDS);
        Thread.sleep(seconds * 1000L);
        ticker.shutdownNow();
        report();
        for (Bot bot : bots) {
            bot.connection.disconnect(net.minecraft.network.chat.Component.literal("done"));
        }

        Thread.sleep(500L);
        System.exit(0);
    }

    private static void report() {
        List<Long> samples;
        synchronized (ACK_NANOS) {
            samples = new ArrayList<>(ACK_NANOS);
            ACK_NANOS.clear();
        }

        Collections.sort(samples);
        String latency = samples.isEmpty()
            ? "n/a"
            : String.format(
                "p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms",
                samples.get(samples.size() / 2) / 1e6,
                samples.get(Math.min(samples.size() - 1, samples.size() * 95 / 100)) / 1e6,
                samples.get(Math.min(samples.size() - 1, samples.size() * 99 / 100)) / 1e6,
                samples.get(samples.size() - 1) / 1e6
            );
        System.out.println(
            "BOTS joined " + JOINED.sum() + ", disconnected " + DISCONNECTED.sum() + ", actions " + ACTIONS.sum() + ", acks " + ACKS.sum()
                + ", teleports " + TELEPORTS.sum() + ", action latency " + latency
        );
    }

    @SuppressWarnings("unchecked")
    private static <T extends PacketListener> T listener(final Class<T> type, final ConnectionProtocol protocol, final Bot bot) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "protocol":
                    return protocol;
                case "flow":
                    return PacketFlow.CLIENTBOUND;
                case "isAcceptingMessages":
                case "shouldHandleMessage":
                    return true;
                case "onDisconnect":
                    bot.disconnected(((DisconnectionDetails)args[0]).reason().getString());
                    return null;
                case "toString":
                    return bot.name + " " + protocol;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    if (args != null && args.length == 1 && args[0] instanceof Packet<?> packet) {
                        try {
                            bot.handle(packet);
                        } catch (Throwable t) {
                            System.out.println("BOT " + bot.name + " failed handling " + packet.getClass().getSimpleName() + ": " + t);
                            t.printStackTrace(System.out);
                        }

                        return null;
                    }

                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }

                    return null;
            }
        };
        return (T)Proxy.newProxyInstance(BotClient.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class Bot {
        private final String name;
        private final Random random;
        private Connection connection;
        private volatile boolean playing;
        private volatile boolean positioned;
        private volatile PositionMoveRotation position = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F);
        private double heading;
        private int ticks;
        private final AtomicInteger sequence = new AtomicInteger();
        private final Map<Integer, Long> pendingActions = new ConcurrentHashMap<>();
        private BlockPos placed;
        private volatile boolean justTeleported;
        private boolean loaded;
        private final boolean sendsCommand;

        private Bot(final String name, final int index) {
            this.name = name;
            this.sendsCommand = index == 0 && COMMAND != null;
            this.random = new Random(index);
            this.heading = this.random.nextDouble() * Math.PI * 2.0;
        }

        private void connect(final String host, final int port, final EventLoopGroupHolder group) {
            this.connection = Connection.connectToServer(new InetSocketAddress(host, port), group, null);
            this.connection.initiateServerboundPlayConnection(host, port, listener(ClientLoginPacketListener.class, ConnectionProtocol.LOGIN, this));
            this.connection.send(new ServerboundHelloPacket(this.name, UUIDUtil.createOfflinePlayerUUID(this.name)));
        }

        private void disconnected(final String reason) {
            DISCONNECTED.increment();
            this.playing = false;
            System.out.println("BOT " + this.name + " disconnected: " + reason);
        }

        private void handle(final Packet<?> packet) {
            if (DEBUG) {
                System.out.println("BOT " + this.name + " <- " + packet.getClass().getSimpleName());
            }

            switch (packet) {
                case ClientboundLoginCompressionPacket compression -> this.connection.setupCompression(compression.getCompressionThreshold(), false);
                case ClientboundLoginFinishedPacket finished -> {
                    this.connection.setupInboundProtocol(
                        ConfigurationProtocols.CLIENTBOUND, listener(ClientConfigurationPacketListener.class, ConnectionProtocol.CONFIGURATION, this)
                    );
                    this.connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                    this.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                }
                case ClientboundLoginDisconnectPacket disconnect -> this.disconnected(disconnect.reason().getString());
                case ClientboundSelectKnownPacks packs -> this.connection.send(new ServerboundSelectKnownPacks(packs.knownPacks()));
                case ClientboundKeepAlivePacket keepAlive -> this.connection.send(new ServerboundKeepAlivePacket(keepAlive.getId()));
                case ClientboundPingPacket ping -> this.connection.send(new ServerboundPongPacket(ping.getId()));
                case ClientboundDisconnectPacket disconnect -> this.disconnected(disconnect.reason().getString());
                case ClientboundFinishConfigurationPacket finish -> {
                    this.installPlayDecoder();
                    this.connection.send(ServerboundFinishConfigurationPacket.INSTANCE);
                    this.connection.setupOutboundProtocol(serverGame);
                    this.playing = true;
                    JOINED.increment();
                }
                case ClientboundPlayerPositionPacket teleport -> {
                    synchronized (this) {
                        this.teleport(teleport);
                    }
                }
                case ClientboundChunkBatchFinishedPacket batch -> this.connection.send(new ServerboundChunkBatchReceivedPacket(20.0F));
                case ClientboundBlockChangedAckPacket ack -> {
                    Long sent = this.pendingActions.remove(ack.sequence());
                    if (sent != null) {
                        ACKS.increment();
                        ACK_NANOS.add(System.nanoTime() - sent);
                    }
                }
                case ClientboundAddEntityPacket entity -> System.out.printf(
                    "ENTITY_ADD %s id=%d uuid=%s type=%s pos=%.4f,%.4f,%.4f rot=%.4f,%.4f%n",
                    this.name, entity.getId(), entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                    entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot()
                );
                case ClientboundSetEntityDataPacket data -> System.out.println("ENTITY_DATA " + this.name + " id=" + data.id() + " values=" + data.packedItems());
                default -> {
                }
            }
        }

        private void teleport(final ClientboundPlayerPositionPacket teleport) {
            {
                {
                    PositionMoveRotation next = PositionMoveRotation.calculateAbsolute(this.position, teleport.change(), teleport.relatives());
                    this.position = next;
                    this.positioned = true;
                    TELEPORTS.increment();
                    if (DEBUG && TELEPORTS.sum() < 12) {
                        System.out.println("BOT " + this.name + " teleport " + teleport.change() + " rel " + teleport.relatives() + " -> " + next.position());
                    }

                    Vec3 pos = next.position();
                    this.connection.send(new ServerboundAcceptTeleportationPacket(teleport.id(), pos.x, pos.y, pos.z, next.yRot(), next.xRot()));
                    // The server accepts one position per client tick; the next tick confirms it.
                    this.justTeleported = true;
                    if (!this.loaded) {
                        // Tells the server the client finished loading terrain, which starts
                        // normal movement validation.
                        this.loaded = true;
                        this.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                    }
                }
            }
        }

        /** Replaces the play decoder with one that decodes only the packets this bot answers. */
        private void installPlayDecoder() {
            Channel channel;
            try {
                Field field = Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                channel = (Channel)field.get(this.connection);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }

            String decoderName = null;
            for (Map.Entry<String, io.netty.channel.ChannelHandler> entry : channel.pipeline()) {
                String type = entry.getValue().getClass().getName();
                if (type.endsWith("PacketDecoder") || type.endsWith("UnconfiguredPipelineHandler$Inbound")) {
                    decoderName = entry.getKey();
                }
            }

            if (decoderName == null) {
                throw new IllegalStateException("No packet decoder in " + channel.pipeline().names());
            }

            if (DEBUG) {
                System.out.println("BOT " + this.name + " pipeline " + channel.pipeline().names() + " replacing " + decoderName + " wanted " + WANTED);
            }

            channel.pipeline().replace(decoderName, "decoder", new ChannelInboundHandlerAdapter() {
                private int seen;

                @Override
                public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                    ByteBuf buf = (ByteBuf)msg;
                    try {
                        buf.markReaderIndex();
                        int id = VarInt.read(buf);
                        if (DEBUG && this.seen++ < 15) {
                            System.out.println("BOT " + Bot.this.name + " id " + id + " " + msg.getClass().getSimpleName());
                        }

                        if (WANTED.containsKey(id)) {
                            buf.resetReaderIndex();
                            Packet<?> packet = clientGame.codec().decode(buf);
                            Bot.this.handle(packet);
                        }
                    } finally {
                        buf.release();
                    }
                }
            });
            // Connection pauses reads while it switches protocols and resumes them only through
            // setupInboundProtocol, which this bot bypasses.
            channel.config().setAutoRead(true);
            channel.read();
        }

        private synchronized void tick() {
            if (!this.playing || !this.positioned) {
                return;
            }

            this.ticks++;
            if (this.sendsCommand && this.ticks == COMMAND_DELAY_TICKS) {
                System.out.println("BOT " + this.name + " sends /" + COMMAND);
                this.connection.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(COMMAND));
            }

            if (this.justTeleported) {
                this.justTeleported = false;
                PositionMoveRotation current = this.position;
                this.connection.send(new ServerboundMovePlayerPacket.PosRot(current.position(), current.yRot(), current.xRot(), true, false));
                this.connection.send(ServerboundClientTickEndPacket.INSTANCE);
                return;
            }

            if (this.random.nextInt(40) == 0) {
                this.heading += this.random.nextGaussian();
            }

            Vec3 pos = this.position.position();
            double nx = pos.x + Math.cos(this.heading) * 0.2;
            double nz = pos.z + Math.sin(this.heading) * 0.2;
            if (Math.abs(nx) > 90.0 || Math.abs(nz) > 90.0) {
                this.heading += Math.PI;
                nx = pos.x;
                nz = pos.z;
            }

            Vec3 next = new Vec3(nx, pos.y, nz);
            this.position = new PositionMoveRotation(next, Vec3.ZERO, this.position.yRot(), this.position.xRot());
            this.connection.send(new ServerboundMovePlayerPacket.Pos(next, true, false));
            if (this.ticks % 10 == 0) {
                if (this.placed == null) {
                    BlockPos floor = BlockPos.containing(next.x + 1.5, next.y - 0.5, next.z);
                    int seq = this.sequence.incrementAndGet();
                    this.pendingActions.put(seq, System.nanoTime());
                    this.connection.send(
                        new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0), Direction.UP, floor, false), seq)
                    );
                    this.placed = floor.above();
                } else {
                    int seq = this.sequence.incrementAndGet();
                    this.pendingActions.put(seq, System.nanoTime());
                    this.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, this.placed, Direction.UP, seq));
                    this.placed = null;
                }

                ACTIONS.increment();
            }

            this.connection.send(ServerboundClientTickEndPacket.INSTANCE);
        }
    }
}
