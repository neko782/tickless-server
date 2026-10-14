import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import sun.misc.Unsafe;

/** A chest lookup can yield while the golem closes or switches its chest. */
public class CopperChestObservationCheck {
    static class LookupLevel extends ServerLevel {
        Runnable duringLookup;
        BlockPos observed;
        LookupLevel() { super(null,null,null,null,null,null,false,0L,List.of(),false); }
        @Override public BlockState getBlockState(BlockPos pos) {
            observed=pos;
            if (duringLookup!=null) { Runnable action=duringLookup; duringLookup=null; action.run(); }
            return Blocks.CHEST.defaultBlockState();
        }
    }
    static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        Field field=owner.getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Unsafe unsafe=(Unsafe)singleton.get(null);
        CopperGolem golem=(CopperGolem)unsafe.allocateInstance(CopperGolem.class);
        LookupLevel level=(LookupLevel)unsafe.allocateInstance(LookupLevel.class);
        set(golem,Entity.class,"type",EntityTypes.COPPER_GOLEM);
        set(golem,Entity.class,"level",level);
        BlockPos first=new BlockPos(1,2,3), second=first.offset(5,0,0);
        if (golem.hasContainerOpen(null,first)) throw new AssertionError("Closed golem reported open");
        golem.setOpenedChestPos(first);
        if (!golem.hasContainerOpen(null,first) || golem.hasContainerOpen(null,second)) throw new AssertionError("Chest position differs");
        level.duringLookup=() -> { try { set(golem,CopperGolem.class,"openedChestPos",null); } catch(Exception e) { throw new AssertionError(e); } };
        if (!golem.hasContainerOpen(null,first) || !first.equals(level.observed)) throw new AssertionError("Close during lookup changed captured observation");
        if (golem.hasContainerOpen(null,first)) throw new AssertionError("Closed position persisted");
        golem.setOpenedChestPos(first);
        level.duringLookup=() -> golem.setOpenedChestPos(second);
        if (!golem.hasContainerOpen(null,first) || !first.equals(level.observed)) throw new AssertionError("Switch mixed two observations");
        if (!golem.hasContainerOpen(null,second)) throw new AssertionError("New chest not observed");
        System.out.println("CopperChestObservationCheck passed: closed/open, close and replacement during world lookup");
        System.exit(0);
    }
}
