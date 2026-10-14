package dev.tickless.conflict.mixin;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class ConflictMixin {
    @Inject(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/server/level/ServerLevel;removedVanillaOperation()V"), require=0)
    private void optionalMiss(CallbackInfo callback) {
        throw new AssertionError("The intentionally missing injection unexpectedly ran");
    }
}
