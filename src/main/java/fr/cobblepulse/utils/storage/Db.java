package fr.cobblepulse.utils.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.DatabaseConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Base de donnees hybride (cahier des charges §3) : SQLite par defaut (fichier local, zero configuration)
 * ou MySQL/MariaDB (pool HikariCP, partage possible entre serveurs).
 * <p>
 * Chaque module choisit sa base via {@link #of(String)} : si le module est coche dans
 * {@code modules_using_database}, il utilise la base principale (MySQL en mode MYSQL), sinon la base SQLite locale.
 * Toutes les operations critiques passent par {@link #tx} (transaction atomique, annulee en cas d'erreur).
 */
public final class Db {
    @FunctionalInterface
    public interface SqlFn<T> {
        T apply(Connection c) throws SQLException;
    }

    private static Source main;
    private static Source local;

    private Db() {}

    /** Une source de donnees (SQLite mono-connexion serialisee, ou MySQL pool). */
    public static final class Source {
        final boolean sqlite;
        final String desc;
        private final HikariDataSource ds;
        private final Connection sqliteConn;
        private final ExecutorService exec;
        final String p;

        private Source(boolean sqlite, String desc, HikariDataSource ds, Connection sqliteConn, int threads, String prefix) {
            this.sqlite = sqlite;
            this.desc = desc;
            this.ds = ds;
            this.sqliteConn = sqliteConn;
            this.p = prefix;
            this.exec = Executors.newFixedThreadPool(sqlite ? 1 : Math.max(1, threads), r -> {
                Thread t = new Thread(r, "CobblePulse-Utils-DB" + (sqlite ? "-sqlite" : "-mysql"));
                t.setDaemon(true);
                return t;
            });
        }

        public boolean sqlite() {
            return sqlite;
        }

        public String description() {
            return desc;
        }

        public String table(String name) {
            return p + name;
        }

        /** Execution synchrone (thread appelant). En SQLite, l'acces a la connexion unique est serialise. */
        public <T> T run(SqlFn<T> fn) throws SQLException {
            if (sqlite) {
                synchronized (sqliteConn) {
                    return fn.apply(sqliteConn);
                }
            }
            try (Connection c = ds.getConnection()) {
                return fn.apply(c);
            }
        }

        /** Transaction atomique : tout ou rien. */
        public <T> T tx(SqlFn<T> fn) throws SQLException {
            return run(c -> {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    T out = fn.apply(c);
                    c.commit();
                    return out;
                } catch (SQLException | RuntimeException e) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                    }
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
            });
        }

        public <T> CompletableFuture<T> async(SqlFn<T> fn) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return run(fn);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, exec);
        }

        public <T> CompletableFuture<T> txAsync(SqlFn<T> fn) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return tx(fn);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, exec);
        }

        public String autoId() {
            return sqlite ? "id INTEGER PRIMARY KEY AUTOINCREMENT" : "id BIGINT AUTO_INCREMENT PRIMARY KEY";
        }

        public String charset() {
            return sqlite ? "" : " CHARACTER SET utf8mb4";
        }

        public String bigText() {
            return sqlite ? "TEXT" : "MEDIUMTEXT";
        }

        public String random() {
            return sqlite ? "RANDOM()" : "RAND()";
        }

        /** INSERT ... ON CONFLICT / ON DUPLICATE KEY pour une cle primaire. */
        /** INSERT qui ignore un doublon de cle (retourne 0 ligne si la ligne existe deja). */
        public String insertIgnore() {
            return sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
        }

        public String upsert(String table, String[] cols, String[] keyCols) {
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table(table)).append(" (").append(String.join(",", cols)).append(") VALUES (");
            for (int i = 0; i < cols.length; i++) sb.append(i == 0 ? "?" : ",?");
            sb.append(")");
            StringBuilder upd = new StringBuilder();
            for (String c : cols) {
                boolean key = false;
                for (String k : keyCols) if (k.equals(c)) key = true;
                if (key) continue;
                if (upd.length() > 0) upd.append(",");
                upd.append(c).append(sqlite ? "=excluded." + c : "=VALUES(" + c + ")");
            }
            if (sqlite) sb.append(" ON CONFLICT(").append(String.join(",", keyCols)).append(") DO UPDATE SET ").append(upd);
            else sb.append(" ON DUPLICATE KEY UPDATE ").append(upd);
            return sb.toString();
        }

        void close() {
            exec.shutdown();
            try {
                exec.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            try {
                if (ds != null) ds.close();
                if (sqliteConn != null) sqliteConn.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static Source main() {
        return main;
    }

    public static Source local() {
        return local;
    }

    /** Base utilisee par un module (cle de modules_using_database). */
    public static Source of(String module) {
        DatabaseConfig c = Cfg.database;
        if (c != null && c.modulesUsingDatabase != null) {
            Boolean v = c.modulesUsingDatabase.get(module);
            if (v != null && !v) return local;
        }
        return main;
    }

    public static synchronized void init() throws Exception {
        close();
        DatabaseConfig cfg = Cfg.database;
        String prefix = cfg.tablePrefix == null ? "cpu_" : cfg.tablePrefix.replaceAll("[^A-Za-z0-9_]", "");
        local = openSqlite(prefix);
        if ("MYSQL".equalsIgnoreCase(cfg.storageType)) {
            try {
                main = openMysql(cfg, prefix);
            } catch (Exception e) {
                CobbleUtils.LOG.error("[CobblePulse-Utils] Connexion MySQL impossible ({}). Repli sur SQLite.", e.toString());
                main = local;
            }
        } else {
            main = local;
        }
        Schema.create(main);
        if (local != main) Schema.create(local);
        CobbleUtils.LOG.info("[CobblePulse-Utils] Stockage actif : {}{}", main.desc, local != main ? " (+ " + local.desc + " pour les modules locaux)" : "");
    }

    private static Source openSqlite(String prefix) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path file = Cfg.utilsRoot().resolve(Cfg.database.sqliteFile == null ? "data/cobblepulse_utils.db" : Cfg.database.sqliteFile);
        Files.createDirectories(file.getParent());
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA busy_timeout=10000");
        }
        return new Source(true, "SQLite (" + file + ")", null, c, 1, prefix);
    }

    private static Source openMysql(DatabaseConfig cfg, String prefix) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ignored) {
        }
        DatabaseConfig.Mysql m = cfg.mysql;
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + m.host + ":" + m.port + "/" + m.database + "?useSSL=" + m.useSsl
                + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC");
        hc.setUsername(m.username);
        hc.setPassword(m.password);
        hc.setMaximumPoolSize(Math.max(2, m.poolSize));
        hc.setPoolName("CobblePulse-Utils-MySQL");
        hc.setConnectionTimeout(10_000);
        hc.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        HikariDataSource ds = new HikariDataSource(hc);
        return new Source(false, "MySQL (" + m.host + "/" + m.database + ")", ds, null, Math.max(2, m.poolSize), prefix);
    }

    public static synchronized void close() {
        if (main != null && main != local) main.close();
        if (local != null) local.close();
        main = null;
        local = null;
    }

    // ---- petits utilitaires JDBC ----

    public static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    public static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else ps.setObject(i + 1, a);
        }
    }
}
