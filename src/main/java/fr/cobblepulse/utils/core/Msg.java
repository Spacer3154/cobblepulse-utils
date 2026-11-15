package fr.cobblepulse.utils.core;

import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.Cfg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Messages configurables (messages.toml) avec variables %cle%. */
public final class Msg {
    private Msg() {}

    public static String raw(String key) {
        if (Cfg.messages == null) return key;
        String v = Cfg.messages.str(key, null);
        if (v == null) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Message manquant dans messages.toml : {}", key);
            return key;
        }
        return v;
    }

    public static String format(String key, Object... kv) {
        String s = raw(key);
        if (s.contains("%prefix%")) s = s.replace("%prefix%", raw("general.prefix"));
        if (s.contains("%prefix_")) {
            int i;
            while ((i = s.indexOf("%prefix_")) >= 0) {
                int j = s.indexOf('%', i + 1);
                if (j < 0) break;
                String mod = s.substring(i + 8, j);
                s = s.substring(0, i) + raw("prefixes." + mod) + s.substring(j + 1);
            }
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    public static MutableComponent get(String key, Object... kv) {
        return TextUtil.legacy(format(key, kv));
    }

    /** Message dont une variable est un composant (nom d'objet traduit, Pokemon...). */
    public static MutableComponent withComponent(String key, String var, Component value, Object... kv) {
        return splice(format(key, kv), var, value);
    }

    public static MutableComponent splice(String text, String var, Component value) {
        String token = "%" + var + "%";
        int i = text.indexOf(token);
        if (i < 0) return TextUtil.legacy(text);
        MutableComponent out = Component.empty();
        out.append(TextUtil.legacy(text.substring(0, i)));
        out.append(value);
        out.append(TextUtil.legacy(text.substring(i + token.length())));
        return out;
    }

    public static boolean reachable(ServerPlayer p) {
        return p != null && p.connection != null && !Players.isTest(p.getUUID());
    }

    public static void send(ServerPlayer p, String key, Object... kv) {
        if (reachable(p)) p.sendSystemMessage(get(key, kv));
    }

    public static void send(ServerPlayer p, Component c) {
        if (reachable(p)) p.sendSystemMessage(c);
    }

    public static void send(UUID uuid, String key, Object... kv) {
        MinecraftServer s = CobbleUtils.server();
        if (s == null || uuid == null) return;
        send(s.getPlayerList().getPlayer(uuid), key, kv);
    }

    public static void send(CommandSourceStack src, String key, Object... kv) {
        src.sendSystemMessage(get(key, kv));
    }

    public static void broadcast(String key, Object... kv) {
        broadcast(get(key, kv));
    }

    public static void broadcast(Component c) {
        MinecraftServer s = CobbleUtils.server();
        if (s != null) s.getPlayerList().broadcastSystemMessage(c, false);
    }

    public static void actionBar(ServerPlayer p, String key, Object... kv) {
        if (reachable(p)) p.displayClientMessage(get(key, kv), true);
    }
}
