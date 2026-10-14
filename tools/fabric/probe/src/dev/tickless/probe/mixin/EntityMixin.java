package dev.tickless.probe.mixin;
import dev.tickless.probe.Probe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.parallel.ParallelSafe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
public abstract class EntityMixin {
    @ParallelSafe @Inject(method="tick", at=@At("HEAD"))
    private void probeTick(CallbackInfo ci) { Probe.entityTick((Entity)(Object)this); }
}
