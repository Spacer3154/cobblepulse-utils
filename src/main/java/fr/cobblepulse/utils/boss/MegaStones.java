package fr.cobblepulse.utils.boss;

import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.pokemon.SpeciesLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Mega-Gemmes de tous les mods, lues dans le registre de Mega Showdown lui-meme.
 * <p>Pour Mega Showdown, une Mega-Gemme est un objet portant deux composants : son type ({@code "mega"}) et
 * l'entree du registre {@code mega_showdown:mega} qu'il active. Cette entree donne les especes concernees et l'aspect
 * applique ({@code mega_evolution=mega_x}). Une gemme ajoutee par un autre mod (Glavenusite de CobblePulse-Ecology...)
 * ou par un datapack suit le meme chemin : elle est reconnue sans liste a tenir a jour.</p>
 * <p>Mega Showdown est optionnel : tout passe par reflexion, et l'index reste vide sans lui.</p>
 */
public final class MegaStones {
    private static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("mega_showdown", "registry_type_component");
    private static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath("mega_showdown", "registry_location_component");
    private static final ResourceLocation MEGA_REGISTRY = ResourceLocation.fromNamespaceAndPath("mega_showdown", "mega");

    /** « espece:forme » (espece normalisee, forme comme Bosses.formKey : mega, mega_x...) → objet. */
    private static volatile Map<String, String> index;

    private MegaStones() {}

    public static void invalidate() {
        index = null;
    }

    /** Mega-Gemme de cette espece et de cette forme (cle « espece:forme »), ou null. */
    public static String find(String species, String formKey) {
        Map<String, String> m = index();
        String sp = SpeciesLookup.norm(species);
        String hit = m.get(sp + ":" + formKey);
        // forme « Mega » d'une espece dont la gemme declare un aspect propre (ex. mega_evolution=mega_glavenus)
        if (hit == null && formKey.equals("mega")) {
            for (Map.Entry<String, String> e : m.entrySet()) if (e.getKey().startsWith(sp + ":mega")) return e.getValue();
        }
        return hit;
    }

    /** Index complet (auto-tests, diagnostic). */
    public static Map<String, String> all() {
        return Collections.unmodifiableMap(index());
    }

    private static Map<String, String> index() {
        Map<String, String> m = index;
        if (m == null) {
            m = build(CobbleUtils.server());
            index = m;
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> build(MinecraftServer server) {
        Map<String, String> out = new LinkedHashMap<>();
        if (server == null) return out;
        DataComponentType<Object> type = (DataComponentType<Object>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(TYPE);
        DataComponentType<Object> location = (DataComponentType<Object>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(LOCATION);
        if (type == null || location == null) return out;
        Optional<? extends Registry<Object>> reg = server.registryAccess().registry(ResourceKey.createRegistryKey(MEGA_REGISTRY));
        if (reg.isEmpty()) return out;
        int stones = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                Object t = item.components().get(type);
                Object loc = item.components().get(location);
                if (!"mega".equals(t) || !(loc instanceof ResourceLocation rl)) continue;
                Object entry = reg.get().get(rl);
                if (entry == null) continue;
                String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
                List<String> species = strings(call(entry, "pokemons"));
                String form = formOf(entry);
                for (String sp : species) {
                    String key = SpeciesLookup.norm(sp) + ":" + form;
                    // objets historiques en double (meme entree) : le premier enregistre est garde, celui du mod principal
                    out.putIfAbsent(key, itemId);
                }
                stones++;
            } catch (Throwable t) {
                CobbleUtils.LOG.debug("[CobblePulse-Utils] Mega-Gemme illisible {} : {}", BuiltInRegistries.ITEM.getKey(item), t.toString());
            }
        }
        CobbleUtils.LOG.info("[CobblePulse-Utils] Mega-Gemmes lues dans Mega Showdown : {} objet(s), {} forme(s)", stones, out.size());
        return out;
    }

    /** Forme activee : valeur de l'aspect « mega_evolution=... » applique (mega, mega_x, mega_y), « mega » par defaut. */
    private static String formOf(Object entry) {
        try {
            Object cond = call(entry, "aspect_conditions");
            Object apply = call(cond, "aspectApply");
            for (String a : strings(call(apply, "aspects"))) {
                int eq = a.indexOf('=');
                if (eq < 0) continue;
                String value = a.substring(eq + 1).trim().toLowerCase(Locale.ROOT).replace('-', '_');
                if (value.startsWith("mega")) return value;
            }
        } catch (Throwable ignored) {
        }
        return "mega";
    }

    private static Object call(Object o, String method) throws ReflectiveOperationException {
        if (o == null) return null;
        Method m = o.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(o);
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Iterable<?> it) for (Object x : it) if (x != null) out.add(x.toString());
        return out;
    }
}
