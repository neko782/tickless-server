import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.HashedStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
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
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
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
 * <p>With {@code -Dbot.arenaDir=PATH}, run the survival combat/build workload controlled
 * by the stress harness's arena files; emit raw per-bot latency samples after a two-second drain.
 * Hurt latency uses the paired victim's feedback in this same JVM and monotonic clock.
 *
 * <p>With {@code -Dbot.command=CMD}, the first bot sends CMD as a chat command once, after
 * {@code -Dbot.commandDelay} seconds of play (default 5). The bot must be an operator.
 */
public final class BotClient {
    private static final boolean RANDOM_ARENA = Boolean.getBoolean("bot.randomArena");
    private static final List<BlockPos> LOOT_POINTS = new ArrayList<>();
    private static final Map<BlockPos, Boolean> RANDOM_SOLIDS = new ConcurrentHashMap<>();
    private static final boolean CHAOS = Boolean.getBoolean("bot.chaos");
    private static final int ATTACK_EVERY = Math.max(1, Integer.getInteger("bot.attackEvery", CHAOS ? 2 : 20));
    private static final int BLOCK_EVERY = Math.max(1, Integer.getInteger("bot.blockEvery", CHAOS ? 2 : 5));
    private static final String ARENA_DIR = System.getProperty("bot.arenaDir");
    private static final Map<Integer, Bot> ARENA_BOTS = new ConcurrentHashMap<>();
    private static volatile int arenaPhase; // 0 waiting, 1 warmup, 2 measured, 3 drain, 4 reported
    private static long arenaStart, arenaEnd;
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
    // Acks that arrived before the server had sent the block state the action produced. A real
    // client then shows its old block until the update arrives.
    private static final LongAdder EARLY_ACKS = new LongAdder();
    // Acks for actions whose block the server never sent at all: the action changed nothing.
    private static final LongAdder NOOP_ACKS = new LongAdder();
    private static final LongAdder EARLY_PLACE_ACKS = new LongAdder();
    // Of those, the ones whose expected state did arrive afterwards: the ack overtook the update.
    // The rest were actions that failed (a placement the server refused also leaves air).
    private static final LongAdder OVERTAKEN_ACKS = new LongAdder();
    private static final List<Long> ACK_NANOS = Collections.synchronizedList(new ArrayList<>());

