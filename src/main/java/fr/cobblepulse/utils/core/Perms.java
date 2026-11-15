package fr.cobblepulse.utils.core;

import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Permissions LuckPerms du cahier des charges.
 * Sans LuckPerms (ou noeud non defini) : commandes joueurs selon commands.json (« default »),
 * noeuds admin = operateurs (niveau configurable, 2 par defaut), avantages (bypass, VIP) refuses.
 */
public final class Perms {
    public static final String ROOT = "cobblepulse";
    public static final String CMD = "cobblepulse.command.";
    public static final String ADMIN = "cobblepulse.admin.";

    public static final String TP_BYPASS_DELAY = "cobblepulse.teleport.bypassdelay";
    public static final String TP_BYPASS_COOLDOWN = "cobblepulse.teleport.bypasscooldown";
    public static final String BACK_ON_DEATH = "cobblepulse.back.ondeath";
    public static final String HOME_MULTIDIM = "cobblepulse.home.multidimension";
    public static final String HOME_LIMIT = "cobblepulse.home.limit";
    public static final String EC_ROWS = "cobblepulse.ec.rows";
    public static final String EC_PAGES = "cobblepulse.ec.pages";
    public static final String KIT_BYPASS = "cobblepulse.kit.bypasscooldown";
    public static final String GTS_TAX = "cobblepulse.gts.tax";
    public static final String GTS_LIMIT = "cobblepulse.gts.limit";
    public static final String GTS_DURATION = "cobblepulse.gts.duration";
    public static final String STS_LIMIT = "cobblepulse.sts.limit";
    public static final String SHOP_DISCOUNT = "cobblepulse.shop.discount";
    public static final String WT_BYPASS = "cobblepulse.wondertrade.bypasscooldown";
    public static final String ALERTS_RECEIVE = "cobblepulse.alerts.receive";
    public static final String ALERTS_COORDS = "cobblepulse.alerts.hovercoords";
    public static final String BOSS_BYPASS = "cobblepulse.boss.bypasslimit";
    public static final String CHAT_COLOR = "cobblepulse.chat.color";
    public static final String VANISH_SEE = "cobblepulse.admin.vanish.see";
    public static final String MUTE_BYPASS = "cobblepulse.admin.mute.bypass";

    private Perms() {}

    /** Commande joueur : noeud LuckPerms, sinon valeur « default » de commands.json, sinon operateur. */
    public static boolean command(ServerPlayer p, String name) {
        Boolean v = LuckPermsBridge.check(p, CMD + name);
        if (v != null) return v;
        Boolean def = Cfg.commands == null ? null : Cfg.commands.defaultFor(name);
        if (def != null && def) return true;
        return p.hasPermissions(Cfg.general == null ? 2 : Cfg.general.opLevel);
    }

    /** Sous-commande (cobblepulse.command.<parent>.<sub>) : noeud precis, sinon droit de la commande parente. */
    public static boolean sub(ServerPlayer p, String parent, String sub) {
        Boolean v = LuckPermsBridge.check(p, CMD + parent + "." + sub);
        if (v != null) return v;
        return command(p, parent);
    }

    /** Noeud joueur generique (autorise par defaut). */
    public static boolean player(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        return v == null || v;
    }

    /** Avantage (refuse par defaut, operateurs inclus sauf indication contraire). */
    public static boolean perk(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        return v != null && v;
    }

    /** Avantage accorde aussi aux operateurs quand LuckPerms ne dit rien. */
    public static boolean perkOrOp(ServerPlayer p, String node) {
        Boolean v = LuckPermsBridge.check(p, node);
        if (v != null) return v;
        return p.hasPermissions(Cfg.general == null ? 2 : Cfg.general.opLevel);
    }

    public static boolean admin(ServerPlayer p, String node) {
        if (p == null) return true;
        Boolean v = LuckPermsBridge.check(p, node.startsWith(ROOT) ? node : ADMIN + node);
        if (v != null) return v;
        return p.hasPermissions(Cfg.general == null ? 2 : Cfg.general.opLevel);
    }

    public static boolean admin(CommandSourceStack src, String node) {
        return !src.isPlayer() || admin(src.getPlayer(), node);
    }

    /**
     * Valeur numerique d'un noeud prefixe.N (ex. cobblepulse.home.limit.5).
     * @param max true : plus grande valeur accordee, false : plus petite (ex. taxe)
     */
    public static int numeric(ServerPlayer p, String prefix, int def, boolean max) {
        Map<String, Boolean> map = LuckPermsBridge.permissionMap(p);
        String pre = prefix + ".";
        Integer best = null;
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) continue;
            String k = e.getKey();
            if (!k.startsWith(pre)) continue;
            try {
                int n = Integer.parseInt(k.substring(pre.length()));
                if (best == null || (max ? n > best : n < best)) best = n;
            } catch (NumberFormatException ignored) {
            }
        }
        return best == null ? def : best;
    }

    public static Predicate<CommandSourceStack> cmdReq(String name) {
        return src -> !src.isPlayer() || command(src.getPlayer(), name);
    }

    public static Predicate<CommandSourceStack> adminReq(String node) {
        return src -> !src.isPlayer() || admin(src.getPlayer(), node);
    }
}
