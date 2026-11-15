package fr.cobblepulse.utils.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.util.Tristate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Appels directs a l'API LuckPerms. Ne jamais referencer cette classe si LuckPerms est absent. */
final class LuckPermsHook {
    private LuckPermsHook() {}

    private static User user(UUID uuid) {
        return LuckPermsProvider.get().getUserManager().getUser(uuid);
    }

    static Boolean check(UUID uuid, String node) {
        User user = user(uuid);
        if (user == null) return null;
        Tristate t = user.getCachedData().getPermissionData().checkPermission(node);
        return t == Tristate.UNDEFINED ? null : t.asBoolean();
    }

    static Map<String, Boolean> permissionMap(UUID uuid) {
        User user = user(uuid);
        if (user == null) return Collections.emptyMap();
        return user.getCachedData().getPermissionData().getPermissionMap();
    }

    static String primaryGroup(UUID uuid) {
        User user = user(uuid);
        return user == null ? null : user.getPrimaryGroup();
    }

    static String prefix(UUID uuid) {
        User user = user(uuid);
        return user == null ? null : user.getCachedData().getMetaData().getPrefix();
    }

    static int groupWeight(String group) {
        Group g = LuckPermsProvider.get().getGroupManager().getGroup(group);
        return g == null ? 0 : g.getWeight().orElse(0);
    }

    static String groupDisplay(String group) {
        Group g = LuckPermsProvider.get().getGroupManager().getGroup(group);
        if (g == null) return group;
        String p = g.getCachedData().getMetaData().getPrefix();
        if (p != null && !p.isBlank()) return p.trim();
        return g.getDisplayName() != null ? g.getDisplayName() : group;
    }

    static List<String> groups() {
        List<Group> all = new ArrayList<>(LuckPermsProvider.get().getGroupManager().getLoadedGroups());
        all.sort(Comparator.comparingInt((Group g) -> g.getWeight().orElse(0)).reversed().thenComparing(Group::getName));
        List<String> out = new ArrayList<>();
        for (Group g : all) out.add(g.getName());
        return out;
    }

    static Integer groupNumeric(String group, String prefix) {
        Group g = LuckPermsProvider.get().getGroupManager().getGroup(group);
        if (g == null) return null;
        String p = prefix.endsWith(".") ? prefix : prefix + ".";
        for (Node n : g.getNodes(NodeType.PERMISSION)) {
            String key = n.getKey();
            if (n.getValue() && key.startsWith(p)) {
                try {
                    return Integer.parseInt(key.substring(p.length()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    static void setGroupNumeric(String group, String prefix, Integer value) {
        LuckPerms lp = LuckPermsProvider.get();
        String p = prefix.endsWith(".") ? prefix : prefix + ".";
        lp.getGroupManager().modifyGroup(group, g -> {
            g.data().clear(n -> n.getKey().startsWith(p) && n.getKey().substring(p.length()).matches("\\d+"));
            if (value != null) g.data().add(Node.builder(p + value).value(true).build());
        });
    }

    static void ensureLoaded(UUID uuid, String name) {
        LuckPermsProvider.get().getUserManager().loadUser(uuid, name).join();
    }

    static void clearUserNodes(UUID uuid, String prefix) {
        var um = LuckPermsProvider.get().getUserManager();
        User u = um.loadUser(uuid).join();
        u.data().clear(n -> n.getKey().startsWith(prefix));
        um.saveUser(u).join();
        u.getCachedData().invalidate();
    }

    static void setUserNode(UUID uuid, String node, boolean value) {
        var um = LuckPermsProvider.get().getUserManager();
        User u = um.loadUser(uuid).join();
        u.data().clear(n -> n.getKey().equals(node));
        u.data().add(Node.builder(node).value(value).build());
        um.saveUser(u).join();
        u.getCachedData().invalidate();
    }
}
