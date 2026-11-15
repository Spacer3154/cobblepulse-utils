package fr.cobblepulse.utils.essentials;

import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.mixin.ChunkMapAccessor;
import fr.cobblepulse.utils.mixin.TrackedEntityInvoker;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /vanish : le membre du staff n'est plus envoye aux autres clients (entite, liste des joueurs, /list, /msg),
 * sauf a ceux qui ont cobblepulse.admin.vanish.see.
 */
public final class Vanish {
    private static final Set<UUID> VANISHED = ConcurrentHashMap.newKeySet();

    private Vanish() {}

    public static boolean isVanished(ServerPlayer p) {
        return p != null && VANISHED.contains(p.getUUID());
    }

    public static boolean canSee(ServerPlayer viewer) {
        return viewer == null || Perms.admin(viewer, Perms.VANISH_SEE) || Perms.admin(viewer, "vanish");
    }

    /** Appele par le mixin de suivi d'entites. */
    public static boolean hiddenFrom(ServerPlayer target, ServerPlayer viewer) {
        return target != viewer && VANISHED.contains(target.getUUID()) && !canSee(viewer);
    }

    public static void toggle(ServerPlayer p) {
        set(p, !isVanished(p));
        Msg.send(p, isVanished(p) ? "staff.vanish_on" : "staff.vanish_off");
    }

    public static void set(ServerPlayer p, boolean on) {
        PlayerData d = PlayerStore.get(p);
        d.vanished = on;
        PlayerStore.dirty(p.getUUID());
        if (on) VANISHED.add(p.getUUID());
        else VANISHED.remove(p.getUUID());
        refresh(p);
    }

    /** Met a jour la liste des joueurs et le suivi de l'entite pour tous les autres joueurs. */
    public static void refresh(ServerPlayer p) {
        boolean on = isVanished(p);
        for (ServerPlayer other : p.getServer().getPlayerList().getPlayers()) {
            if (other == p || other.connection == null) continue;
            if (on && !canSee(other)) other.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(p.getUUID())));
            else other.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p)));
        }
        ServerLevel level = p.serverLevel();
        Object tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).cpu$entityMap().get(p.getId());
        if (tracked != null) ((TrackedEntityInvoker) tracked).cpu$updatePlayers(level.players());
    }

    public static void onJoin(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        if (d.vanished && Perms.admin(p, "vanish")) {
            VANISHED.add(p.getUUID());
            refresh(p);
            Msg.send(p, "staff.vanish_still");
        } else if (d.vanished) {
            d.vanished = false;
            PlayerStore.dirty(p.getUUID());
        }
        // cacher les staffs invisibles au nouvel arrivant
        if (!canSee(p)) {
            for (UUID u : VANISHED) {
                ServerPlayer v = Players.online(u);
                if (v != null && v != p) p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(u)));
            }
        }
    }

    public static void onLeave(ServerPlayer p) {
        VANISHED.remove(p.getUUID());
    }
}
