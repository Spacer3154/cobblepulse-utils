package fr.cobblepulse.utils.pokemon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Noms francais (natures, types, statistiques, etiquettes, difficultes) et alias acceptes dans les fichiers. */
public final class Names {
    public static final Map<String, String> NATURES = new LinkedHashMap<>();
    public static final Map<String, String> TYPES = new LinkedHashMap<>();
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    public static final Map<String, String> STATS = new LinkedHashMap<>();
    public static final Map<String, String> DIFFICULTIES = new LinkedHashMap<>();

    static {
        String[][] natures = {{"hardy", "Hardi"}, {"lonely", "Solo"}, {"brave", "Brave"}, {"adamant", "Rigide"}, {"naughty", "Mauvais"},
                {"bold", "Assuré"}, {"docile", "Docile"}, {"relaxed", "Relax"}, {"impish", "Malin"}, {"lax", "Lâche"},
                {"timid", "Timide"}, {"hasty", "Pressé"}, {"serious", "Sérieux"}, {"jolly", "Jovial"}, {"naive", "Naïf"},
                {"modest", "Modeste"}, {"mild", "Doux"}, {"quiet", "Discret"}, {"bashful", "Pudique"}, {"rash", "Foufou"},
                {"calm", "Calme"}, {"gentle", "Gentil"}, {"sassy", "Malpoli"}, {"careful", "Prudent"}, {"quirky", "Bizarre"}};
        for (String[] n : natures) NATURES.put(n[0], n[1]);
        String[][] types = {{"normal", "normal"}, {"fire", "feu"}, {"water", "eau"}, {"grass", "plante"}, {"electric", "electrik"},
                {"ice", "glace"}, {"fighting", "combat"}, {"poison", "poison"}, {"ground", "sol"}, {"flying", "vol"},
                {"psychic", "psy"}, {"bug", "insecte"}, {"rock", "roche"}, {"ghost", "spectre"}, {"dragon", "dragon"},
                {"dark", "tenebres"}, {"steel", "acier"}, {"fairy", "fee"}};
        for (String[] t : types) TYPES.put(t[0], t[1]);
        String[][] labels = {{"legendary", "legendaire"}, {"mythical", "fabuleux"}, {"ultra_beast", "ultra_chimere"},
                {"paradox", "paradoxe"}, {"baby", "bebe"}, {"fossil", "fossile"}, {"powerhouse", "pseudo_legendaire"}};
        for (String[] l : labels) LABELS.put(l[0], l[1]);
        String[][] stats = {{"hp", "PV"}, {"atk", "Attaque"}, {"def", "Défense"}, {"spa", "Atq. Spé."}, {"spd", "Déf. Spé."}, {"spe", "Vitesse"}};
        for (String[] s : stats) STATS.put(s[0], s[1]);
        DIFFICULTIES.put("facile", "Facile");
        DIFFICULTIES.put("normal", "Normale");
        DIFFICULTIES.put("moyen", "Moyenne");
        DIFFICULTIES.put("difficile", "Difficile");
        DIFFICULTIES.put("globale", "Globale");
    }

    private Names() {}

    private static String plain(String s) {
        if (s == null) return "";
        String v = s.trim().toLowerCase(Locale.ROOT);
        v = v.replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ï', 'i').replace('î', 'i').replace('â', 'a').replace('à', 'a')
                .replace('ô', 'o').replace('û', 'u').replace('ç', 'c').replace(' ', '_').replace('-', '_');
        int c = v.indexOf(':');
        return c >= 0 ? v.substring(c + 1) : v;
    }

    /** Identifiant anglais Cobblemon d'un type ("feu" ou "fire" -> "fire"). */
    public static String typeId(String s) {
        String v = plain(s);
        if (TYPES.containsKey(v)) return v;
        for (Map.Entry<String, String> e : TYPES.entrySet()) if (e.getValue().equals(v)) return e.getKey();
        return v;
    }

    public static String typeName(String id) {
        String fr = TYPES.get(typeId(id));
        if (fr == null) return id;
        return switch (fr) {
            case "electrik" -> "Électrik";
            case "tenebres" -> "Ténèbres";
            case "fee" -> "Fée";
            default -> Character.toUpperCase(fr.charAt(0)) + fr.substring(1);
        };
    }

    /** Etiquette Cobblemon ("legendaire" ou "legendary" -> "legendary"). */
    public static String labelId(String s) {
        String v = plain(s);
        if (LABELS.containsKey(v)) return v;
        for (Map.Entry<String, String> e : LABELS.entrySet()) if (e.getValue().equals(v)) return e.getKey();
        return v;
    }

    /** Nature anglaise ("rigide" ou "adamant" -> "adamant"). */
    public static String natureId(String s) {
        String v = plain(s);
        if (NATURES.containsKey(v)) return v;
        for (Map.Entry<String, String> e : NATURES.entrySet()) if (plain(e.getValue()).equals(v)) return e.getKey();
        return v;
    }

    public static String nature(String s) {
        String fr = NATURES.get(natureId(s));
        return fr == null ? s : fr;
    }

    public static String gender(String g) {
        return switch (PokemonFactory.gender(g)) {
            case "male" -> "Mâle";
            case "female" -> "Femelle";
            case "genderless" -> "Asexué";
            default -> "Indifférent";
        };
    }

    public static String stat(String s) {
        String v = STATS.get(s);
        return v == null ? s : v;
    }

    public static String difficulty(String d) {
        String v = DIFFICULTIES.get(d);
        return v == null ? d : v;
    }

    public static String category(String c) {
        return switch (c) {
            case "quotidiennes" -> "Quotidiennes";
            case "hebdomadaires" -> "Hebdomadaires";
            default -> "Saison";
        };
    }

    public static String list(List<String> l) {
        return String.join(", ", l);
    }
}
