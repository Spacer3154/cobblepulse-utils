package fr.cobblepulse.utils.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** config/cobblepulse/database.json (cahier des charges §3.2). */
public final class DatabaseConfig {
    /** "SQLITE" (par defaut, zero config) ou "MYSQL". */
    public String storageType = "SQLITE";
    public Mysql mysql = new Mysql();
    /** Fichier SQLite, relatif a config/cobblepulse/utils/. */
    public String sqliteFile = "data/cobblepulse_utils.db";
    public String tablePrefix = "cpu_";
    /** Nom de ce serveur (annonces GTS en reseau). */
    public String serverId = "main";
    /** Modules dont les donnees vont dans la base principale (MySQL partage) ; false = SQLite local. */
    public Map<String, Boolean> modulesUsingDatabase = new LinkedHashMap<>();

    public static final class Mysql {
        public String host = "localhost";
        public int port = 3306;
        public String database = "cobblepulse_utils";
        public String username = "root";
        public String password = "password";
        public int poolSize = 10;
        public boolean useSsl = false;
    }

    public void normalize() {
        if (storageType == null) storageType = "SQLITE";
        storageType = storageType.trim().toUpperCase(java.util.Locale.ROOT);
        if (mysql == null) mysql = new Mysql();
        if (sqliteFile == null || sqliteFile.isBlank()) sqliteFile = "data/cobblepulse_utils.db";
        if (tablePrefix == null) tablePrefix = "cpu_";
        if (serverId == null || serverId.isBlank()) serverId = "main";
        if (modulesUsingDatabase == null) modulesUsingDatabase = new LinkedHashMap<>();
        for (String k : new String[]{"gts", "sts_logs", "wondertrade_pool", "enderchests", "boss_first_victories", "player_data"}) {
            modulesUsingDatabase.putIfAbsent(k, true);
        }
    }

    public static String template() {
        return """
                {
                  // Choix : "SQLITE" (par defaut, zero config) ou "MYSQL"
                  "storage_type": "SQLITE",
                  "mysql": {
                    "host": "localhost",
                    "port": 3306,
                    "database": "cobblepulse_utils",
                    "username": "root",
                    "password": "password",
                    "pool_size": 10,
                    "use_ssl": false
                  },
                  // Fichier SQLite (relatif a config/cobblepulse/utils/)
                  "sqlite_file": "data/cobblepulse_utils.db",
                  "table_prefix": "cpu_",
                  // Nom de ce serveur (utile en reseau MySQL)
                  "server_id": "main",
                  // true : base principale (MySQL partagee entre serveurs) ; false : SQLite local a ce serveur
                  "modules_using_database": {
                    "gts": true,
                    "sts_logs": true,
                    "wondertrade_pool": true,
                    "enderchests": true,
                    "boss_first_victories": true,
                    "player_data": true
                  }
                }
                """;
    }
}
