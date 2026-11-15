package fr.cobblepulse.utils.wiki;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution;
import com.cobblemon.mod.common.api.pokemon.moves.Learnset;
import com.cobblemon.mod.common.api.pokemon.requirement.Requirement;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.spawning.TimeRange;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.evolution.variants.BlockClickEvolution;
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution;
import com.cobblemon.mod.common.pokemon.evolution.variants.LevelUpEvolution;
import com.cobblemon.mod.common.pokemon.evolution.variants.TradeEvolution;
import com.cobblemon.mod.common.pokemon.requirements.BiomeRequirement;
import com.cobblemon.mod.common.pokemon.requirements.FriendshipRequirement;
import com.cobblemon.mod.common.pokemon.requirements.HeldItemRequirement;
import com.cobblemon.mod.common.pokemon.requirements.LevelRequirement;
import com.cobblemon.mod.common.pokemon.requirements.MoveSetRequirement;
import com.cobblemon.mod.common.pokemon.requirements.TimeRangeRequirement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.pokemon.SpeciesLookup;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

/** Fiche technique d'une espece pour le Wiki (cles de traduction, resolues par le client). */
final class SpeciesInfo {
    private SpeciesInfo() {}

    static String key(net.minecraft.network.chat.Component c) {
        return c.getContents() instanceof TranslatableContents tc ? tc.getKey() : c.getString();
    }

