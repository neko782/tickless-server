package dev.tickless.conflict.mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
@Mixin(ServerLevel.class)
public abstract class ConflictMixin {
    /** Intentionally conflicts with Tickless by ticking directly, without its transaction setup. */
    @Overwrite public void tickNonPassenger(Entity entity) { entity.tick(); }
}
