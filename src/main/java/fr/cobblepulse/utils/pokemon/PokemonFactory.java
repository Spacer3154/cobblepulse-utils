package fr.cobblepulse.utils.pokemon;

import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.tera.TeraType;
import com.cobblemon.mod.common.api.types.tera.TeraTypes;
import com.cobblemon.mod.common.pokeball.PokeBall;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;

import fr.cobblepulse.utils.core.Reg;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Construit un Pokemon (ou fakemon) de recompense a partir de sa fiche (repris de l'editeur de Battletower). */
public final class PokemonFactory {
    private PokemonFactory() {}

    public static Stats stat(String id) {
        return switch (id) {
            case "hp" -> Stats.HP;
            case "atk" -> Stats.ATTACK;
            case "def" -> Stats.DEFENCE;
            case "spa" -> Stats.SPECIAL_ATTACK;
            case "spd" -> Stats.SPECIAL_DEFENCE;
            default -> Stats.SPEED;
        };
    }

    public static String id(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Genre en anglais Cobblemon (male, female, genderless) depuis le francais ou l'anglais. */
    public static String gender(String g) {
        String k = g == null ? "" : g.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "male", "m", "mâle" -> "male";
            case "female", "femelle", "f" -> "female";
            case "genderless", "asexue", "asexué", "aucun" -> "genderless";
            default -> "";
        };
    }

    private static String ns(String id, String def) {
        if (id == null || id.isBlank()) return "";
        String v = id.trim().toLowerCase(Locale.ROOT);
        return v.contains(":") ? v : def + ":" + v;
    }

    /** Cree le Pokemon decrit (les erreurs sont ajoutees a la liste ; null si l'espece est inconnue). */
    public static Pokemon create(MonSpec spec, List<String> errors) {
        spec.normalize();
        Species species = SpeciesLookup.resolve(spec.species);
        String form = spec.form;
        if (species == null) {
            errors.add("Espèce inconnue : " + spec.species);
            return null;
        }
        StringBuilder props = new StringBuilder("level=").append(spec.level);
        if (!spec.nature.isBlank()) props.append(" nature=").append(id(spec.nature));
        if (!spec.ability.isBlank()) props.append(" ability=").append(id(spec.ability));
        String g = gender(spec.gender);
        if (!g.isEmpty()) props.append(" gender=").append(g);
        props.append(" shiny=").append(spec.shiny ? "yes" : "no");
        Pokemon pk;
        try {
            PokemonProperties pp = PokemonProperties.Companion.parse(props.toString());
            pp.setSpecies(species.getResourceIdentifier().toString());
            pk = pp.create();
            if (pk.getSpecies() != species) {
                pk.setSpecies(species);
                pp.setSpecies(null);
                pp.apply(pk);
            }
        } catch (Throwable t) {
            errors.add("Création impossible (" + spec.species + ") : " + t.getMessage());
            return null;
        }
        if (!form.isBlank()) applyForm(pk, species, form, errors);
        for (String st : MonSpec.STATS) {
            try {
                int iv = spec.iv(st);
                if (iv >= 0) pk.setIV(stat(st), iv);
                pk.setEV(stat(st), spec.ev(st));
            } catch (Throwable t) {
                errors.add("IV/EV invalides : " + t.getMessage());
            }
        }
        int evTotal = 0;
        for (String st : MonSpec.STATS) evTotal += spec.ev(st);
        if (evTotal > 510) errors.add("Total d'EV " + evTotal + " > 510 (accepté, mais hors règles officielles)");
        if (!spec.nickname.isBlank()) pk.setNickname(Component.literal(spec.nickname));
        if (!spec.heldItem.isBlank()) {
            Item item = Reg.item(ns(spec.heldItem, "cobblemon"), Items.AIR);
            if (item == Items.AIR) errors.add("Objet tenu inconnu : " + spec.heldItem);
            else pk.swapHeldItem(new ItemStack(item), false, false);
        }
        if (!spec.ball.isBlank()) {
            try {
                ResourceLocation rl = ResourceLocation.tryParse(ns(spec.ball, "cobblemon"));
                PokeBall ball = rl == null ? null : PokeBalls.getPokeBall(rl);
                if (ball == null) errors.add("Poké Ball inconnue : " + spec.ball);
                else pk.setCaughtBall(ball);
            } catch (Throwable t) {
                errors.add("Poké Ball invalide : " + spec.ball);
            }
        }
        if (!spec.moves.isEmpty()) {
            pk.getMoveSet().clear();
            int slot = 0;
            for (String mid : spec.moves) {
                MoveTemplate tpl = Moves.getByName(id(mid));
                if (tpl == null) {
                    errors.add("Capacité inconnue : " + mid);
                    continue;
                }
                Move mv = tpl.create();
                pk.getMoveSet().setMove(slot++, mv);
                if (slot >= 4) break;
            }
        }
        if (!spec.teraType.isBlank()) {
            try {
                TeraType tt = TeraTypes.get(id(spec.teraType));
                if (tt != null) pk.setTeraType(tt);
                else errors.add("Type Téra inconnu : " + spec.teraType);
            } catch (Throwable t) {
                errors.add("Type Téra invalide : " + spec.teraType);
            }
        }
        pk.heal();
        return pk;
    }

    private static String formKey(String f) {
        String k = id(f);
        return switch (k) {
            case "alolan", "alola" -> "alola";
            case "galarian", "galar" -> "galar";
            case "hisuian", "hisui" -> "hisui";
            case "paldean", "paldea" -> "paldea";
            default -> k;
        };
    }

    private static boolean formMatches(FormData f, String key) {
        if (f == null) return false;
        if (formKey(f.formOnlyShowdownId()).equals(key) || formKey(f.getName()).equals(key)) return true;
        for (String a : f.getAspects()) if (formKey(a).equals(key)) return true;
        return false;
    }

    /** Applique la forme ou les aspects demandes (separes par des virgules). */
    public static void applyForm(Pokemon pk, Species species, String formList, List<String> errors) {
        for (String raw : formList.split(",")) {
            String form = raw.trim();
            if (form.isEmpty()) continue;
            String key = formKey(form);
            if (key.isEmpty() || formMatches(pk.getForm(), key)) continue;
            boolean found = false;
            for (FormData f : species.getForms()) {
                if (!formMatches(f, key)) continue;
                found = true;
                try {
                    Set<String> forced = new HashSet<>(pk.getForcedAspects());
                    forced.addAll(f.getAspects());
                    pk.setForcedAspects(forced);
                    pk.updateAspects();
                    if (!formMatches(pk.getForm(), key)) pk.setForm(f);
                } catch (Throwable t) {
                    errors.add("Forme " + form + " impossible pour " + species.getName() + " : " + t.getMessage());
                }
                break;
            }
            if (!found) {
                // Aspect libre (ex. variante de texture d'un fakemon)
                try {
                    Set<String> forced = new HashSet<>(pk.getForcedAspects());
                    forced.add(form.toLowerCase(Locale.ROOT));
                    pk.setForcedAspects(forced);
                    pk.updateAspects();
                } catch (Throwable t) {
                    errors.add("Forme inconnue pour " + species.getName() + " : " + form);
                }
            }
        }
    }
}
