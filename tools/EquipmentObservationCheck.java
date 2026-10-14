import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.MobVisibility;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.parallel.WorldTxn;

/** Scalar targeting reads can race; stack access, replacement and rollback remain owned. */
public class EquipmentObservationCheck {
    static double reference(EntityEquipment equipment, Entity target, double initial) {
        double result=initial;
        for (EquipmentSlot slot:EquipmentSlot.VALUES) {
            ItemStack item=equipment.get(slot);
            MobVisibility visibility=item.get(DataComponents.MOB_VISIBILITY);
            if (visibility!=null) {
                Equippable equippable=item.get(DataComponents.EQUIPPABLE);
                if (equippable!=null && slot==equippable.slot() && visibility.targetingEntityTypes().contains(target.typeHolder())) result*=visibility.visibility();
            }
        }
        return result;
    }
    static float armorReference(EntityEquipment equipment) {
        int total=0,count=0;
        for (EquipmentSlot slot:EquipmentSlotGroup.ARMOR) if (slot.getType()==EquipmentSlot.Type.HUMANOID_ARMOR) {
            total++;
            if (!equipment.get(slot).isEmpty()) count++;
        }
        return total==0?0:(float)count/total;
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Items.DIAMOND_HELMET.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 1).build());
        Entity owner=BrainMemoryCheck.fixture();
        EntityEquipment equipment=new EntityEquipment();
        for (EquipmentSlot slot:EquipmentSlot.VALUES) {
            for (double initial:new double[]{0,0.08,0.8,1,10}) {
                if (Double.doubleToLongBits(reference(equipment,owner,initial))!=Double.doubleToLongBits(equipment.applyVisibility(initial,owner))) throw new AssertionError("Visibility differs");
            }
            if (armorReference(equipment)!=equipment.armorCoverForVisibility()) throw new AssertionError("Armor differs");
            ItemStack item=new ItemStack(Items.DIAMOND_HELMET);
            item.set(DataComponents.MOB_VISIBILITY,new MobVisibility((HolderSet)HolderSet.direct(EntityTypes.COW.builtInRegistryHolder()),0.7f));
            item.set(DataComponents.EQUIPPABLE,Equippable.builder(slot).build());
            equipment.set(slot,item);
        }
        if (reference(equipment,owner,0.8)!=equipment.applyVisibility(0.8,owner)) throw new AssertionError("Multiple modifiers differ");
        // In-place component changes become visible at the next owner refresh. Read snapshots
        // remain safe and stable in between; no mutable component map is shared with observers.
        double oldVisibility = equipment.applyVisibility(0.8,owner);
        equipment.get(EquipmentSlot.HEAD).set(DataComponents.EQUIPPABLE,Equippable.builder(EquipmentSlot.FEET).build());
        equipment.get(EquipmentSlot.CHEST).remove(DataComponents.EQUIPPABLE);
        equipment.get(EquipmentSlot.LEGS).setCount(0);
        if (equipment.applyVisibility(0.8,owner)!=oldVisibility) throw new AssertionError("Observation snapshot changed in place");
        var refresh = EntityEquipment.class.getDeclaredMethod("refreshVisibility");
        refresh.setAccessible(true);
        refresh.invoke(equipment);
        if (reference(equipment,owner,0.8)!=equipment.applyVisibility(0.8,owner) || armorReference(equipment)!=equipment.armorCoverForVisibility()) throw new AssertionError("Live changes differ");
        equipment.clear();
        equipment.worldOwner=owner;
        WorldTxn.enable(Thread.currentThread(),()->{});
        WorldTxn.current().lock(owner.worldLock);
        CountDownLatch observed=new CountDownLatch(1),accessed=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread.ofVirtual().start(()->{
            try {
                WorldTxn.run(0,0,0,false,()->{
                    if (equipment.applyVisibility(0.8,owner)!=0.8 || equipment.armorCoverForVisibility()!=0) throw new AssertionError("Empty observation differs");
                    if (owner.worldLock.isHeldBy(WorldTxn.current())) throw new AssertionError("Observation took owner");
                });
            } catch(Throwable t) {failure.set(t);} finally {observed.countDown();}
        });
        if (!observed.await(2,TimeUnit.SECONDS) || failure.get()!=null) throw new AssertionError("Foreign observation blocked",failure.get());
        CountDownLatch entering=new CountDownLatch(1);
        Thread.ofVirtual().start(()->{
            try {
                WorldTxn.run(0,0,0,false,()->{
                    entering.countDown();
                    equipment.get(EquipmentSlot.HEAD);
                    if (!owner.worldLock.isHeldBy(WorldTxn.current())) throw new AssertionError("Item access lost ownership");
                    equipment.set(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
                });
            } catch(Throwable t) {failure.set(t);} finally {accessed.countDown();}
        });
        if (!entering.await(2,TimeUnit.SECONDS) || accessed.await(100,TimeUnit.MILLISECONDS)) throw new AssertionError("Item access did not wait for owner");
        WorldTxn.mainSafePoint();
        if (!accessed.await(2,TimeUnit.SECONDS) || failure.get()!=null) throw new AssertionError("Owned item access failed",failure.get());
        var abort = WorldTxn.class.getDeclaredMethod("requestAbort");
        abort.setAccessible(true);
        for (boolean bulk : new boolean[]{false, true}) {
            ItemStack original = equipment.get(EquipmentSlot.HEAD);
            double originalVisibility = equipment.applyVisibility(0.8, owner);
            float originalArmor = equipment.armorCoverForVisibility();
            WorldTxn.mainSafePoint();
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            Thread rollback = Thread.ofVirtual().start(() -> {
                try {
                    WorldTxn.run(0, 0, 0, false, () -> {
                        if (attempts.getAndIncrement() == 0) {
                            if (bulk) equipment.clear();
                            else equipment.set(EquipmentSlot.HEAD, ItemStack.EMPTY);
                            try {
                                if (!(boolean)abort.invoke(WorldTxn.current())) throw new AssertionError("Abort refused");
                            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                        } else if (equipment.get(EquipmentSlot.HEAD) != original
                            || equipment.applyVisibility(0.8, owner) != originalVisibility
                            || equipment.armorCoverForVisibility() != originalArmor) {
                            throw new AssertionError("Rollback lost equipment or observation");
                        }
                    });
                } catch (Throwable t) { failure.set(t); }
            });
            rollback.join(2000);
            if (rollback.isAlive() || attempts.get() != 2 || failure.get() != null)
                throw new AssertionError("Equipment rollback failed", failure.get());
        }
        System.out.println("EquipmentObservationCheck passed: exact modifiers, live components, armor, foreign observation, item ownership and rollback");
        System.exit(0);
    }
}
