package fr.cobblepulse.utils.mixin;

import fr.cobblepulse.utils.essentials.ChatFeatures;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** /ignore (chat public) et surnom dans la liste des joueurs. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void cpu$ignore(OutgoingChatMessage message, boolean filtered, ChatType.Bound bound, CallbackInfo ci) {
        if (message instanceof OutgoingChatMessage.Player pm
                && ChatFeatures.ignores((ServerPlayer) (Object) this, pm.message().sender())) {
            ci.cancel();
        }
    }

    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void cpu$tabNick(CallbackInfoReturnable<Component> cir) {
        Component nick = ChatFeatures.nickOf(((ServerPlayer) (Object) this).getUUID());
        if (nick != null) cir.setReturnValue(nick);
    }
}