    static JsonObject build(Species s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.getResourceIdentifier().toString());
        o.addProperty("name_key", key(s.getTranslatedName()));
        o.addProperty("name", s.getName());
        o.addProperty("dex", s.getNationalPokedexNumber());
        o.addProperty("height", s.getHeight() / 10.0);
        o.addProperty("weight", s.getWeight() / 10.0);
        o.addProperty("catch_rate", s.getCatchRate());
        o.addProperty("male_ratio", s.getMaleRatio());
        JsonArray labels = new JsonArray();
        for (String l : s.getLabels()) labels.add(l);
        o.add("labels", labels);
        JsonArray eggs = new JsonArray();
        s.getEggGroups().forEach(e -> eggs.add(e.name().toLowerCase(java.util.Locale.ROOT)));
        o.add("egg_groups", eggs);
        JsonArray dex = new JsonArray();
        for (String line : s.getPokedex()) dex.add(line);
        o.add("pokedex", dex);
        JsonArray forms = new JsonArray();
        for (FormData f : s.getForms()) forms.add(form(f));
        o.add("forms", forms);
        o.add("base", form(s.getStandardForm()));
        PreEvolution pre = s.getPreEvolution();
        if (pre != null) {
            JsonObject pe = new JsonObject();
            pe.addProperty("species", pre.getSpecies().getResourceIdentifier().toString());
            pe.addProperty("name_key", key(pre.getSpecies().getTranslatedName()));
            o.add("pre_evolution", pe);
        }
        return o;
    }

    private static JsonObject form(FormData f) {
        JsonObject o = new JsonObject();
        o.addProperty("name", f.getName());
        JsonArray aspects = new JsonArray();
        for (String a : f.getAspects()) aspects.add(a);
        o.add("aspects", aspects);
        JsonArray types = new JsonArray();
        f.getTypes().forEach(t -> types.add(t.getName()));
        o.add("types", types);
        JsonObject stats = new JsonObject();
        Map<Stat, Integer> base = f.getBaseStats();
        for (int i = 0; i < PokemonText.STATS.length; i++) {
            Integer v = base.get(PokemonText.STATS[i]);
            stats.addProperty(PokemonText.STAT_KEYS[i], v == null ? 0 : v);
        }
        o.add("stats", stats);
        JsonArray abilities = new JsonArray();
        for (PotentialAbility a : f.getAbilities()) {
            JsonObject ab = new JsonObject();
            ab.addProperty("key", a.getTemplate().getDisplayName());
            ab.addProperty("desc", a.getTemplate().getDescription());
            ab.addProperty("hidden", a.getPriority() == Priority.LOW || a.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("hidden"));
            abilities.add(ab);
        }
        o.add("abilities", abilities);
        o.add("evolutions", evolutions(f));
        o.add("moves", moves(f.getMoves()));
        return o;
    }

    private static JsonArray evolutions(FormData f) {
        JsonArray out = new JsonArray();
        for (Evolution e : f.getEvolutions()) {
            JsonObject j = new JsonObject();
            String res = e.getResult().getSpecies();
            Species rs = res == null ? null : SpeciesLookup.resolve(res);
            j.addProperty("species", rs == null ? String.valueOf(res) : rs.getResourceIdentifier().toString());
            j.addProperty("name_key", rs == null ? String.valueOf(res) : key(rs.getTranslatedName()));
            j.addProperty("method", e instanceof LevelUpEvolution ? "level" : e instanceof ItemInteractionEvolution ? "item"
                    : e instanceof TradeEvolution ? "trade" : e instanceof BlockClickEvolution ? "block" : "other");
            if (e instanceof ItemInteractionEvolution ie) j.addProperty("item", itemOf(ie.getRequiredContext()));
            JsonArray req = new JsonArray();
            for (Requirement r : e.getRequirements()) req.add(requirement(r));
            j.add("requirements", req);
            out.add(j);
        }
        return out;
    }

    private static String itemOf(ItemPredicate p) {
        if (p == null) return "";
        try {
            if (p.items().isPresent()) {
                for (Holder<Item> h : p.items().get()) return BuiltInRegistries.ITEM.getKey(h.value()).toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static JsonObject requirement(Requirement r) {
        JsonObject j = new JsonObject();
        if (r instanceof LevelRequirement lr) {
            j.addProperty("type", "level");
            j.addProperty("value", lr.getMinLevel());
        } else if (r instanceof FriendshipRequirement fr) {
            j.addProperty("type", "friendship");
            j.addProperty("value", fr.getAmount());
        } else if (r instanceof HeldItemRequirement hr) {
            j.addProperty("type", "held_item");
            j.addProperty("value", itemOf(hr.getItemCondition()));
        } else if (r instanceof TimeRangeRequirement tr) {
            j.addProperty("type", "time");
            String name = "custom";
            for (Map.Entry<String, TimeRange> e : TimeRange.Companion.getTimeRanges().entrySet()) if (e.getValue().equals(tr.getRange())) name = e.getKey();
            j.addProperty("value", name);
        } else if (r instanceof BiomeRequirement br) {
            j.addProperty("type", "biome");
            j.addProperty("value", br.getBiomeCondition() == null ? "" : String.valueOf(br.getBiomeCondition()));
        } else if (r instanceof MoveSetRequirement mr) {
            j.addProperty("type", "move");
            j.addProperty("value", mr.getMove() == null ? "" : mr.getMove().getName());
        } else {
            j.addProperty("type", "other");
            j.addProperty("value", r.getClass().getSimpleName().replace("Requirement", ""));
        }
        return j;
    }

    private static JsonObject moves(Learnset l) {
        JsonObject o = new JsonObject();
        JsonArray lvl = new JsonArray();
        for (Map.Entry<Integer, List<MoveTemplate>> e : new java.util.TreeMap<>(l.getLevelUpMoves()).entrySet()) {
            for (MoveTemplate m : e.getValue()) {
                JsonObject j = move(m);
                j.addProperty("level", e.getKey());
                lvl.add(j);
            }
        }
        o.add("level", lvl);
        o.add("tm", list(l.getTmMoves()));
        o.add("egg", list(l.getEggMoves()));
        o.add("tutor", list(l.getTutorMoves()));
        return o;
    }

    private static JsonArray list(List<MoveTemplate> l) {
        JsonArray a = new JsonArray();
        for (MoveTemplate m : l) a.add(move(m));
        return a;
    }

    private static JsonObject move(MoveTemplate m) {
        JsonObject j = new JsonObject();
        j.addProperty("key", key(m.getDisplayName()));
        j.addProperty("name", m.getName());
        j.addProperty("type", m.getElementalType().getName());
        j.addProperty("category", m.getDamageCategory().getName());
        j.addProperty("power", (int) m.getPower());
        j.addProperty("accuracy", (int) m.getAccuracy());
        j.addProperty("pp", m.getPp());
        return j;
    }
}
