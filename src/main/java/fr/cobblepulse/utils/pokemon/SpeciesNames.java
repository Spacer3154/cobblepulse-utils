package fr.cobblepulse.utils.pokemon;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import fr.cobblepulse.utils.CobbleUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Noms d'especes lisibles partout, y compris pour les Fakemons ajoutes par datapack.
 * <p>Cobblemon nomme une espece par une cle de traduction ({@code cobblemon.species.etreroch.name}). Quand le joueur
 * n'a pas la traduction (pack de Fakemons installe seulement sur le serveur), le jeu affichait la cle brute. Chaque
 * nom envoye porte donc un texte de secours, affiche par le client quand il n'a pas la traduction.</p>
 * <p>Le texte de secours vient, dans l'ordre : des fichiers de langue des datapacks du monde (un pack de Fakemons
 * contient souvent ses {@code assets/<ns>/lang/fr_fr.json}), puis de ceux des mods, dans la langue du serveur
 * ({@code fr_fr}, puis {@code en_us}), et a defaut du nom ecrit dans le fichier de l'espece.</p>
 */
public final class SpeciesNames {
    /** Cle de traduction → nom, dans la langue du serveur (rempli au demarrage, ou recu du serveur cote client). */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    /** Cles dont la traduction ne vient que d'un datapack (ou d'aucun fichier) : a envoyer aux joueurs. */
    private static final Map<String, String> DATAPACK_ONLY = new ConcurrentHashMap<>();
    public static volatile String language = "fr_fr";

    private SpeciesNames() {}

    private static boolean client() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    public static String key(Species s) {
        return s.getTranslationKey();
    }

    /** Nom a envoyer : traduit par le client, avec un texte de secours s'il n'a pas la traduction. */
    public static MutableComponent of(Species s) {
        return Component.translatableWithFallback(key(s), text(s));
    }

    /** Nom en texte, dans la langue du serveur (ou du client, cote client). */
    public static String text(Species s) {
        if (s == null) return "?";
        String k = key(s);
        Language lang = Language.getInstance();
        // cote client : la langue du joueur d'abord (packs de ressources compris)
        if (client() && lang.has(k)) return lang.getOrDefault(k);
        String n = NAMES.get(k);
        if (n != null && !n.isBlank()) return n;
        if (lang.has(k)) return lang.getOrDefault(k);
        return pretty(s.getName());
    }

    static String pretty(String n) {
        if (n == null || n.isBlank()) return "?";
        String t = n.replace('_', ' ');
        // nom ecrit en minuscules dans le fichier de l'espece : majuscule initiale
        return t.equals(t.toLowerCase(Locale.ROOT)) ? Character.toUpperCase(t.charAt(0)) + t.substring(1) : t;
    }

    /** Noms a envoyer aux joueurs : especes que les mods ne traduisent pas (Fakemons de datapacks). */
    public static Map<String, String> forClients() {
        return Collections.unmodifiableMap(DATAPACK_ONLY);
    }

    /** Cote client : noms recus du serveur. */
    public static void accept(Map<String, String> names) {
        NAMES.putAll(names);
    }

    /** Recharge les noms (demarrage, /reload, /cp reload). */
    public static void reload(MinecraftServer server) {
        long t0 = System.currentTimeMillis();
        Set<String> keys = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        for (Species s : PokemonSpecies.getSpecies()) {
            keys.add(key(s));
            namespaces.add(s.getResourceIdentifier().getNamespace());
        }
        namespaces.add("cobblemon");
        String lang = language == null || language.isBlank() ? "fr_fr" : language.toLowerCase(Locale.ROOT);
        Map<String, String> mods = new HashMap<>();
        Map<String, String> modsEn = new HashMap<>();
        Map<String, String> packs = new HashMap<>();
        Map<String, String> packsEn = new HashMap<>();
        for (String ns : namespaces) {
            readMods(ns, lang, keys, mods);
            readMods(ns, "en_us", keys, modsEn);
        }
        int packCount = 0;
        if (server != null) {
            for (Pack pack : server.getPackRepository().getSelectedPacks()) {
                try (PackResources r = pack.open()) {
                    boolean any = false;
                    for (String ns : namespaces) {
                        any |= readPack(r, ns, lang, keys, packs);
                        any |= readPack(r, ns, "en_us", keys, packsEn);
                    }
                    if (any) packCount++;
                } catch (Throwable t) {
                    CobbleUtils.LOG.debug("[CobblePulse-Utils] Langues du pack {} illisibles : {}", pack.getId(), t.toString());
                }
            }
        }
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> only = new LinkedHashMap<>();
        Language server_lang = Language.getInstance();
        for (Species s : PokemonSpecies.getSpecies()) {
            String k = key(s);
            String name = first(packs.get(k), mods.get(k), client() ? null : (server_lang.has(k) ? server_lang.getOrDefault(k) : null),
                    packsEn.get(k), modsEn.get(k), pretty(s.getName()));
            merged.put(k, name);
            if (!mods.containsKey(k) && !modsEn.containsKey(k) && !(server_lang.has(k) && !client())) only.put(k, name);
        }
        NAMES.clear();
        NAMES.putAll(merged);
        DATAPACK_ONLY.clear();
        DATAPACK_ONLY.putAll(only);
        CobbleUtils.LOG.info("[CobblePulse-Utils] Noms d'especes : {} ({} venant de {} datapack(s), {} sans traduction des mods) en {} ms",
                merged.size(), packs.size() + packsEn.size(), packCount, only.size(), System.currentTimeMillis() - t0);
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "?";
    }

    /** Fichiers de langue des mods (tous les jars qui ont ce chemin). */
    private static void readMods(String ns, String lang, Set<String> keys, Map<String, String> out) {
        try {
            Enumeration<URL> urls = SpeciesNames.class.getClassLoader().getResources("assets/" + ns + "/lang/" + lang + ".json");
            while (urls.hasMoreElements()) {
                URL u = urls.nextElement();
                try (InputStream in = u.openStream()) {
                    read(in, keys, out);
                } catch (Throwable t) {
                    CobbleUtils.LOG.debug("[CobblePulse-Utils] Langue illisible {} : {}", u, t.toString());
                }
            }
        } catch (Throwable t) {
            CobbleUtils.LOG.debug("[CobblePulse-Utils] Langues des mods ({}) : {}", ns, t.toString());
        }
    }

    /** Fichier de langue d'un datapack (assets/<ns>/lang/<langue>.json, quand le pack en contient). */
    private static boolean readPack(PackResources r, String ns, String lang, Set<String> keys, Map<String, String> out) {
        try {
            IoSupplier<InputStream> sup = r.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath(ns, "lang/" + lang + ".json"));
            if (sup == null) return false;
            try (InputStream in = sup.get()) {
                return read(in, keys, out) > 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static int read(InputStream in, Set<String> keys, Map<String, String> out) throws java.io.IOException {
        JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.setLenient(true);
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) return 0;
        JsonObject o = root.getAsJsonObject();
        int n = 0;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!keys.contains(e.getKey()) || !e.getValue().isJsonPrimitive()) continue;
            out.put(e.getKey(), e.getValue().getAsString());
            n++;
        }
        return n;
    }
}
