import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import sun.misc.Unsafe;

/** Empty, started, finished, stopped and untracked paths must update notification membership. */
public class ActiveNavigationCheck {
    static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field=owner.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Unsafe unsafe=(Unsafe)singleton.get(null);
        ServerLevel level=(ServerLevel)unsafe.allocateInstance(ServerLevel.class);
        Set<Mob> tracked=ConcurrentHashMap.newKeySet();
        Set<Mob> active=ConcurrentHashMap.newKeySet();
        set(level,ServerLevel.class,"navigatingMobs",tracked);
        set(level,ServerLevel.class,"activeNavigatingMobs",active);
        Mob mob=(Mob)BrainMemoryCheck.fixture(); set(mob,Entity.class,"id",1);
        PathNavigation nav=(PathNavigation)unsafe.allocateInstance(GroundPathNavigation.class);
        set(nav,PathNavigation.class,"mob",mob); set(nav,PathNavigation.class,"level",level);
        set(mob, Mob.class, "navigation", nav);
        tracked.add(mob); nav.refreshPathRegistration();
        if (!active.isEmpty()) throw new AssertionError("Empty path registered");
        Path path=new Path(List.of(new Node(0,0,0),new Node(1,0,0)),new BlockPos(1,0,0),true);
        set(nav,PathNavigation.class,"path",path); nav.refreshPathRegistration();
        if (!active.contains(mob)) throw new AssertionError("Started path missing");
        path.advance(); path.advance(); nav.refreshPathRegistration();
        if (!active.isEmpty()) throw new AssertionError("Finished path retained");
        path.setNextNodeIndex(0); nav.refreshPathRegistration(); nav.stop();
        if (!active.isEmpty() || nav.getPath()!=null) throw new AssertionError("Stop retained path");
        set(nav,PathNavigation.class,"path",path); nav.refreshPathRegistration();
        if (nav.moveTo((Path)null,1) || !active.isEmpty()) throw new AssertionError("Failed movement retained path");
        set(nav,PathNavigation.class,"path",path); nav.refreshPathRegistration();
        tracked.remove(mob); nav.refreshPathRegistration();
        if (!active.isEmpty()) throw new AssertionError("Untracked navigation retained");
        // Switching navigation must replace notification membership, without retaining old paths.
        tracked.add(mob);
        set(nav, PathNavigation.class, "path", path); nav.refreshPathRegistration();
        PathNavigation replacement = (PathNavigation)unsafe.allocateInstance(GroundPathNavigation.class);
        set(replacement, PathNavigation.class, "mob", mob); set(replacement, PathNavigation.class, "level", level);
        set(replacement, PathNavigation.class, "path", path);
        set(mob, Mob.class, "navigation", replacement); replacement.refreshPathRegistration();
        nav.stop();
        if (!active.contains(mob)) throw new AssertionError("Obsolete stop removed current navigation");
        replacement.stop();
        if (!active.isEmpty()) throw new AssertionError("Replacement stop retained mob");
        set(mob, Mob.class, "navigation", nav);
        set(nav, PathNavigation.class, "path", path);
        // Reproduce tracking ending after refresh observed membership, before it adds.
        tracked.add(mob);
        var checked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var intercept = new java.util.concurrent.atomic.AtomicBoolean(true);
        Set<Mob> racedTracked = new java.util.AbstractSet<>() {
            public java.util.Iterator<Mob> iterator() { return tracked.iterator(); }
            public int size() { return tracked.size(); }
            public boolean contains(Object entity) {
                boolean present = tracked.contains(entity);
                if (intercept.compareAndSet(true, false)) {
                    checked.countDown();
                    try { if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Release timed out"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
                return present;
            }
        };
        set(level, ServerLevel.class, "navigatingMobs", racedTracked);
        var racing = java.util.concurrent.CompletableFuture.runAsync(nav::refreshPathRegistration);
        if (!checked.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Refresh did not observe tracking");
        tracked.remove(mob); active.remove(mob); release.countDown();
        racing.get(2, java.util.concurrent.TimeUnit.SECONDS);
        if (!active.isEmpty()) throw new AssertionError("Tracking-end race leaked navigation");
        System.out.println("ActiveNavigationCheck passed: empty/start/finish/stop, null movement, tracking lifetime, navigation replacement and concurrent tracking end");
        System.exit(0);
    }
}
