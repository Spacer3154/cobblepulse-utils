package fr.cobblepulse.utils.storage;

import java.sql.SQLException;
import java.sql.Statement;

/** Creation des tables (idempotente). Prefixe configurable (cpu_ par defaut). */
final class Schema {
    private Schema() {}

    static void create(Db.Source s) throws SQLException {
        String cs = s.charset();
        String big = s.bigText();
        String id = s.autoId();
        s.run(c -> {
            try (Statement st = c.createStatement()) {
                // Donnees joueur (homes, surnom, cooldowns, temps de jeu, ignores...) en JSON
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("players") + " (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32),"
                        + " data " + big + ", updated BIGINT)" + cs);
                // GTS : annonces + historique
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("gts_listings") + " (" + id + ", seller VARCHAR(36) NOT NULL,"
                        + " seller_name VARCHAR(32), kind VARCHAR(8) NOT NULL, data " + big + " NOT NULL, summary TEXT, price BIGINT NOT NULL,"
                        + " category VARCHAR(32), created BIGINT, expires BIGINT, status VARCHAR(12) NOT NULL, buyer VARCHAR(36),"
                        + " buyer_name VARCHAR(32), closed_at BIGINT, tax BIGINT DEFAULT 0, server VARCHAR(32))" + cs);
                // Boite de reception : paiements GTS, annonces expirees, objets en attente
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("mailbox") + " (" + id + ", owner VARCHAR(36) NOT NULL, kind VARCHAR(8) NOT NULL,"
                        + " data " + big + ", amount BIGINT DEFAULT 0, reason TEXT, created BIGINT)" + cs);
                // STS : journal des rachats (quotas journaliers + audit)
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("sts_logs") + " (" + id + ", player VARCHAR(36) NOT NULL, player_name VARCHAR(32),"
                        + " species VARCHAR(64), level INT, shiny INT, price BIGINT, data " + big + ", at BIGINT)" + cs);
                // Echange Miracle : reservoir + statistiques
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("wt_pool") + " (" + id + ", data " + big + " NOT NULL, species VARCHAR(64),"
                        + " level INT, shiny INT, rare INT, depositor VARCHAR(36), depositor_name VARCHAR(32), deposited BIGINT)" + cs);
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("wt_stats") + " (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32),"
                        + " trades INT DEFAULT 0, shinies INT DEFAULT 0, last_at BIGINT DEFAULT 0)" + cs);
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("wt_log") + " (" + id + ", player VARCHAR(36), player_name VARCHAR(32),"
                        + " given VARCHAR(64), received VARCHAR(64), at BIGINT)" + cs);
                // Enderchests evolutifs (une ligne par page)
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("enderchests") + " (uuid VARCHAR(36) NOT NULL, page INT NOT NULL,"
                        + " data " + big + ", updated BIGINT, PRIMARY KEY (uuid, page))" + cs);
                // Boss : premieres victoires par joueur
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("boss_victories") + " (uuid VARCHAR(36) NOT NULL, boss_key VARCHAR(96) NOT NULL,"
                        + " first_at BIGINT, wins INT DEFAULT 0, PRIMARY KEY (uuid, boss_key))" + cs);
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + s.table("kv") + " (k VARCHAR(128) PRIMARY KEY, v " + big + ")" + cs);
            }
            safeIndex(c, "CREATE INDEX " + s.table("gts_status_idx") + " ON " + s.table("gts_listings") + " (status, expires)");
            safeIndex(c, "CREATE INDEX " + s.table("gts_seller_idx") + " ON " + s.table("gts_listings") + " (seller, status)");
            safeIndex(c, "CREATE INDEX " + s.table("mailbox_owner_idx") + " ON " + s.table("mailbox") + " (owner)");
            safeIndex(c, "CREATE INDEX " + s.table("sts_player_idx") + " ON " + s.table("sts_logs") + " (player, at)");
            return null;
        });
    }

    private static void safeIndex(java.sql.Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException ignored) {
            // index deja present
        }
    }
}
