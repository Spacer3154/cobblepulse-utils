package fr.cobblepulse.utils.core;

import com.mojang.authlib.GameProfile;
import fr.cobblepulse.utils.CobbleUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Recherche de joueurs (en ligne / hors ligne) et joueurs factices de l'auto-test. */
public final class Players {
    private static final Set<UUID> TEST = ConcurrentHashMap.newKeySet();

    private Players() {}

    public static void registerTest(ServerPlayer p) {
        TEST.add(p.getUUID());
    }

    public static boolean isTest(UUID uuid) {
        return uuid != null && TEST.contains(uuid);
    }

    public static ServerPlayer online(UUID uuid) {
        MinecraftServer s = CobbleUtils.server();
        return s == null || uuid == null ? null : s.getPlayerList().getPlayer(uuid);
    }

    public static ServerPlayer online(String name) {
        MinecraftServer s = CobbleUtils.server();
        return s == null || name == null ? null : s.getPlayerList().getPlayerByName(name);
    }

    /** Nom d'un joueur (en ligne, sinon cache des profils), ou debut de l'UUID. */
    public static String name(UUID uuid) {
        if (uuid == null) return "?";
        ServerPlayer p = online(uuid);
        if (p != null) return p.getGameProfile().getName();
        MinecraftServer s = CobbleUtils.server();
        if (s != null && s.getProfileCache() != null) {
            Optional<GameProfile> gp = s.getProfileCache().get(uuid);
            if (gp.isPresent()) return gp.get().getName();
        }
        return uuid.toString().substring(0, 8);
    }

    /** Profil d'un joueur connu du serveur (en ligne ou deja venu). */
    public static GameProfile profile(String name) {
        ServerPlayer p = online(name);
        if (p != null) return p.getGameProfile();
        MinecraftServer s = CobbleUtils.server();
        if (s != null && s.getProfileCache() != null) {
            return s.getProfileCache().get(name).orElse(null);
        }
        return null;
    }

    /** Sauvegarde immediate des donnees du joueur (inventaire, argent) apres une operation critique. */
    public static void saveNow(ServerPlayer p) {
        MinecraftServer s = CobbleUtils.server();
        if (s == null || p == null || isTest(p.getUUID())) return;
        try {
            ((fr.cobblepulse.utils.mixin.PlayerListAccessor) s.getPlayerList()).cpu$save(p);
        } catch (Throwable t) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Sauvegarde du joueur {} impossible : {}", p.getGameProfile().getName(), t.toString());
        }
    }
}
