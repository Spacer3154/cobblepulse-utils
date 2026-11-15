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
