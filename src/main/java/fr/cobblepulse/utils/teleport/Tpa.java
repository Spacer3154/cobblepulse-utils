package fr.cobblepulse.utils.teleport;

import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Demandes /tpa et /tpahere (expiration, /tptoggle, /ignore). */
public final class Tpa {
    record Request(UUID from, UUID to, boolean here, long expires) {}

    /** cible -> (demandeur -> demande), ordre d'arrivee. */
    private static final Map<UUID, LinkedHashMap<UUID, Request>> REQUESTS = new ConcurrentHashMap<>();

    private Tpa() {}

    public static int request(ServerPlayer from, ServerPlayer to, boolean here) {
        if (from == to) {
            Msg.send(from, "tpa.self");
            return 0;
        }
        PlayerData td = PlayerStore.get(to);
        if (td.tpToggle) {
            Msg.send(from, "tpa.disabled", "player", to.getGameProfile().getName());
            return 0;
        }
        if (td.ignored.contains(from.getUUID())) {
            Msg.send(from, "tpa.ignored", "player", to.getGameProfile().getName());
            return 0;
        }
        if (!Teleports.checkCooldown(from, "tpa", Cfg.general.teleport.tpaCooldownSeconds)) return 0;
        long exp = System.currentTimeMillis() + Cfg.general.teleport.tpaTimeoutSeconds * 1000L;
        REQUESTS.computeIfAbsent(to.getUUID(), k -> new LinkedHashMap<>()).put(from.getUUID(), new Request(from.getUUID(), to.getUUID(), here, exp));
        String fromName = from.getGameProfile().getName();
        Msg.send(from, here ? "tpa.sent_here" : "tpa.sent", "player", to.getGameProfile().getName(), "seconds", Cfg.general.teleport.tpaTimeoutSeconds);
        Msg.send(to, here ? "tpa.received_here" : "tpa.received", "player", fromName, "seconds", Cfg.general.teleport.tpaTimeoutSeconds);
        Component buttons = Component.empty()
                .append(TextUtil.button(Msg.format("tpa.button_accept"), "/tpaccept " + fromName, Msg.format("tpa.button_accept_hover")))
                .append(Component.literal("  "))
                .append(TextUtil.button(Msg.format("tpa.button_deny"), "/tpdeny " + fromName, Msg.format("tpa.button_deny_hover")));
        Msg.send(to, buttons);
        return 1;
    }

    private static Request take(ServerPlayer target, String fromName) {
        LinkedHashMap<UUID, Request> map = REQUESTS.get(target.getUUID());
        if (map == null) return null;
        long now = System.currentTimeMillis();
        map.values().removeIf(r -> r.expires < now);
        if (map.isEmpty()) return null;
        Request r = null;
        if (fromName != null && !fromName.isBlank()) {
            for (Request x : map.values()) if (Players.name(x.from).equalsIgnoreCase(fromName)) r = x;
        } else {
            List<Request> all = new ArrayList<>(map.values());
            r = all.get(all.size() - 1);
        }
        if (r != null) map.remove(r.from);
        return r;
    }

    public static int accept(ServerPlayer target, String fromName) {
        Request r = take(target, fromName);
        if (r == null) {
            Msg.send(target, "tpa.none");
            return 0;
        }
        ServerPlayer from = Players.online(r.from);
        if (from == null) {
            Msg.send(target, "tpa.offline");
            return 0;
        }
        // monde d'arrivee : celui de la cible (/tpa) ou du demandeur (/tpahere)
        String dest = Worlds.id(r.here ? from.serverLevel() : target.serverLevel());
        if (!Worlds.tpaInto(dest)) {
            Msg.send(target, "tpa.world_denied", "world", Worlds.plainName(dest));
            Msg.send(from, "tpa.world_denied", "world", Worlds.plainName(dest));
            return 0;
        }
        Msg.send(target, "tpa.accepted", "player", from.getGameProfile().getName());
        Msg.send(from, "tpa.accepted_by", "player", target.getGameProfile().getName());
        if (r.here) {
            Teleports.teleport(target, Loc.of(from), from.getGameProfile().getName(), null);
        } else {
            Teleports.teleport(from, Loc.of(target), target.getGameProfile().getName(), null);
        }
        return 1;
    }

    public static int deny(ServerPlayer target, String fromName) {
        Request r = take(target, fromName);
        if (r == null) {
            Msg.send(target, "tpa.none");
            return 0;
        }
        Msg.send(target, "tpa.denied");
        Msg.send(r.from, "tpa.denied_by", "player", target.getGameProfile().getName());
        return 1;
    }

    public static List<String> pendingNames(ServerPlayer target) {
        List<String> out = new ArrayList<>();
        LinkedHashMap<UUID, Request> map = REQUESTS.get(target.getUUID());
        if (map != null) for (Request r : map.values()) out.add(Players.name(r.from));
        return out;
    }

    public static void clear(UUID uuid) {
        REQUESTS.remove(uuid);
    }
}
