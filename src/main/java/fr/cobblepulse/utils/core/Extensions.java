package fr.cobblepulse.utils.core;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.utils.CobbleUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Lignes ajoutees par les autres mods CobblePulse aux fiches de Pokemon et d'objets (GTS, STS, Echange Miracle, menus),
 * sans dependance de compilation : points d'entree Fabric "cobblepulse:pokemon_lines" (Function&lt;Pokemon, List&lt;String&gt;&gt;)
 * et "cobblepulse:item_lines" (Function&lt;ItemStack, List&lt;String&gt;&gt;), lignes en codes couleur &.
 * Exemple : CobblePulse Ranch (castration, fertilite, fiche complete d'un oeuf).
 */
public final class Extensions {
    private static List<Function<Object, List<String>>> pokemon, items;

    private Extensions() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Function<Object, List<String>>> load(String key) {
        List<Function<Object, List<String>>> out = new ArrayList<>();
        try {
            for (var c : FabricLoader.getInstance().getEntrypointContainers(key, Function.class)) {
                try {
                    out.add((Function) c.getEntrypoint());
                    CobbleUtils.LOG.info("[CobblePulse-Utils] Extension {} de {}", key, c.getProvider().getMetadata().getId());
                } catch (Throwable t) {
                    CobbleUtils.LOG.warn("[CobblePulse-Utils] Extension {} de {} ignoree : {}", key, c.getProvider().getMetadata().getId(), t.toString());
                }
            }
        } catch (Throwable t) {
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Extensions {} illisibles : {}", key, t.toString());
        }
        return out;
    }

    private static List<String> run(List<Function<Object, List<String>>> list, Object o) {
        List<String> out = new ArrayList<>();
        for (Function<Object, List<String>> f : list) {
            try {
                List<String> l = f.apply(o);
                if (l != null) out.addAll(l);
            } catch (Throwable t) {
                CobbleUtils.LOG.debug("[CobblePulse-Utils] Extension en erreur : {}", t.toString());
            }
        }
        return out;
    }

    public static List<String> pokemonLines(Pokemon p) {
        if (pokemon == null) pokemon = load("cobblepulse:pokemon_lines");
        return pokemon.isEmpty() || p == null ? List.of() : run(pokemon, p);
    }

    public static List<String> itemLines(ItemStack st) {
        if (items == null) items = load("cobblepulse:item_lines");
        return items.isEmpty() || st == null || st.isEmpty() ? List.of() : run(items, st);
    }
}
