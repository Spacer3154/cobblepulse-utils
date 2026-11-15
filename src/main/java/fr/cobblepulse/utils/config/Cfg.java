package fr.cobblepulse.utils.config;

import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.Json;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fichiers de configuration :
 * <pre>
 * config/cobblepulse/database.json        (cahier des charges §3.2)
 * config/cobblepulse/utils/general.json   commands.json  messages.toml
 *                          warps.json     kits.json      dexrewards.json
 *                          shop.json + shop/&lt;categorie&gt;.json
 *                          gts.json  sts.json  wondertrade.json  alerts.json  boss.json
 * </pre>
 * Rechargement a chaud : /cp reload.
 */
public final class Cfg {
    public static DatabaseConfig database = new DatabaseConfig();
    public static GeneralConfig general = new GeneralConfig();
    public static CommandsConfig commands = new CommandsConfig();
    public static TomlFile messages;
    public static WarpsConfig warps = new WarpsConfig();
    public static KitsConfig kits = new KitsConfig();
    public static DexConfig dex = new DexConfig();
    public static ShopConfig shop = new ShopConfig();
    public static final Map<String, ShopConfig.Category> SHOP = new LinkedHashMap<>();
    public static MarketConfig.Gts gts = new MarketConfig.Gts();
    public static MarketConfig.Sts sts = new MarketConfig.Sts();
    public static MarketConfig.WonderTrade wt = new MarketConfig.WonderTrade();
    public static AlertsConfig alerts = new AlertsConfig();
    public static BossConfig boss = new BossConfig();
    public static int revision;

    private Cfg() {}

    public static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("cobblepulse");
    }

    public static Path utilsRoot() {
        return root().resolve("utils");
    }

    public static void loadAll() {
        try {
            Files.createDirectories(utilsRoot());
        } catch (IOException e) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Creation de {} impossible : {}", utilsRoot(), e.toString());
        }
        loadDatabase();
        Path u = utilsRoot();
        messages = TomlFile.load(u, "messages.toml");
        general = load(u.resolve("general.json"), GeneralConfig.class, GeneralConfig::new);
        general.normalize();
        commands = load(u.resolve("commands.json"), CommandsConfig.class, CommandsConfig::new);
        commands.normalize();
        warps = load(u.resolve("warps.json"), WarpsConfig.class, WarpsConfig::new);
        warps.normalize();
        kits = load(u.resolve("kits.json"), KitsConfig.class, KitsConfig::defaults);
        kits.normalize();
        dex = load(u.resolve("dexrewards.json"), DexConfig.class, DexConfig::defaults);
        dex.normalize();
        shop = load(u.resolve("shop.json"), ShopConfig.class, ShopConfig::new);
        shop.normalize();
        loadShop();
        gts = load(u.resolve("gts.json"), MarketConfig.Gts.class, MarketConfig.Gts::new);
        gts.normalize();
        sts = load(u.resolve("sts.json"), MarketConfig.Sts.class, MarketConfig.Sts::new);
        sts.normalize();
        wt = load(u.resolve("wondertrade.json"), MarketConfig.WonderTrade.class, MarketConfig.WonderTrade::new);
        wt.normalize();
        alerts = load(u.resolve("alerts.json"), AlertsConfig.class, AlertsConfig::new);
        alerts.normalize();
        boss = load(u.resolve("boss.json"), BossConfig.class, BossConfig::new);
        boss.normalize();
        // re-ecriture des fichiers completes (nouvelles cles ajoutees, valeurs corrigees)
        save("general.json", general);
        save("commands.json", commands);
        save("warps.json", warps);
        save("kits.json", kits);
        save("dexrewards.json", dex);
        save("shop.json", shop);
        save("gts.json", gts);
        save("sts.json", sts);
        save("wondertrade.json", wt);
        save("alerts.json", alerts);
        save("boss.json", boss);
        revision++;
    }

    private static void loadDatabase() {
        Path f = root().resolve("database.json");
        try {
            Files.createDirectories(root());
            if (!Files.exists(f)) writeAtomic(f, DatabaseConfig.template());
            DatabaseConfig c = Json.CFG.fromJson(Files.readString(f, StandardCharsets.UTF_8), DatabaseConfig.class);
            database = c == null ? new DatabaseConfig() : c;
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] {} invalide ({}), SQLite utilise par defaut", f, e.toString());
            database = new DatabaseConfig();
        }
        database.normalize();
    }

    private static final Map<Path, Boolean> BROKEN = new LinkedHashMap<>();

    public static <T> T load(Path file, Class<T> type, Supplier<T> def) {
        BROKEN.remove(file);
        try {
            if (!Files.exists(file)) {
                T d = def.get();
                writeAtomic(file, Json.CFG.toJson(d));
                return d;
            }
            T v = Json.CFG.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            return v == null ? def.get() : v;
        } catch (Exception e) {
            BROKEN.put(file, true);
            CobbleUtils.LOG.error("[CobblePulse-Utils] Fichier {} invalide : {} (valeurs par defaut utilisees, fichier conserve)", file, e.toString());
            return def.get();
        }
    }

    /** Enregistre un fichier de utils/ (jamais si le fichier utilisateur est illisible : on ne l'ecrase pas). */
    public static void save(String rel, Object obj) {
        Path f = utilsRoot().resolve(rel);
        if (BROKEN.containsKey(f)) return;
        try {
            writeAtomic(f, Json.CFG.toJson(obj));
        } catch (IOException e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Ecriture de {} impossible : {}", f, e.toString());
        }
    }

    public static Path shopDir() {
        return utilsRoot().resolve("shop");
    }

    private static void loadShop() {
        SHOP.clear();
        Path dir = shopDir();
        boolean fresh = !Files.isDirectory(dir);
        try {
            Files.createDirectories(dir);
            if (fresh) {
                for (ShopConfig.Category c : ShopConfig.defaults()) writeAtomic(dir.resolve(c.id + ".json"), Json.CFG.toJson(c));
            }
        } catch (IOException e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Dossier du shop : {}", e.toString());
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) files.add(p);
        } catch (IOException e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Lecture du shop : {}", e.toString());
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<ShopConfig.Category> cats = new ArrayList<>();
        for (Path p : files) {
            ShopConfig.Category c = load(p, ShopConfig.Category.class, ShopConfig.Category::new);
            String name = p.getFileName().toString();
            c.id = name.substring(0, name.length() - 5).toLowerCase(java.util.Locale.ROOT);
            ShopConfig.normalize(c);
            cats.add(c);
        }
        for (ShopConfig.Category c : cats) SHOP.put(c.id, c);
    }

    public static void saveShopCategory(ShopConfig.Category c) {
        try {
            writeAtomic(shopDir().resolve(c.id + ".json"), Json.CFG.toJson(c));
        } catch (IOException e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Ecriture de la categorie {} : {}", c.id, e.toString());
        }
        revision++;
    }

    public static void deleteShopCategory(String id) {
        try {
            Files.deleteIfExists(shopDir().resolve(id + ".json"));
        } catch (IOException e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Suppression de la categorie {} : {}", id, e.toString());
        }
        SHOP.remove(id);
        revision++;
    }

    public static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
