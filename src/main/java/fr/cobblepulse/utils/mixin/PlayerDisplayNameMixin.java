package fr.cobblepulse.utils.mixin;

import fr.cobblepulse.utils.essentials.ChatFeatures;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** /nick : le surnom remplace le pseudo dans le nom affiche (chat, placeholders %player:displayname%). */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void cpu$nick(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide()) return;
        Component nick = ChatFeatures.nickOf(self.getUUID());
        if (nick != null) {
            MutableComponent c = PlayerTeam.formatNameForTeam(self.getTeam(), nick.copy());
            cir.setReturnValue(c);
        }
    }
}
