package fr.cobblepulse.utils.data;

import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.Json;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.storage.Db;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache des donnees joueurs. Chargement synchrone a la connexion (base locale ou MySQL),
 * sauvegardes asynchrones (instantane JSON pris sur le thread principal), vidage periodique et a l'arret.
 */
public final class PlayerStore {
    private static final Map<UUID, PlayerData> CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();

    private PlayerStore() {}

    private static Db.Source db() {
        return Db.of("player_data");
    }

    public static PlayerData get(ServerPlayer p) {
        PlayerData d = get(p.getUUID());
        String n = p.getGameProfile().getName();
        if (!n.equals(d.name)) {
            d.name = n;
            dirty(p.getUUID());
        }
        return d;
    }

    public static PlayerData get(UUID uuid) {
        PlayerData d = CACHE.get(uuid);
        if (d != null) return d;
        d = load(uuid);
        CACHE.put(uuid, d);
        return d;
    }

    /** Donnees d'un joueur hors ligne, sans le garder en cache s'il n'y etait pas. */
    public static PlayerData peek(UUID uuid) {
        PlayerData d = CACHE.get(uuid);
        return d != null ? d : load(uuid);
    }

    private static PlayerData load(UUID uuid) {
        PlayerData d = null;
        try {
            String json = db().run(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT data FROM " + db().table("players") + " WHERE uuid=?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
            if (json != null) d = Json.COMPACT.fromJson(json, PlayerData.class);
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Chargement des donnees de {} impossible : {}", uuid, e.toString());
        }
        if (d == null) d = new PlayerData();
        d.normalize();
        return d;
    }

    public static UUID findByName(String name) {
        for (Map.Entry<UUID, PlayerData> e : CACHE.entrySet()) if (name.equalsIgnoreCase(e.getValue().name)) return e.getKey();
        try {
            return db().run(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + db().table("players") + " WHERE LOWER(name)=LOWER(?)")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? UUID.fromString(rs.getString(1)) : null;
                    }
                }
            });
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> knownNames() {
        List<String> out = new ArrayList<>();
        try {
            db().run(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT name FROM " + db().table("players") + " ORDER BY updated DESC LIMIT 200");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) if (rs.getString(1) != null) out.add(rs.getString(1));
                }
                return null;
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Modifie les donnees d'un joueur, en ligne ou non, et les enregistre. */
    public static void update(UUID uuid, java.util.function.Consumer<PlayerData> fn) {
        PlayerData d = CACHE.get(uuid);
        if (d != null) {
            fn.accept(d);
            save(uuid);
            return;
        }
        PlayerData off = load(uuid);
        fn.accept(off);
        String json = Json.COMPACT.toJson(off);
        db().async(c -> write(c, uuid, off.name, json));
    }

    /** Modifie les donnees de tous les joueurs connus (en ligne : cache ; hors ligne : base). Resultat : nombre de joueurs. */
    public static java.util.concurrent.CompletableFuture<Integer> updateAll(java.util.function.Consumer<PlayerData> fn) {
        java.util.Set<UUID> cached = new java.util.HashSet<>(CACHE.keySet());
        for (UUID u : cached) {
            fn.accept(CACHE.get(u));
            save(u);
        }
        return db().txAsync(c -> {
            int n = cached.size();
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, data FROM " + db().table("players")); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
            for (String[] r : rows) {
                UUID u = UUID.fromString(r[0]);
                if (cached.contains(u)) continue;
                PlayerData d = r[2] == null ? new PlayerData() : Json.COMPACT.fromJson(r[2], PlayerData.class);
                if (d == null) continue;
                d.normalize();
                fn.accept(d);
                write(c, u, r[1] == null ? d.name : r[1], Json.COMPACT.toJson(d));
                n++;
            }
            return n;
        });
    }

    public static void dirty(UUID uuid) {
        DIRTY.add(uuid);
    }

    /** Sauvegarde asynchrone d'un joueur (snapshot pris maintenant). */
    public static void save(UUID uuid) {
        PlayerData d = CACHE.get(uuid);
        if (d == null) return;
        DIRTY.remove(uuid);
        String json = Json.COMPACT.toJson(d);
        String name = d.name;
        db().async(c -> write(c, uuid, name, json)).exceptionally(t -> {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Sauvegarde des donnees de {} impossible : {}", name, t.toString());
            return null;
        });
    }

    private static Void write(java.sql.Connection c, UUID uuid, String name, String json) throws java.sql.SQLException {
        String sql = db().upsert("players", new String[]{"uuid", "name", "data", "updated"}, new String[]{"uuid"});
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, json);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
        return null;
    }

    public static void flushDirty() {
        for (UUID id : new ArrayList<>(DIRTY)) save(id);
    }

    public static void onJoin(ServerPlayer p) {
        PlayerData d = get(p);
        long now = System.currentTimeMillis();
        if (d.firstJoin == 0) d.firstJoin = now;
        d.lastSeen = now;
        dirty(p.getUUID());
    }

    public static void onLeave(ServerPlayer p) {
        PlayerData d = CACHE.get(p.getUUID());
        if (d != null) {
            d.lastSeen = System.currentTimeMillis();
            d.playtimeTicks = playtimeTicks(p);
            save(p.getUUID());
            if (!Players.isTest(p.getUUID())) CACHE.remove(p.getUUID());
        }
    }

    public static long playtimeTicks(ServerPlayer p) {
        return p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    /** Arret du serveur : ecriture synchrone de tout le cache. */
    public static void saveAllSync() {
        for (Map.Entry<UUID, PlayerData> e : CACHE.entrySet()) {
            ServerPlayer p = Players.online(e.getKey());
            if (p != null) e.getValue().playtimeTicks = playtimeTicks(p);
            String json = Json.COMPACT.toJson(e.getValue());
            try {
                db().run(c -> write(c, e.getKey(), e.getValue().name, json));
            } catch (Exception ex) {
                CobbleUtils.LOG.error("[CobblePulse-Utils] Sauvegarde finale de {} impossible : {}", e.getValue().name, ex.toString());
            }
        }
        DIRTY.clear();
    }

    public static void clear() {
        CACHE.clear();
        DIRTY.clear();
    }
}
