package fr.cobblepulse.utils.wiki;

import com.cobblemon.mod.common.api.conditional.RegistryLikeCondition;
import com.cobblemon.mod.common.api.conditional.RegistryLikeIdentifierCondition;
import com.cobblemon.mod.common.api.conditional.RegistryLikeTagCondition;
import com.cobblemon.mod.common.api.spawning.BestSpawner;
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools;
import com.cobblemon.mod.common.api.spawning.TimeRange;
import com.cobblemon.mod.common.api.spawning.condition.AreaTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.GroundedTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.BossConfig;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.net.Net;
import fr.cobblepulse.utils.pokemon.SpeciesLookup;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.minecraft.commands.Commands.argument;

/**
 * Cote serveur du Wiki : ouverture de l'ecran client, donnees de spawn (calculees depuis le reservoir de spawn
 * du serveur, datapacks compris) et tables de recompenses des Boss.
 */
public final class WikiService {
    /** (biome|seau|contexte) -> somme des poids. Recalcule quand le reservoir change. */
    private static Map<String, Double> totals;
    private static int totalsFor = -1;

    private WikiService() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "wiki", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p -> open(p, null)))
                .then(argument("pokemon", StringArgumentType.greedyString()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                        com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getImplemented().stream().map(s -> s.getResourceIdentifier().getPath()).toList(), b))
                        .executes(ctx -> Cmd.run(ctx, p -> open(p, StringArgumentType.getString(ctx, "pokemon"))))));
    }

    public static void open(ServerPlayer p, String species) {
        if (!Net.hasClient(p)) {
            Msg.send(p, "wiki.no_client");
            return;
        }
        JsonObject o = new JsonObject();
        if (species != null && !species.isBlank()) {
            Species s = SpeciesLookup.resolve(species);
            if (s == null) {
                Msg.send(p, "wiki.unknown", "pokemon", species);
                return;
            }
            o.addProperty("species", s.getResourceIdentifier().toString());
        }
        Net.send(p, "wiki_open", o.toString());
    }

    private static Map<String, Double> totals() {
        List<SpawnDetail> details = CobblemonSpawnPools.WORLD_SPAWN_POOL.getDetails();
        int sig = System.identityHashCode(details) * 31 + details.size();
        if (totals != null && totalsFor == sig) return totals;
        Map<String, Double> m = new HashMap<>();
        for (SpawnDetail d : details) {
            if (!(d instanceof PokemonSpawnDetail)) continue;
            String ctx = d.getSpawnablePositionType() == null ? "?" : d.getSpawnablePositionType().getName();
            for (ResourceLocation b : d.getValidBiomes()) m.merge(b + "|" + d.getBucket() + "|" + ctx, (double) Math.max(0, d.getWeight()), Double::sum);
        }
        totals = m;
        totalsFor = sig;
        return m;
    }

    private static String modName(String namespace) {
        if ("minecraft".equals(namespace)) return "Minecraft";
        Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(namespace);
        if (mc.isPresent()) return mc.get().getMetadata().getName();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            if (c.getMetadata().getId().replace("-", "").replace("_", "").equalsIgnoreCase(namespace.replace("_", ""))) return c.getMetadata().getName();
        }
        String n = namespace.replace('_', ' ');
        return n.isEmpty() ? namespace : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String cond(RegistryLikeCondition<?> c) {
        if (c instanceof RegistryLikeIdentifierCondition<?> id) return id.getIdentifier().toString();
        if (c instanceof RegistryLikeTagCondition<?> tag) return "#" + tag.getTag().location();
        return String.valueOf(c);
    }

    private static String timeName(TimeRange tr) {
        if (tr == null) return "";
        for (Map.Entry<String, TimeRange> e : TimeRange.Companion.getTimeRanges().entrySet()) if (e.getValue().equals(tr)) return e.getKey();
        return "custom";
    }

    /** Donnees de spawn d'une espece, envoyees au Wiki client. */
    public static void sendSpecies(ServerPlayer p, String speciesId) {
        Species s = SpeciesLookup.resolve(speciesId);
        if (s == null) return;
        Net.send(p, "wiki_species", spawnData(s).toString());
    }

    public static JsonObject spawnData(Species s) {
        JsonObject out = new JsonObject();
        out.addProperty("species", s.getResourceIdentifier().toString());
        Map<String, Float> buckets = BestSpawner.INSTANCE.getConfig().getWorldBuckets();
        double bucketSum = 0;
        for (float w : buckets.values()) bucketSum += w;
        Map<String, Double> tot = totals();
        JsonArray entries = new JsonArray();
        for (SpawnDetail d : CobblemonSpawnPools.WORLD_SPAWN_POOL.getDetails()) {
            if (!(d instanceof PokemonSpawnDetail pd)) continue;
            String sp = pd.getPokemon().getSpecies();
            if (sp == null || SpeciesLookup.resolve(sp) != s) continue;
            String ctx = d.getSpawnablePositionType() == null ? "?" : d.getSpawnablePositionType().getName();
            double bucketShare = bucketSum <= 0 ? 0 : buckets.getOrDefault(d.getBucket(), 0f) / bucketSum;
            JsonObject cond = conditions(d);
            Map<String, JsonArray> byMod = new LinkedHashMap<>();
            for (ResourceLocation b : d.getValidBiomes()) {
                double total = tot.getOrDefault(b + "|" + d.getBucket() + "|" + ctx, 0.0);
                double share = d.getPercentage() > 0 ? d.getPercentage() / 100.0 : total <= 0 ? 0 : d.getWeight() / total;
                JsonObject be = new JsonObject();
                be.addProperty("id", b.toString());
                be.addProperty("percent", Math.round(bucketShare * share * 10000) / 100.0);
                byMod.computeIfAbsent(modName(b.getNamespace()), k -> new JsonArray()).add(be);
            }
            JsonObject e = new JsonObject();
            e.addProperty("bucket", d.getBucket());
            e.addProperty("context", ctx);
            e.addProperty("levels", pd.getLevelRange() == null ? "" : pd.getLevelRange().getFirst() + "-" + pd.getLevelRange().getLast());
            e.addProperty("aspects", pd.getPokemon().getAspects() == null ? "" : String.join(",", pd.getPokemon().getAspects()));
            e.add("conditions", cond);
            JsonObject mods = new JsonObject();
            for (Map.Entry<String, JsonArray> m : byMod.entrySet()) mods.add(m.getKey(), m.getValue());
            e.add("biomes", mods);
            entries.add(e);
        }
        out.add("spawns", entries);
        return out;
    }

    private static JsonObject conditions(SpawnDetail d) {
        JsonObject o = new JsonObject();
        List<String> times = new ArrayList<>();
        List<String> weather = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        List<String> structures = new ArrayList<>();
        Float minY = null;
        Float maxY = null;
        Boolean sky = null;
        Integer minLight = null;
        Integer maxLight = null;
        for (SpawningCondition<?> c : d.getConditions()) {
            String t = timeName(c.getTimeRange());
            if (!t.isEmpty() && !times.contains(t)) times.add(t);
            if (Boolean.TRUE.equals(c.isThundering())) weather.add("thunder");
            else if (Boolean.TRUE.equals(c.isRaining())) weather.add("rain");
            else if (Boolean.FALSE.equals(c.isRaining())) weather.add("clear");
            if (c.getMinY() != null) minY = minY == null ? c.getMinY() : Math.min(minY, c.getMinY());
            if (c.getMaxY() != null) maxY = maxY == null ? c.getMaxY() : Math.max(maxY, c.getMaxY());
            if (c.getCanSeeSky() != null) sky = c.getCanSeeSky();
            if (c.getMinLight() != null) minLight = c.getMinLight();
            if (c.getMaxLight() != null) maxLight = c.getMaxLight();
            if (c instanceof GroundedTypeSpawningCondition<?> g && g.getNeededBaseBlocks() != null) {
                for (RegistryLikeCondition<?> b : g.getNeededBaseBlocks()) blocks.add(cond(b));
            }
            if (c instanceof AreaTypeSpawningCondition<?> a && a.getNeededNearbyBlocks() != null) {
                for (RegistryLikeCondition<?> b : a.getNeededNearbyBlocks()) blocks.add(cond(b));
            }
            if (c.getStructures() != null) {
                for (var e : c.getStructures()) structures.add(e.map(ResourceLocation::toString, tk -> "#" + tk.location()));
            }
        }
        o.add("time", toArray(times));
        o.add("weather", toArray(weather));
        o.add("blocks", toArray(blocks));
        o.add("structures", toArray(structures));
        if (minY != null) o.addProperty("min_y", minY.intValue());
        if (maxY != null) o.addProperty("max_y", maxY.intValue());
        if (sky != null) o.addProperty("sky", sky);
        if (minLight != null) o.addProperty("min_light", minLight);
        if (maxLight != null) o.addProperty("max_light", maxLight);
        return o;
    }

    private static JsonArray toArray(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }

    /** Onglet Boss : paliers et tables de recompenses (consultation publique). */
    public static void sendBosses(ServerPlayer p) {
        JsonObject o = new JsonObject();
        JsonArray tiers = new JsonArray();
        for (String id : Cfg.boss.tiers.keySet()) {
            BossConfig.Tier t = Cfg.boss.tiers.get(id);
            JsonObject j = new JsonObject();
            j.addProperty("id", id);
            j.addProperty("name", t.color + t.name);
            j.addProperty("levels", t.levelMin + "-" + t.levelMax);
            j.addProperty("ivs", t.perfectIvs);
            j.addProperty("icon", t.icon);
            j.addProperty("cooldown", t.engageCooldownSeconds);
            JsonArray loot = new JsonArray();
            for (Reward r : t.loot) {
                JsonObject l = new JsonObject();
                l.addProperty("text", TextUtil.strip(Rewards.describe(r)));
                l.addProperty("chance", r.chance);
                loot.add(l);
            }
            j.add("loot", loot);
            tiers.add(j);
        }
        o.add("tiers", tiers);
        o.addProperty("form_rule", Msg.format("wiki.boss_form_rule", "first", BossEditorFmt.fmt(Cfg.boss.formItemFirstChance),
                "repeat", BossEditorFmt.fmt(Cfg.boss.formItemRepeatChance)));
        o.addProperty("enabled", Cfg.boss.naturalSpawn);
        Net.send(p, "wiki_bosses", o.toString());
    }

    static final class BossEditorFmt {
        static String fmt(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.FRANCE, "%.1f", d);
        }
    }

    static {
        CobbleUtils.LOG.debug("[CobblePulse-Utils] Wiki pret");
    }
}
