import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;

/** Concurrent registration, updates, removal and externally retained transmitter snapshots. */
public class WaypointConcurrencyCheck {
    public static void main(String[] args) throws Exception {
        var manager = new ServerWaypointManager();
        // Identity-distinct transmitters: a record with no components would compare equal.
        var transmitters = new WaypointTransmitter[32];
        for (int i = 0; i < transmitters.length; i++) {
            transmitters[i] = new WaypointTransmitter() {
                public boolean isTransmittingWaypoint() { return true; }
                public Optional<WaypointTransmitter.Connection> makeWaypointConnectionWith(ServerPlayer player) { return Optional.empty(); }
                public Waypoint.Icon waypointIcon() { return null; }
            };
            manager.trackWaypoint(transmitters[i]);
        }
        var snapshot = manager.transmitters();
        manager.untrackWaypoint(transmitters[0]);
        if (snapshot.size() != 32 || !snapshot.contains(transmitters[0])) throw new AssertionError("Retained snapshot changed");
        try { snapshot.clear(); throw new AssertionError("Mutable snapshot"); }
        catch (UnsupportedOperationException expected) {}
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread[] workers = new Thread[32];
        for (int i = 0; i < workers.length; i++) {
            final var transmitter = transmitters[i];
            workers[i] = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 2000; n++) {
                        manager.trackWaypoint(transmitter);
                        manager.updateWaypoint(transmitter);
                        manager.remakeConnections(transmitter);
                        manager.transmitters().forEach(value -> { if (value == null) throw new AssertionError(); });
                        manager.untrackWaypoint(transmitter);
                    }
                    manager.trackWaypoint(transmitter);
                } catch (Throwable t) { failure.compareAndSet(null, t); }
            });
        }
        start.countDown();
        for (Thread worker : workers) { worker.join(30_000); if (worker.isAlive()) throw new AssertionError("Stalled worker"); }
        if (failure.get() != null) throw new AssertionError("Concurrent waypoint operation", failure.get());
        if (manager.transmitters().size() != 32) throw new AssertionError("Lost registration");
        manager.breakAllConnections();
        for (var transmitter : transmitters) manager.untrackWaypoint(transmitter);
        if (!manager.transmitters().isEmpty()) throw new AssertionError("Lost removal");
        System.out.println("PASS concurrent waypoint registry and immutable snapshots");
    }
}
