package fr.cobblepulse.utils.mixin;

import fr.cobblepulse.utils.essentials.Vanish;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** /vanish : un joueur invisible n'est pas envoye aux clients qui n'ont pas le droit de le voir. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow
    @Final
    Entity entity;
    @Shadow
    @Final
    ServerEntity serverEntity;
    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void cpu$vanish(ServerPlayer viewer, CallbackInfo ci) {
        if (entity instanceof ServerPlayer target && Vanish.hiddenFrom(target, viewer)) {
            if (seenBy.remove(viewer.connection)) serverEntity.removePairing(viewer);
            ci.cancel();
        }
    }
}
