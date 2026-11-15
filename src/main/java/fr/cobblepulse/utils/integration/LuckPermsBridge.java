package fr.cobblepulse.utils.integration;

import fr.cobblepulse.utils.CobbleUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Facade sans dependance dure vers LuckPerms (les classes LP ne sont touchees que si le mod est present). */
public final class LuckPermsBridge {
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("luckperms");
    private static boolean warned;

    private LuckPermsBridge() {}

    public static boolean present() {
        return PRESENT;
    }

    private static void warn(Throwable t) {
        if (!warned) {
            warned = true;
            CobbleUtils.LOG.warn("[CobblePulse-Utils] API LuckPerms indisponible : {}", t.toString());
        }
    }

    /** @return true/false si le noeud est defini (y compris via joker), null sinon ou si LuckPerms est absent. */
    public static Boolean check(ServerPlayer p, String node) {
        if (!PRESENT || p == null) return null;
        try {
            return LuckPermsHook.check(p.getUUID(), node);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    /** Toutes les permissions effectives (heritage resolu) du joueur en ligne. */
    public static Map<String, Boolean> permissionMap(ServerPlayer p) {
        if (!PRESENT || p == null) return Collections.emptyMap();
        try {
            return LuckPermsHook.permissionMap(p.getUUID());
        } catch (Throwable t) {
            warn(t);
            return Collections.emptyMap();
        }
    }

    public static String primaryGroup(UUID uuid) {
        if (!PRESENT) return null;
        try {
            return LuckPermsHook.primaryGroup(uuid);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    public static String prefix(UUID uuid) {
        if (!PRESENT) return null;
        try {
            return LuckPermsHook.prefix(uuid);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    public static int groupWeight(String group) {
        if (!PRESENT || group == null) return 0;
        try {
            return LuckPermsHook.groupWeight(group);
        } catch (Throwable t) {
            warn(t);
            return 0;
        }
    }

    public static String groupDisplay(String group) {
        if (!PRESENT || group == null) return group;
        try {
            return LuckPermsHook.groupDisplay(group);
        } catch (Throwable t) {
            warn(t);
            return group;
        }
    }

    public static List<String> groups() {
        if (!PRESENT) return Collections.emptyList();
        try {
            return LuckPermsHook.groups();
        } catch (Throwable t) {
            warn(t);
            return Collections.emptyList();
        }
    }

    /** Valeur numerique d'un noeud prefixe.N defini directement sur un groupe (null si absent). */
    public static Integer groupNumeric(String group, String prefix) {
        if (!PRESENT) return null;
        try {
            return LuckPermsHook.groupNumeric(group, prefix);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    /** Remplace les noeuds prefixe.* d'un groupe par prefixe.value (value null : suppression). Asynchrone. */
    public static boolean setGroupNumeric(String group, String prefix, Integer value) {
        if (!PRESENT) return false;
        try {
            LuckPermsHook.setGroupNumeric(group, prefix, value);
            return true;
        } catch (Throwable t) {
            warn(t);
            return false;
        }
    }

    /** Charge l'utilisateur LuckPerms (joueurs factices de l'auto-test). */
    public static void ensureLoaded(UUID uuid, String name) {
        if (!PRESENT) return;
        try {
            LuckPermsHook.ensureLoaded(uuid, name);
        } catch (Throwable t) {
            warn(t);
        }
    }

    public static boolean setUserNode(UUID uuid, String node, boolean value) {
        if (!PRESENT) return false;
        try {
            LuckPermsHook.setUserNode(uuid, node, value);
            return true;
        } catch (Throwable t) {
            warn(t);
            return false;
        }
    }
}
