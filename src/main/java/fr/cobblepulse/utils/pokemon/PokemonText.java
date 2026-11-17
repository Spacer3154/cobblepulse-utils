package fr.cobblepulse.utils.pokemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.TextUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Affichage et serialisation des Pokemon (chat, infobulles, icones de menus, base de donnees). */
public final class PokemonText {
    public static final Stat[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};
    public static final String[] STAT_KEYS = {"hp", "atk", "def", "spa", "spd", "spe"};

    private PokemonText() {}

    public static PlayerPartyStore party(ServerPlayer p) {
        return Cobblemon.INSTANCE.getStorage().getParty(p);
    }

    // ---------------------------------------------------------------- base de donnees

    public static String toJson(Pokemon pk) {
        return pk.saveToJSON(CobbleUtils.server().registryAccess(), new JsonObject()).toString();
    }

    public static Pokemon fromJson(String json) {
        try {
            return new Pokemon().loadFromJSON(CobbleUtils.server().registryAccess(), JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Pokemon illisible : {}", e.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- textes

    public static String formName(Pokemon pk) {
        String f = pk.getForm().getName();
        return f == null || f.equalsIgnoreCase("normal") || f.isBlank() ? "" : fr.cobblepulse.utils.boss.Bosses.formLabel(f);
    }

    public static MutableComponent speciesName(Pokemon pk) {
        MutableComponent c = fr.cobblepulse.utils.pokemon.SpeciesNames.of(pk.getSpecies()).copy();
        String f = formName(pk);
        if (!f.isEmpty()) c.append(Component.literal(" (" + f + ")"));
        return c;
    }

    public static String gender(Pokemon pk) {
        Gender g = pk.getGender();
        return g == Gender.MALE ? "&b♂" : g == Gender.FEMALE ? "&d♀" : "";
    }

    /** "Pikachu ★ Nv.25 ♂" */
    public static MutableComponent title(Pokemon pk) {
        MutableComponent c = Component.empty();
        MutableComponent name = pk.getNickname() != null ? pk.getNickname().copy() : speciesName(pk);
        c.append(name.withStyle(pk.getShiny() ? ChatFormatting.GOLD : ChatFormatting.WHITE));
        if (pk.getShiny()) c.append(Component.literal(" ★").withStyle(ChatFormatting.YELLOW));
        c.append(TextUtil.legacy(" &7Nv." + pk.getLevel() + " " + gender(pk)));
        return c;
    }

    public static int ivTotal(Pokemon pk) {
        int t = 0;
        for (Stat s : STATS) {
            Integer v = pk.getIvs().get(s);
            t += v == null ? 0 : v;
        }
        return t;
    }

    public static int perfectIvs(Pokemon pk) {
        int n = 0;
        for (Stat s : STATS) {
            Integer v = pk.getIvs().get(s);
            if (v != null && v >= 31) n++;
        }
        return n;
    }

    private static String stats(Pokemon pk, boolean ivs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < STATS.length; i++) {
            Integer v = ivs ? pk.getIvs().get(STATS[i]) : pk.getEvs().get(STATS[i]);
            if (i > 0) b.append("&8/");
            int val = v == null ? 0 : v;
            b.append(ivs && val >= 31 ? "&a" : "&f").append(val);
        }
        return b.toString();
    }

    /** Lignes de description (legacy) utilisees dans les infobulles et les lores de menus. */
    public static List<String> lines(Pokemon pk) {
        List<String> l = new ArrayList<>();
        String types = Names.typeName(pk.getForm().getPrimaryType().getName())
                + (pk.getForm().getSecondaryType() != null ? " / " + Names.typeName(pk.getForm().getSecondaryType().getName()) : "");
        l.add("&7Espèce : &f" + fr.cobblepulse.utils.pokemon.SpeciesNames.text(pk.getSpecies()) + (formName(pk).isEmpty() ? "" : " &8(" + formName(pk) + ")")
                + " &8#" + pk.getSpecies().getNationalPokedexNumber());
        l.add("&7Types : &f" + types);
        l.add("&7Niveau : &f" + pk.getLevel() + (pk.getShiny() ? "  &e★ Chromatique" : ""));
        l.add("&7Nature : &f" + Names.nature(pk.getNature().getName().getPath()));
        l.add("&7Talent : &f" + Component.translatable(pk.getAbility().getDisplayName()).getString());
        l.add("&7IV : " + stats(pk, true) + " &8(" + ivTotal(pk) + "/186)");
        l.add("&7EV : " + stats(pk, false));
        List<String> moves = new ArrayList<>();
        for (Move m : pk.getMoveSet().getMoves()) moves.add(m.getDisplayName().getString());
        if (!moves.isEmpty()) l.add("&7Capacités : &f" + String.join(", ", moves));
        ItemStack held = pk.heldItem();
        if (!held.isEmpty()) l.add("&7Objet tenu : &f" + held.getHoverName().getString());
        try {
            l.add("&7Ball : &f" + pk.getCaughtBall().item().getDescription().getString());
        } catch (Throwable ignored) {
        }
        String ot = pk.getOriginalTrainerName();
        if (ot != null && !ot.isBlank()) l.add("&7Dresseur d'origine : &f" + ot);
        Set<String> labels = pk.getSpecies().getLabels();
        List<String> tags = new ArrayList<>();
        if (labels.contains("legendary")) tags.add("&6Légendaire");
        if (labels.contains("mythical")) tags.add("&dFabuleux");
        if (labels.contains("ultra_beast")) tags.add("&3Ultra-Chimère");
        if (labels.contains("paradox")) tags.add("&5Paradoxe");
        if (!tags.isEmpty()) l.add(String.join(" &8| ", tags));
        return l;
    }

    public static Component hover(Pokemon pk) {
        MutableComponent c = Component.empty().append(title(pk));
        for (String line : lines(pk)) c.append(Component.literal("\n")).append(TextUtil.legacy(line));
        return c;
    }

    /** Nom cliquable avec infobulle complete (/pokeshout). */
    public static MutableComponent chat(Pokemon pk) {
        MutableComponent c = Component.literal("[").withStyle(ChatFormatting.DARK_GRAY);
        c.append(title(pk));
        c.append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
        Component hover = hover(pk);
        return c.withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    /** Icone de menu (modele du Pokemon) avec description. */
    public static ItemStack icon(Pokemon pk, String... extra) {
        ItemStack base;
        try {
            base = PokemonItem.Companion.from(pk);
        } catch (Throwable t) {
            base = new ItemStack(net.minecraft.world.item.Items.EGG);
        }
        ItemBuilder b = ItemBuilder.of(base).name(title(pk));
        b.lore(lines(pk));
        if (extra.length > 0) {
            b.lore("");
            b.lore(extra);
        }
        return b.build();
    }

    public static boolean isRare(Pokemon pk) {
        Set<String> l = pk.getSpecies().getLabels();
        return pk.getShiny() || l.contains("legendary") || l.contains("mythical") || l.contains("ultra_beast") || l.contains("paradox");
    }

    /** Categorie de prix plancher GTS. */
    public static String category(Pokemon pk) {
        Set<String> l = pk.getSpecies().getLabels();
        if (l.contains("mythical")) return "mythical";
        if (l.contains("legendary")) return "legendary";
        if (l.contains("ultra_beast")) return "ultra_beast";
        if (l.contains("paradox")) return "paradox";
        if (pk.getShiny()) return "shiny";
        return "pokemon";
    }

    public static String speciesKey(Pokemon pk) {
        return SpeciesLookup.ref(pk.getSpecies());
    }

    public static boolean listed(List<String> list, Pokemon pk) {
        if (list == null || list.isEmpty()) return false;
        String path = pk.getSpecies().getResourceIdentifier().getPath();
        String full = pk.getSpecies().getResourceIdentifier().toString();
        String name = SpeciesLookup.norm(pk.getSpecies().getName());
        for (String s : list) {
            if (s == null) continue;
            String v = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (v.equals(path) || v.equals(full) || SpeciesLookup.norm(v).equals(name)) return true;
            if (v.startsWith("#") && pk.getSpecies().getLabels().contains(v.substring(1))) return true;
        }
        return false;
    }
}
