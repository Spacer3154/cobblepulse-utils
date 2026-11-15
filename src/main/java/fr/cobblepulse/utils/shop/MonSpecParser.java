package fr.cobblepulse.utils.shop;

import fr.cobblepulse.utils.pokemon.MonSpec;
import fr.cobblepulse.utils.pokemon.SpeciesLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fiche Pokemon saisie dans le chat (editeurs) :
 * {@code pikachu niveau=50 shiny ivs=31,31,31,31,31,31 evs=0,252,0,0,4,252 nature=timid talent=static objet=light_ball
 * capacites=thunderbolt,quick_attack forme=alolan ball=ultra_ball tera=fire surnom=Pika}
 */
public final class MonSpecParser {
    private MonSpecParser() {}

    public static MonSpec parse(String txt) {
        if (txt == null || txt.isBlank()) return null;
        String[] tokens = txt.trim().split("\\s+");
        MonSpec m = new MonSpec();
        m.species = tokens[0];
        if (SpeciesLookup.resolve(m.species) == null) return null;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            String k = t.contains("=") ? t.substring(0, t.indexOf('=')).toLowerCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT);
            String v = t.contains("=") ? t.substring(t.indexOf('=') + 1) : "";
            switch (k) {
                case "shiny", "chromatique" -> m.shiny = v.isEmpty() || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("oui");
                case "lvl", "level", "niveau", "nv" -> m.level = parseInt(v, 5);
                case "nature" -> m.nature = v;
                case "ability", "talent" -> m.ability = v;
                case "held", "objet", "item" -> m.heldItem = v;
                case "ball" -> m.ball = v;
                case "form", "forme" -> m.form = v;
                case "tera" -> m.teraType = v;
                case "gender", "genre" -> m.gender = v;
                case "nick", "surnom" -> m.nickname = v.replace('_', ' ');
                case "moves", "capacites", "capacités" -> {
                    List<String> l = new ArrayList<>();
                    for (String s : v.split(",")) if (!s.isBlank()) l.add(s.trim());
                    m.moves = l;
                }
                case "ivs", "iv" -> stats(m.ivs, v, 31);
                case "evs", "ev" -> stats(m.evs, v, 252);
                default -> {
                }
            }
        }
        m.normalize();
        return m;
    }

    /** Fiche texte d'un MonSpec (inverse de {@link #parse}). */
    public static String format(MonSpec m) {
        if (m == null) return "";
        StringBuilder b = new StringBuilder(m.species);
        b.append(" niveau=").append(m.level);
        if (m.shiny) b.append(" shiny");
        add(b, "forme", m.form.replace(' ', '-'));
        if (m.ivs.values().stream().anyMatch(v -> v >= 0)) {
            b.append(" ivs=");
            for (int i = 0; i < MonSpec.STATS.size(); i++) b.append(i > 0 ? "," : "").append(m.iv(MonSpec.STATS.get(i)) < 0 ? 0 : m.iv(MonSpec.STATS.get(i)));
        }
        if (m.evs.values().stream().anyMatch(v -> v > 0)) {
            b.append(" evs=");
            for (int i = 0; i < MonSpec.STATS.size(); i++) b.append(i > 0 ? "," : "").append(m.ev(MonSpec.STATS.get(i)));
        }
        add(b, "nature", m.nature);
        add(b, "talent", m.ability);
        add(b, "genre", m.gender);
        add(b, "ball", m.ball);
        add(b, "objet", m.heldItem);
        add(b, "tera", m.teraType);
        add(b, "surnom", m.nickname.replace(' ', '_'));
        if (!m.moves.isEmpty()) add(b, "capacites", String.join(",", m.moves));
        return b.toString();
    }

    private static void add(StringBuilder b, String k, String v) {
        if (v != null && !v.isBlank()) b.append(' ').append(k).append('=').append(v.trim());
    }

    /** Copie d'un Pokemon de l'equipe (espece, forme, niveau, chromatique, IV/EV, nature, talent, capacites...). */
    public static MonSpec of(com.cobblemon.mod.common.pokemon.Pokemon pk) {
        MonSpec m = new MonSpec();
        var id = pk.getSpecies().getResourceIdentifier();
        m.species = id.getNamespace().equals("cobblemon") ? id.getPath() : id.toString();
        m.level = pk.getLevel();
        m.shiny = pk.getShiny();
        if (pk.getForm() != pk.getSpecies().getStandardForm()) m.form = pk.getForm().formOnlyShowdownId();
        m.nature = pk.getNature().getName().getPath();
        m.ability = pk.getAbility().getName();
        m.gender = pk.getGender().name().toLowerCase(Locale.ROOT);
        m.ball = pk.getCaughtBall().getName().getPath();
        if (!pk.heldItem().isEmpty()) m.heldItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(pk.heldItem().getItem()).toString();
        com.cobblemon.mod.common.api.pokemon.stats.Stat[] stats = fr.cobblepulse.utils.pokemon.PokemonText.STATS;
        for (int i = 0; i < stats.length && i < MonSpec.STATS.size(); i++) {
            Integer iv = pk.getIvs().get(stats[i]);
            Integer ev = pk.getEvs().get(stats[i]);
            m.ivs.put(MonSpec.STATS.get(i), iv == null ? -1 : iv);
            m.evs.put(MonSpec.STATS.get(i), ev == null ? 0 : ev);
        }
        for (var mv : pk.getMoveSet().getMoves()) m.moves.add(mv.getTemplate().getName());
        if (pk.getNickname() != null) m.nickname = pk.getNickname().getString();
        try {
            m.teraType = pk.getTeraType().showdownId();
        } catch (Throwable ignored) {
        }
        m.normalize();
        return m;
    }

    private static void stats(java.util.Map<String, Integer> map, String v, int max) {
        String[] parts = v.split(",");
        for (int i = 0; i < parts.length && i < MonSpec.STATS.size(); i++) {
            map.put(MonSpec.STATS.get(i), Math.max(-1, Math.min(max, parseInt(parts[i], -1))));
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