    public static void main(final String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int count = Integer.parseInt(args[2]);
        int seconds = Integer.parseInt(args[3]);
        String prefix = args.length > 4 ? args[4] : "Bot";
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (CHAOS) {
            // Server bootstrap alone doesn't load 26.3's data-driven default item components.
            // This non-predicting client only needs item identity/count for inventory decoding;
            // all stack validity, damage and transfers are computed by the real server.
            BuiltInRegistries.ITEM.listElements().forEach(holder -> holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY));
        }
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
            GamePacketTypes.CLIENTBOUND_BLOCK_CHANGED_ACK,
            GamePacketTypes.CLIENTBOUND_BLOCK_UPDATE,
            GamePacketTypes.CLIENTBOUND_SECTION_BLOCKS_UPDATE
        ));
        if (ARENA_DIR != null) {
            wanted.add(GamePacketTypes.CLIENTBOUND_LOGIN);
            wanted.add(GamePacketTypes.CLIENTBOUND_HURT_ANIMATION);
            if (CHAOS) {
                wanted.add(GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY);
                wanted.add(GamePacketTypes.CLIENTBOUND_RESPAWN);
                wanted.add(GamePacketTypes.CLIENTBOUND_SET_HEALTH);
                wanted.add(GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_KILL);
                wanted.add(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT);
                wanted.add(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT);
            }
        }
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
        List<Bot> bots = new java.util.concurrent.CopyOnWriteArrayList<>();
        ticker.scheduleAtFixedRate(() -> {
            if (ARENA_DIR != null) {
                Path dir = Path.of(ARENA_DIR);
                if (arenaPhase == 0 && bots.size() == count && bots.stream().allMatch(b -> b.playing && b.positioned)) {
                    if (!Files.exists(dir.resolve("arena-ready"))) {
                        try { Files.createFile(dir.resolve("arena-ready")); }
                        catch (Exception e) { throw new IllegalStateException(e); }
                    }
                    if (Files.exists(dir.resolve("arena-warmup"))) arenaPhase = 1;
                }
                if (arenaPhase == 1 && Files.exists(dir.resolve("arena-measure"))) {
                    arenaStart = System.nanoTime();
                    arenaPhase = 2;
                }
                if (arenaPhase == 2 && Files.exists(dir.resolve("arena-end"))) {
                    arenaEnd = System.nanoTime();
                    arenaPhase = 3;
                }
                if (arenaPhase == 3 && System.nanoTime() - arenaEnd > TimeUnit.SECONDS.toNanos(2)) {
                    for (Bot bot : bots) bot.arenaReport();
                    System.out.println("ARENA_COMPLETE seconds=" + (arenaEnd - arenaStart) / 1e9);
                    arenaPhase = 4;
                }
            }
            for (Bot bot : bots) {
                try {
                    if (bot.connection != null) bot.connection.tick(); // Flush queued handshakes and process disconnects, even before play.
                    bot.tick();
                } catch (Throwable t) {
                    System.out.println("BOT " + bot.name + " tick failed: " + t);
                }
            }
        }, 5L, ARENA_DIR == null ? 50L : 5L, TimeUnit.MILLISECONDS);
        if (ARENA_DIR == null) ticker.scheduleAtFixedRate(BotClient::report, 5L, 5L, TimeUnit.SECONDS);
        for (int i = 0; i < count; i++) {
            Bot bot = new Bot(prefix + i, i);
            bots.add(bot);
            ARENA_BOTS.put(i, bot);
            if (DEBUG) {
                System.out.println("BOT connecting " + bot.name);
            }

            try {
                bot.connect(host, port, group);
            } catch (Exception e) {
                System.out.println("BOT " + bot.name + " failed to connect: " + e);
            }

            Thread.sleep(Integer.getInteger("bot.connectDelay", CHAOS ? 5 : 50));
        }

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
                + ", teleports " + TELEPORTS.sum() + ", acks before block update " + EARLY_ACKS.sum() + " (placing " + EARLY_PLACE_ACKS.sum() + ", update arrived later " + OVERTAKEN_ACKS.sum() + "), acks for unchanged blocks " + NOOP_ACKS.sum() + ", action latency " + latency
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
        private final int index;
        private volatile int entityId = -1;
        private long arenaAttackSent;
        private long arenaNextTick;
        private boolean arenaAttackMeasured;
        private int arenaAttacks, arenaPlaces, arenaBreaks, arenaUpdates;
        private int arenaDeaths, arenaRespawns, arenaMoves, arenaChestOpens, arenaChestClicks, arenaHitTimeouts;
        private boolean arenaDead;
        private long chestOpenSent;
        private int chestId, chestState;
        private int inventoryState, lootTicks, upgrades;
        private int equipArmor = -1;
        private int swapSlot=-1, swapHotbar=-1, chestOpenSequence=-1;
        private String swapSourceId, swapHotbarId;
        private boolean swapSourceConfirmed, swapHotbarConfirmed;
        private Vec3 roamTarget;
        private final net.minecraft.world.item.ItemStack[] inventory = new net.minecraft.world.item.ItemStack[46];
        private List<net.minecraft.world.item.ItemStack> chestItems = List.of();
        private final List<Long> arenaChestLatency = new ArrayList<>();
        private final double homeX, homeZ;
        private final List<Long> arenaHitLatency = new ArrayList<>();
        private final List<Long> arenaAckLatency = new ArrayList<>();
        private final List<Long> arenaUpdateLatency = new ArrayList<>();
        private final Map<Integer, Long> arenaPending = new ConcurrentHashMap<>();
        private final Map<BlockPos, Boolean> arenaExpectedAir = new ConcurrentHashMap<>();
        private final Map<BlockPos, Long> arenaUpdatePending = new ConcurrentHashMap<>();
        private final String name;
        private final Random random;
        private volatile Connection connection;
        private volatile boolean playing;
        private volatile boolean positioned;
        private volatile PositionMoveRotation position = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F);
        private double heading;
        private int ticks;
        private final AtomicInteger sequence = new AtomicInteger();
        private final Map<Integer, Long> pendingActions = new ConcurrentHashMap<>();
        // Per action: the block it targets, and whether the action should leave it air.
        private final Map<Integer, BlockPos> actionTargets = new ConcurrentHashMap<>();
        private final Map<Integer, Boolean> actionMakesAir = new ConcurrentHashMap<>();
        // Last block state the server sent for each position: true if air.
        private final Map<BlockPos, Boolean> knownAir = new ConcurrentHashMap<>();
        // Positions whose ack came first, and the state the action should have left there.
        private final Map<BlockPos, Boolean> awaitingAfterAck = new ConcurrentHashMap<>();
        private BlockPos placed;
        private volatile boolean justTeleported;
        private boolean loaded;
        private final boolean sendsCommand;

        private Bot(final String name, final int index) {
            this.name = name;
            this.index = index;
            this.homeX = (index / 2 % 16) * 5 + (index % 2) * 2 + .5;
            this.homeZ = (index / 2 / 16) * 5 + .5;
            this.arenaNextTick = System.nanoTime() + new Random(index).nextInt(50) * 1_000_000L;
            this.sendsCommand = index == 0 && COMMAND != null;
            this.random = new Random(index);
            java.util.Arrays.fill(this.inventory, net.minecraft.world.item.ItemStack.EMPTY);
            if (RANDOM_ARENA && LOOT_POINTS.isEmpty()) {
                try {
                    for (String line : Files.readAllLines(Path.of(System.getProperty("bot.lootPoints")))) {
                        String[] xyz = line.split(",");
                        BlockPos point = new BlockPos(Integer.parseInt(xyz[0]), 200, Integer.parseInt(xyz[1]));
                        LOOT_POINTS.add(point);
                        if (xyz.length > 2) RANDOM_SOLIDS.put(point, true);
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }
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
                case ClientboundSetHealthPacket health -> {
                    if (CHAOS && health.getHealth() <= 0) this.arenaDied();
                }
                case ClientboundPlayerCombatKillPacket death -> {
                    if (CHAOS && death.playerId() == this.entityId) this.arenaDied();
                }
                case net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket item -> {
                    synchronized (this) {
                        int slot = item.slot();
                        int menu = slot < 9 ? slot + 36 : slot < 36 ? slot : slot < 40 ? 44-slot : slot == 40 ? 45 : -1;
                        if (menu >= 0) this.inventorySet(menu, item.contents());
                    }
                }
                case ClientboundContainerSetContentPacket content -> {
                    if (RANDOM_ARENA && content.containerId() == 0) {
                        synchronized (this) {
                            this.inventoryState = content.stateId();
                            for (int i=0; i<Math.min(46,content.items().size()); i++) this.inventorySet(i, content.items().get(i));
                        }
                    }
                    if (CHAOS && content.containerId() != 0) {
                        synchronized (this) {
                            this.chestId = content.containerId();
                            this.chestState = content.stateId();
                            this.chestItems = new ArrayList<>(content.items());
                            this.lootTicks = 0;
                            if (RANDOM_ARENA) for (int i=27; i<content.items().size() && i-18<46; i++) this.inventory[i-18]=content.items().get(i);
                            if (this.chestOpenSent != 0) {
                                this.arenaChestLatency.add(System.nanoTime() - this.chestOpenSent);
                                this.chestOpenSent = 0;
                            }
                        }
                    }
                }
                case ClientboundContainerSetSlotPacket slot -> {
                    if (RANDOM_ARENA && slot.getContainerId()==0 && slot.getSlot()>=0 && slot.getSlot()<46) {
                        synchronized (this) { this.inventoryState=slot.getStateId(); this.inventorySet(slot.getSlot(),slot.getItem()); }
                    }
                    if (CHAOS && this.chestId != 0 && slot.getContainerId() == this.chestId && slot.getSlot() >= 0) {
                        synchronized (this) {
                            this.chestState = slot.getStateId();
                            if (slot.getSlot() < this.chestItems.size()) this.chestItems.set(slot.getSlot(), slot.getItem());
                            if (RANDOM_ARENA && slot.getSlot()>=27 && slot.getSlot()-18<46) this.inventory[slot.getSlot()-18]=slot.getItem();
                        }
                    }
                }
                case ClientboundLoginPacket login -> this.entityId = login.playerId();
                case ClientboundHurtAnimationPacket hurt -> {
                    if (!RANDOM_ARENA && ARENA_DIR != null && hurt.id() == this.entityId) {
                        Bot attacker = ARENA_BOTS.get(this.index ^ 1);
                        synchronized (attacker) {
                            if (attacker.arenaAttackSent != 0) {
                                if (attacker.arenaAttackMeasured) attacker.arenaHitLatency.add(System.nanoTime() - attacker.arenaAttackSent);
                                attacker.arenaAttackSent = 0;
                            }
                        }
                    }
                }
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
                    if (ARENA_DIR != null) {
                        synchronized (this) {
                            // Minecraft acks are cumulative: one packet may acknowledge several actions.
                            long now = System.nanoTime();
                            if (RANDOM_ARENA && this.chestOpenSequence>=0 && ack.sequence()>=this.chestOpenSequence) {
                                this.chestOpenSequence=-1;
                                if(this.chestId==0) this.chestOpenSent=0;
                            }
                            this.arenaPending.entrySet().removeIf(entry -> {
                                if (entry.getKey() > ack.sequence()) return false;
                                this.arenaAckLatency.add(now - entry.getValue());
                                return true;
                            });
                        }
                    }
                    Long sent = this.pendingActions.remove(ack.sequence());
                    if (sent != null) {
                        ACKS.increment();
                        ACK_NANOS.add(System.nanoTime() - sent);
                    }

                    BlockPos target = this.actionTargets.remove(ack.sequence());
                    Boolean makesAir = this.actionMakesAir.remove(ack.sequence());
                    if (target != null) {
                        Boolean air = this.knownAir.get(target);
                        if (air == null) {
                            NOOP_ACKS.increment();
                        } else if (!makesAir.equals(air)) {
                            EARLY_ACKS.increment();
                            this.awaitingAfterAck.put(target, makesAir);
                            if (!makesAir) {
                                EARLY_PLACE_ACKS.increment();
                            }
                        }
                    }
                }
                case ClientboundBlockUpdatePacket update -> this.blockUpdate(update.getPos(), update.getBlockState().isAir());
                case ClientboundSectionBlocksUpdatePacket update -> update.runUpdates((pos, state) -> this.blockUpdate(pos, state.isAir()));
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

        private void blockUpdate(final BlockPos pos, final boolean air) {
            BlockPos key = pos.immutable();
            if (ARENA_DIR != null) {
                synchronized (this) {
                    // Only the expected transition at this bot's private block counts as feedback.
                    if ((RANDOM_ARENA || this.placed != null && key.equals(this.placed)) && Boolean.valueOf(air).equals(this.arenaExpectedAir.get(key))) {
                        Long sent = this.arenaUpdatePending.remove(key);
                        if (sent != null) {
                            this.arenaUpdates++;
                            this.arenaUpdateLatency.add(System.nanoTime() - sent);
                        }
                    }
                }
            }
            this.knownAir.put(key, air);
            if (RANDOM_ARENA && key.getY()==200) { if(air) RANDOM_SOLIDS.remove(key); else RANDOM_SOLIDS.put(key,true); }
            Boolean expected = this.awaitingAfterAck.get(key);
            if (expected != null && expected == air && this.awaitingAfterAck.remove(key, expected)) {
                OVERTAKEN_ACKS.increment();
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

                        if (ARENA_DIR != null && WANTED.get(id) == GamePacketTypes.CLIENTBOUND_LOGIN) {
                            // Only need the fixed leading entity ID. Full login decoding needs
                            // dynamic dimension registries, which this minimal client omits.
                            Bot.this.entityId = buf.readInt();
                        } else if (CHAOS && WANTED.get(id) == GamePacketTypes.CLIENTBOUND_RESPAWN) {
                            // The dimension data needs dynamic registries; the lifecycle signal does not.
                            synchronized (Bot.this) {
                                Bot.this.arenaDead = false;
                                Bot.this.positioned = false;
                                Bot.this.loaded = false;
                                Bot.this.chestId = 0;
                                if(RANDOM_ARENA) { java.util.Arrays.fill(Bot.this.inventory,net.minecraft.world.item.ItemStack.EMPTY); Bot.this.equipArmor=-1; Bot.this.swapSlot=-1; Bot.this.chestOpenSequence=-1; Bot.this.chestOpenSent=0; }
                                if (arenaPhase >= 2 && arenaPhase <= 3) Bot.this.arenaRespawns++;
                            }
                        } else if (WANTED.containsKey(id)) {
                            buf.resetReaderIndex();
                            Packet<?> packet = clientGame.codec().decode(buf);
                            Bot.this.handle(packet);
                        }
                    } catch (Throwable error) {
                        System.out.println("BOT " + Bot.this.name + " decoder failed: " + error);
                        error.printStackTrace(System.out);
                        throw error;
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

        private synchronized void arenaReport() {
            System.out.println("ARENA_RESULT {\"name\":\"" + this.name + "\",\"attacks\":" + this.arenaAttacks
                + ",\"places\":" + this.arenaPlaces + ",\"breaks\":" + this.arenaBreaks
                + ",\"updates\":" + this.arenaUpdates + ",\"pending_acks\":" + this.arenaPending.size()
                + ",\"hit_ns\":" + this.arenaHitLatency + ",\"ack_ns\":" + this.arenaAckLatency
                + ",\"update_ns\":" + this.arenaUpdateLatency
                + ",\"deaths\":" + this.arenaDeaths + ",\"respawns\":" + this.arenaRespawns
                + ",\"moves\":" + this.arenaMoves + ",\"chest_opens\":" + this.arenaChestOpens
                + ",\"chest_clicks\":" + this.arenaChestClicks + ",\"hit_timeouts\":" + this.arenaHitTimeouts
                + ",\"upgrades\":" + this.upgrades + ",\"chest_ns\":" + this.arenaChestLatency + "}");
        }

        private synchronized void arenaDied() {
            if (this.arenaDead) return;
            this.arenaDead = true;
            if (RANDOM_ARENA) { java.util.Arrays.fill(this.inventory, net.minecraft.world.item.ItemStack.EMPTY); this.equipArmor=-1; this.swapSlot=-1; this.chestOpenSequence=-1; this.chestOpenSent=0; }
            if (arenaPhase >= 2 && arenaPhase <= 3) this.arenaDeaths++;
            this.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        }

        private void chaosTick(final boolean measured) {
            if (this.arenaDead) return;
            // Both fighters follow the same bounded path, keeping each other and their
            // private build spot/chest within reach. No teleport is used for running.
            double phase = this.ticks * .09 + (this.index / 2) * .31;
            Vec3 next = new Vec3(this.homeX + .6 * Math.sin(phase), 200, this.homeZ + .45 * Math.sin(phase * .7));
            float yaw = this.index % 2 == 0 ? -90 : 90;
            this.position = new PositionMoveRotation(next, Vec3.ZERO, yaw, 0);
            this.connection.send(new ServerboundMovePlayerPacket.PosRot(next, yaw, 0, true, false));
            if (measured) this.arenaMoves++;
            if (this.chestId != 0) {
                if (this.ticks % 5 == 0) {
                    for (int slot = 0; slot < Math.min(27, this.chestItems.size()); slot++) {
                        if (!this.chestItems.get(slot).isEmpty()) {
                            this.connection.send(new ServerboundContainerClickPacket(this.chestId, this.chestState,
                                (short)slot, (byte)0, ContainerInput.QUICK_MOVE, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
                            if (measured) this.arenaChestClicks++;
                            break;
                        }
                    }
                }
                if (this.ticks % 20 == 10) {
                    this.connection.send(new ServerboundContainerClosePacket(this.chestId));
                    this.chestId = 0;
                }
            } else if ((this.ticks + this.index) % 20 == 0) {
                BlockPos chest = new BlockPos((this.index / 2 % 16) * 5 + 1, 200, (this.index / 2 / 16) * 5 - 2);
                if (measured) { this.arenaChestOpens++; this.chestOpenSent = System.nanoTime(); }
                // Opening a container is also a sequenced use action; retain its ack latency.
                int seq = this.sequence.incrementAndGet();
                if (measured) this.arenaPending.put(seq, System.nanoTime());
                this.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false), seq));
            }
        }

        private void inventorySet(int slot, net.minecraft.world.item.ItemStack stack) {
            if (RANDOM_ARENA && slot>=5 && slot<=8) {
                String oldId=itemId(slot), newId=BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                if (armorProtection(newId)>armorProtection(oldId)) {
                    this.upgrades++;
                    System.out.println("ARMOR_UPGRADE "+this.name+" "+slot+" "+oldId+" "+newId);
                } else if (!stack.isEmpty() && armorProtection(newId)<armorProtection(oldId)) {
                    System.out.println("ARMOR_DOWNGRADE "+this.name+" "+oldId+" "+newId);
                }
            }
            this.inventory[slot]=stack;
            if (slot==this.swapSlot && itemId(slot).equals(this.swapSourceId)) this.swapSourceConfirmed=true;
            if (slot==this.swapHotbar && itemId(slot).equals(this.swapHotbarId)) this.swapHotbarConfirmed=true;
            if (this.swapSourceConfirmed && this.swapHotbarConfirmed) this.swapSlot=-1;
        }

        private String itemId(int slot) {
            return BuiltInRegistries.ITEM.getKey(this.inventory[slot].getItem()).getPath();
        }

        private int armorSlot(String id) {
            if (id.endsWith("_helmet")) return 5;
            if (id.endsWith("_chestplate")) return 6;
            if (id.endsWith("_leggings")) return 7;
            if (id.endsWith("_boots")) return 8;
            return -1;
        }

        private int armorProtection(String id) {
            int slot=armorSlot(id); if (slot<0) return 0;
            int[] values = id.startsWith("diamond_") ? new int[]{3,8,6,3}
                : id.startsWith("iron_") ? new int[]{2,6,5,2}
                : id.startsWith("chainmail_") ? new int[]{2,5,4,1}
                : id.startsWith("golden_") ? new int[]{2,5,3,1}
                : id.startsWith("leather_") ? new int[]{1,3,2,1} : new int[]{0,0,0,0};
            return values[slot-5];
        }

        private void inventorySwap(int slot, int hotbar) {
            int hotbarSlot=hotbar+36;
            if (itemId(slot).equals(itemId(hotbarSlot))) return;
            this.swapSlot=slot; this.swapHotbar=hotbarSlot;
            this.swapSourceId=itemId(hotbarSlot); this.swapHotbarId=itemId(slot);
            this.swapSourceConfirmed=false; this.swapHotbarConfirmed=false;
            this.connection.send(new ServerboundContainerClickPacket(0, this.inventoryState,
                (short)slot, (byte)hotbar, ContainerInput.SWAP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
        }

        private void randomArenaTick(boolean measured) {
            if (this.arenaDead) return;
            Vec3 pos=this.position.position();
            if (this.chestId!=0) {
                this.lootTicks++;
                if (this.lootTicks%3==0) {
                    for (int i=0;i<Math.min(27,this.chestItems.size());i++) if (!this.chestItems.get(i).isEmpty()) {
                        this.connection.send(new ServerboundContainerClickPacket(this.chestId,this.chestState,
                            (short)i,(byte)0,ContainerInput.QUICK_MOVE,Int2ObjectMaps.emptyMap(),HashedStack.EMPTY));
                        if (measured) this.arenaChestClicks++;
                        break;
                    }
                }
                if (this.lootTicks>=30) {
                    this.connection.send(new ServerboundContainerClosePacket(this.chestId)); this.chestId=0;
                    this.roamTarget=null;
                }
            } else {
                // SWAP clicks never put anything on the cursor. Stage upgrades through hotbar 8.
                if (this.ticks%5==0 && this.swapSlot<0 && this.chestOpenSent==0) {
                    if (this.equipArmor>=0) {
                        if (armorSlot(itemId(44))==this.equipArmor
                            && armorProtection(itemId(44))>armorProtection(itemId(this.equipArmor))) {
                            inventorySwap(this.equipArmor,8);
                        }
                        this.equipArmor=-1;
                    } else {
                        for(int i=9;i<45;i++) {
                            String id=itemId(i); int armor=armorSlot(id);
                            if(armor>=0 && armorProtection(id)>armorProtection(itemId(armor))) {
                                if(i!=44) inventorySwap(i,8);
                                this.equipArmor=armor; break;
                            }
                            if(id.endsWith("_sword") && i!=36 && (!itemId(36).endsWith("_sword")
                                || (id.startsWith("diamond_") && !itemId(36).startsWith("diamond_")))) {
                                inventorySwap(i,0); break;
                            }
                            if(id.equals("slime_block") && i!=37 && !itemId(37).equals("slime_block")) {
                                inventorySwap(i,1); break;
                            }
                        }
                    }
                }
                if(this.roamTarget==null || this.ticks%100==0 || pos.distanceToSqr(this.roamTarget)<.4) {
                    if(this.random.nextInt(3)!=0) {
                        BlockPos loot=LOOT_POINTS.get(this.random.nextInt(LOOT_POINTS.size()));
                        this.roamTarget=Vec3.atBottomCenterOf(loot).add(this.random.nextBoolean()?1.3:-1.3,0,0);
                    } else this.roamTarget=new Vec3(2+this.random.nextDouble()*27,200,2+this.random.nextDouble()*27);
                }
                Vec3 delta=this.roamTarget.subtract(pos); double distance=Math.sqrt(delta.x*delta.x+delta.z*delta.z);
                if(distance>.1) {
                    double speed=Math.min(.19,distance);
                    Vec3 next=new Vec3(pos.x+delta.x/distance*speed,200,pos.z+delta.z/distance*speed);
                    boolean blocked=false;
                    for(double dx:new double[]{-.32,.32}) for(double dz:new double[]{-.32,.32})
                        if(RANDOM_SOLIDS.getOrDefault(BlockPos.containing(next.x+dx,200,next.z+dz),false)) blocked=true;
                    if(blocked) this.roamTarget=null;
                    else {
                        float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
                        this.position=new PositionMoveRotation(next,Vec3.ZERO,yaw,0);
                        this.connection.send(new ServerboundMovePlayerPacket.PosRot(next,yaw,0,true,false));
                        if(measured) this.arenaMoves++;
                    }
                }
                if(this.ticks%20==0 && this.swapSlot<0 && this.equipArmor<0 && this.chestOpenSent==0) for(BlockPos loot:LOOT_POINTS) {
                    if(RANDOM_SOLIDS.getOrDefault(loot,false) && pos.distanceToSqr(Vec3.atCenterOf(loot))<6) {
                        int seq=this.sequence.incrementAndGet();
                        this.chestOpenSent=System.nanoTime(); this.chestOpenSequence=seq;
                        if(measured) {this.arenaChestOpens++; this.arenaPending.put(seq,System.nanoTime());}
                        this.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(loot),Direction.UP,loot,false),seq)); break;
                    }
                }
            }
            if(this.ticks%ATTACK_EVERY==0) {
                List<Bot> targets=new ArrayList<>();
                for(Bot other:ARENA_BOTS.values()) if(other!=this && other.playing && other.positioned && !other.arenaDead
                    && pos.distanceToSqr(other.position.position())<6.25) targets.add(other);
                if(!targets.isEmpty()) {
                    Bot target=targets.get(this.random.nextInt(targets.size()));
                    this.connection.send(new ServerboundSetCarriedItemPacket(0));
                    this.connection.send(new ServerboundAttackPacket(target.entityId));
                    System.out.println("ATTACK_SENT "+this.name+" "+target.entityId);
                    if(measured) this.arenaAttacks++;
                }
            }
            if(this.chestId==0 && this.ticks%BLOCK_EVERY==0 && itemId(37).equals("slime_block")) {
                BlockPos block=BlockPos.containing(pos.x+this.random.nextInt(5)-2,200,pos.z+this.random.nextInt(5)-2);
                if(block.getX()>0 && block.getX()<31 && block.getZ()>0 && block.getZ()<31
                    && !(Math.abs(block.getX()-16)<3 && Math.abs(block.getZ()-16)<3)
                    && !RANDOM_SOLIDS.containsKey(block) && pos.distanceToSqr(Vec3.atCenterOf(block))>1.5) {
                    this.connection.send(new ServerboundSetCarriedItemPacket(1));
                    int seq=this.sequence.incrementAndGet(); long now=System.nanoTime();
                    if(measured) {this.arenaPlaces++;this.arenaPending.put(seq,now);this.arenaUpdatePending.put(block,now);this.arenaExpectedAir.put(block,false);}
                    this.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(block.below()).add(0,.5,0),Direction.UP,block.below(),false),seq));
                }
            }
        }

        private void arenaTick() {
            if (arenaPhase < 1 || arenaPhase > 2) return;
            boolean measured = arenaPhase == 2;
            if (RANDOM_ARENA) { this.randomArenaTick(measured); return; }
            Bot partner = ARENA_BOTS.get(this.index ^ 1);
            if (CHAOS) {
                this.chaosTick(measured);
                if (this.arenaDead) return;
                // Hurt feedback has no request ID. A probe times the first attack until
                // the next accepted hit, including vanilla combat cooldown/invulnerability.
                if (this.arenaAttackSent != 0 && System.nanoTime() - this.arenaAttackSent > 2_000_000_000L) {
                    if (this.arenaAttackMeasured) this.arenaHitTimeouts++;
                    this.arenaAttackSent = 0;
                }
            }
            // Fixed pairs are two blocks apart. Knockback is disabled by the arena setup.
            // Stagger attacks across the 50 ms client phases; never misattribute a late response.
            if ((this.ticks + this.index) % ATTACK_EVERY == 0 && partner.entityId >= 0 && (CHAOS || this.arenaAttackSent == 0)) {
                if (this.arenaAttackSent == 0) {
                    this.arenaAttackSent = System.nanoTime();
                    this.arenaAttackMeasured = measured;
                }
                if (CHAOS) this.connection.send(new ServerboundSetCarriedItemPacket(0));
                if (measured) this.arenaAttacks++;
                this.connection.send(new ServerboundAttackPacket(partner.entityId));
            }
            if (this.ticks % BLOCK_EVERY == 0) {
                if (CHAOS) this.connection.send(new ServerboundSetCarriedItemPacket(1));
                Vec3 pos = CHAOS ? new Vec3(this.homeX, 200, this.homeZ) : this.position.position();
                BlockPos floor = BlockPos.containing(pos.x, pos.y - 0.5, pos.z + 1.5);
                this.placed = floor.above();
                boolean breaking = this.ticks % (BLOCK_EVERY * 2) >= BLOCK_EVERY;
                int seq = this.sequence.incrementAndGet();
                long sent = System.nanoTime();
                if (measured) {
                    this.arenaPending.put(seq, sent);
                    this.arenaUpdatePending.put(this.placed, sent);
                    this.arenaExpectedAir.put(this.placed, breaking);
                    if (breaking) this.arenaBreaks++; else this.arenaPlaces++;
                }
                if (breaking) {
                    this.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, this.placed, Direction.UP, seq));
                } else {
                    this.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false), seq));
                }
            }
        }

        private synchronized void tick() {
            if (!this.playing || !this.positioned) {
                return;
            }

            if (ARENA_DIR != null) {
                long now = System.nanoTime();
                if (now < this.arenaNextTick) return;
                // Skip missed client ticks rather than submitting a catch-up packet burst.
                this.arenaNextTick += (1 + (now - this.arenaNextTick) / 50_000_000L) * 50_000_000L;
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

            if (ARENA_DIR != null) {
                if (arenaPhase < 1 || arenaPhase > 2) return;
                this.arenaTick();
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
                    this.actionTargets.put(seq, floor.above());
                    this.actionMakesAir.put(seq, false);
                    this.connection.send(
                        new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0), Direction.UP, floor, false), seq)
                    );
                    this.placed = floor.above();
                } else {
                    int seq = this.sequence.incrementAndGet();
                    this.pendingActions.put(seq, System.nanoTime());
                    this.actionTargets.put(seq, this.placed);
                    this.actionMakesAir.put(seq, true);
                    this.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, this.placed, Direction.UP, seq));
                    this.placed = null;
                }

                ACTIONS.increment();
            }

            this.connection.send(ServerboundClientTickEndPacket.INSTANCE);
        }
    }
}
