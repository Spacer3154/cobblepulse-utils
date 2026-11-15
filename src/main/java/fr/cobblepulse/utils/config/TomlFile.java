package fr.cobblepulse.utils.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import fr.cobblepulse.utils.CobbleUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fichier TOML de configuration. Le fichier par defaut est copie depuis le jar s'il n'existe pas ;
 * toute cle absente du fichier utilisateur retombe sur la valeur par defaut embarquee.
 */
public final class TomlFile {
    private final String name;
    private final Config user;
    private final Config defaults;

    private TomlFile(String name, Config user, Config defaults) {
        this.name = name;
        this.user = user;
        this.defaults = defaults;
    }

    public String name() {
        return name;
    }

    public static TomlFile load(Path root, String relPath) {
        Path file = root.resolve(relPath);
        Config defaults = readResource(relPath);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = TomlFile.class.getResourceAsStream("/utils_defaults/" + relPath)) {
                    if (in != null) {
                        Files.copy(in, file);
                    } else {
                        Files.writeString(file, "", StandardCharsets.UTF_8);
                    }
                }
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Config user = new TomlParser().parse(r);
                return new TomlFile(relPath, user, defaults);
            }
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Impossible de lire {} : {} (valeurs par defaut utilisees)", file, e.toString());
            return new TomlFile(relPath, Config.inMemory(), defaults);
        }
    }

    /** Charge un TOML present uniquement dans le jar (ou un fichier existant sans valeurs par defaut). */
    public static TomlFile loadPlain(Path file) {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new TomlFile(file.getFileName().toString(), new TomlParser().parse(r), Config.inMemory());
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Impossible de lire {} : {}", file, e.toString());
            return new TomlFile(file.getFileName().toString(), Config.inMemory(), Config.inMemory());
        }
    }

    private static Config readResource(String relPath) {
        try (InputStream in = TomlFile.class.getResourceAsStream("/utils_defaults/" + relPath)) {
            if (in == null) return Config.inMemory();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new TomlParser().parse(r);
            }
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Defaut invalide {} : {}", relPath, e.toString());
            return Config.inMemory();
        }
    }

    public Object raw(String path) {
        Object v = user.contains(path) ? user.get(path) : null;
        if (v == null && defaults.contains(path)) v = defaults.get(path);
        return v;
    }

    public boolean has(String path) {
        return user.contains(path) || defaults.contains(path);
    }

    public String str(String path, String def) {
        Object v = raw(path);
        return v == null ? def : String.valueOf(v);
    }

    public String str(String path) {
        return str(path, "");
    }

    public int i(String path, int def) {
        Object v = raw(path);
        return v instanceof Number n ? n.intValue() : parseOr(v, def).intValue();
    }

    public long l(String path, long def) {
        Object v = raw(path);
        return v instanceof Number n ? n.longValue() : parseOr(v, def).longValue();
    }

    public double d(String path, double def) {
        Object v = raw(path);
        return v instanceof Number n ? n.doubleValue() : parseOr(v, def).doubleValue();
    }

    public boolean b(String path, boolean def) {
        Object v = raw(path);
        if (v instanceof Boolean bo) return bo;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static Number parseOr(Object v, Number def) {
        if (v == null) return def;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public List<String> list(String path) {
        Object v = raw(path);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) out.add(String.valueOf(o));
        } else if (v != null && !String.valueOf(v).isBlank()) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    /** Cles directes d'une table (ex. "tiers" -> first, second...). Union fichier utilisateur + defauts. */
    public Set<String> keys(String path) {
        Set<String> out = new LinkedHashSet<>();
        collect(user, path, out);
        if (out.isEmpty()) collect(defaults, path, out);
        return out;
    }

    private static void collect(Config c, String path, Set<String> out) {
        Object v = path.isEmpty() ? c : (c.contains(path) ? c.get(path) : null);
        if (v instanceof Config sub) {
            out.addAll(sub.valueMap().keySet());
        }
    }

    /** Tableau de tables TOML ([[path]]) : la liste du fichier utilisateur remplace celle par defaut. */
    public List<TomlFile> tables(String path) {
        Object v = user.contains(path) ? user.get(path) : (defaults.contains(path) ? defaults.get(path) : null);
        List<TomlFile> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Config c) out.add(new TomlFile(name + "#" + path, c, Config.inMemory()));
            }
        }
        return out;
    }

    /** Sous-table ([path]) avec repli sur la sous-table par defaut. */
    public TomlFile sub(String path) {
        Object u = user.contains(path) ? user.get(path) : null;
        Object d = defaults.contains(path) ? defaults.get(path) : null;
        return new TomlFile(name + "#" + path, u instanceof Config cu ? cu : Config.inMemory(), d instanceof Config cd ? cd : Config.inMemory());
    }

    public Set<String> rootKeys() {
        Set<String> out = new LinkedHashSet<>(user.valueMap().keySet());
        out.addAll(defaults.valueMap().keySet());
        return Collections.unmodifiableSet(out);
    }
}
