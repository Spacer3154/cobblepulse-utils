package fr.cobblepulse.utils.pokemon;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Retrouve une espece Cobblemon, y compris les fakemons ajoutes par datapack :
 * <ul>
 *   <li>identifiant complet : {@code monpack:volt_mon} ;</li>
 *   <li>chemin seul, avec ou sans tirets bas : {@code volt_mon}, {@code voltmon} ;</li>
 *   <li>nom affiche : {@code Volt Mon}, {@code Volt-Mon}.</li>
 * </ul>
 * En cas d'homonymie, l'espace de noms {@code cobblemon} est prefere.
 */
public final class SpeciesLookup {
    private SpeciesLookup() {}

    /** Minuscules, sans accents ni ponctuation : « Étreroch » → « etreroch ». */
    public static String norm(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static Species resolve(String input) {
        if (input == null) return null;
        String raw = input.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return null;
        String path = raw;
        if (raw.contains(":")) {
            ResourceLocation rl = ResourceLocation.tryParse(raw.replace(' ', '_'));
            if (rl != null) {
                Species s = PokemonSpecies.getByIdentifier(rl);
                if (s != null) return s;
            }
            path = raw.substring(raw.indexOf(':') + 1);
        }
        for (String candidate : new String[]{path.replace(' ', '_'), path.replace(' ', '_').replace('-', '_'), norm(path)}) {
            ResourceLocation rl = ResourceLocation.tryParse("cobblemon:" + candidate);
            if (rl == null) continue;
            Species s = PokemonSpecies.getByIdentifier(rl);
            if (s != null) return s;
        }
        // "Type: Null" contient ':' sans etre un identifiant : on essaie aussi le texte entier normalise
        String key = norm(path);
        String whole = norm(raw);
        if (key.isEmpty() && whole.isEmpty()) return null;
        Species byPath = null;
        Species byName = null;
        for (Species s : PokemonSpecies.getSpecies()) {
            ResourceLocation id = s.getResourceIdentifier();
            boolean cobblemon = "cobblemon".equals(id.getNamespace());
            String p = norm(id.getPath());
            String n = norm(s.getName());
            String shown = norm(SpeciesNames.text(s));
            if (p.equals(key) || p.equals(whole)) {
                if (cobblemon) return s;
                if (byPath == null) byPath = s;
            } else if (n.equals(key) || n.equals(whole) || shown.equals(key) || shown.equals(whole)) {
                if (byName == null || cobblemon) byName = s;
            }
        }
        return byPath != null ? byPath : byName;
    }

    /** Reference stable d'une espece : nom court pour Cobblemon, identifiant complet sinon. */
    public static String ref(Species s) {
        ResourceLocation id = s.getResourceIdentifier();
        return "cobblemon".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }
}
