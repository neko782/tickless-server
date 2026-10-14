import java.lang.reflect.Method;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;

/** Membership snapshots must invalidate on edits while ticket availability remains live. */
public class PoiSnapshotCheck {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        PoiSection section = new PoiSection(() -> {});
        Holder<PoiType> type = Holder.direct(new PoiType(Set.of(), 1, 1));
        if (section.getRecords(t -> true, PoiManager.Occupancy.ANY).count() != 0) throw new AssertionError("Nonempty section");
        BlockPos pos = new BlockPos(1, 2, 3);
        PoiRecord record = section.add(pos, type);
        if (section.getRecords(t -> t == type, PoiManager.Occupancy.HAS_SPACE).count() != 1) throw new AssertionError("Add not published");
        var pending = section.getRecords(t -> true, PoiManager.Occupancy.HAS_SPACE);
        Method take = PoiRecord.class.getDeclaredMethod("acquireTicket"); take.setAccessible(true);
        if (!(boolean)take.invoke(record)) throw new AssertionError("Claim failed");
        if (pending.count() != 0) throw new AssertionError("Ticket state captured before consumption");
        if (section.getRecords(t -> true, PoiManager.Occupancy.HAS_SPACE).count() != 0
            || section.getRecords(t -> true, PoiManager.Occupancy.IS_OCCUPIED).count() != 1) throw new AssertionError("Cached ticket availability");
        if ((boolean)take.invoke(record)) throw new AssertionError("Duplicate ticket");
        section.release(pos);
        if (section.getRecords(t -> true, PoiManager.Occupancy.HAS_SPACE).count() != 1) throw new AssertionError("Release not visible");
        section.remove(pos);
        if (section.getRecords(t -> true, PoiManager.Occupancy.ANY).count() != 0) throw new AssertionError("Remove not published");
        section.add(pos, type);
        if (section.getRecords(t -> false, PoiManager.Occupancy.ANY).count() != 0
            || section.getRecords(t -> true, PoiManager.Occupancy.ANY).count() != 1) throw new AssertionError("Readd/filter failed");
        section.add(new BlockPos(2, 2, 3), type);
        var tests = new java.util.concurrent.atomic.AtomicInteger();
        long count = section.getRecords(t -> { tests.incrementAndGet(); return true; }, PoiManager.Occupancy.ANY).count();
        if (count != 2 || tests.get() != 1) throw new AssertionError("Type predicate no longer runs once per group");
        tests.set(0);
        section.getRecords(t -> { tests.incrementAndGet(); return true; }, PoiManager.Occupancy.ANY).findFirst().orElseThrow();
        if (tests.get() != 1) throw new AssertionError("Lazy query evaluated extra groups");
        PoiSection held = new PoiSection(() -> {});
        held.add(pos, type);
        synchronized (held) {
            var read = java.util.concurrent.CompletableFuture.supplyAsync(() -> held.getRecords(t -> true, PoiManager.Occupancy.ANY).count());
            if (read.get(2, java.util.concurrent.TimeUnit.SECONDS) != 1) throw new AssertionError("Held-section query changed membership");
        }
        System.out.println("PoiSnapshotCheck passed: add/remove/readd, filtering, live exclusive tickets and monitor-free snapshot reads");
    }
}
