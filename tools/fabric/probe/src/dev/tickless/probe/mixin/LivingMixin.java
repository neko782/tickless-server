package dev.tickless.probe.mixin;
import dev.tickless.probe.Probe;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.parallel.ParallelSafe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LivingEntity.class)
public abstract class LivingMixin {
    @ParallelSafe @Inject(method="hurtServer", at=@At("HEAD"))
    private void probeHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) { Probe.hurt((LivingEntity)(Object)this); }
    @ParallelSafe @Inject(method="die", at=@At("HEAD"))
    private void probeDeath(DamageSource source, CallbackInfo ci) { Probe.die((LivingEntity)(Object)this); }
}
