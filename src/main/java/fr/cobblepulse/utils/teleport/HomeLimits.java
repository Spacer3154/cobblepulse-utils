package fr.cobblepulse.utils.teleport;

import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import net.minecraft.server.level.ServerPlayer;

/** Quotas de homes par grade (cobblepulse.home.limit.N) et homes multi-dimensions (cobblepulse.home.multidimension). */
public final class HomeLimits {
    private HomeLimits() {}

    public static int limit(ServerPlayer p) {
        return Perms.numeric(p, Perms.HOME_LIMIT, Cfg.general.teleport.homeDefaultLimit, true);
    }

    public static boolean setHome(ServerPlayer p, String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        if (name.isEmpty()) name = "home";
        if (name.length() > 24) {
            Msg.send(p, "homes.bad_name");
            return false;
        }
        Loc here = Loc.of(p);
        if (!here.isOverworld() && !Perms.perkOrOp(p, Perms.HOME_MULTIDIM)) {
            Msg.send(p, "homes.no_multidim");
            return false;
        }
        PlayerData d = PlayerStore.get(p);
        int max = limit(p);
        if (!d.homes.containsKey(name) && d.homes.size() >= max) {
            Msg.send(p, "homes.limit", "max", max);
            return false;
        }
        boolean existed = d.homes.containsKey(name);
        d.homes.put(name, here);
        PlayerStore.dirty(p.getUUID());
        Msg.send(p, existed ? "homes.updated" : "homes.set", "home", name, "count", d.homes.size(), "max", max);
        return true;
    }
}
