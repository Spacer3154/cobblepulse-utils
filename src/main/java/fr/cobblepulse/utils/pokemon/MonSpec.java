package fr.cobblepulse.utils.pokemon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pokemon (ou fakemon) personnalise offert en recompense. */
public final class MonSpec {
    public static final List<String> STATS = List.of("hp", "atk", "def", "spa", "spd", "spe");

    /** Espece : nom (pikachu), identifiant complet (monpack:volt_mon) ou nom affiche. */
    public String species = "pikachu";
    /** Forme / aspects separes par des virgules (alolan, galarian, wash, rapid-strike...). */
    public String form = "";
    public String nickname = "";
    public int level = 5;
    /** Vide = aleatoire. */
    public String nature = "";
    /** Vide = talent normal aleatoire. */
    public String ability = "";
    /** Vide = aleatoire ; male, female ou genderless. */
    public String gender = "";
    public boolean shiny;
    public String heldItem = "";
    /** Poke Ball dans laquelle le Pokemon est remis (vide = Poke Ball). */
    public String ball = "";
    /** IV : -1 = aleatoire. */
    public Map<String, Integer> ivs = defaults(-1);
    public Map<String, Integer> evs = defaults(0);
    /** Jusqu'a 4 capacites (vide = capacites normales du niveau). */
    public List<String> moves = new ArrayList<>();
    /** Type Tera (vide = normal). */
    public String teraType = "";

    public static Map<String, Integer> defaults(int v) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String s : STATS) m.put(s, v);
        return m;
    }

    public int iv(String stat) {
        Integer v = ivs == null ? null : ivs.get(stat);
        if (v == null || v < 0) return -1;
        return Math.min(31, v);
    }

    public int ev(String stat) {
        Integer v = evs == null ? null : evs.get(stat);
        return v == null ? 0 : Math.max(0, Math.min(252, v));
    }

    public void normalize() {
        if (species == null) species = "";
        if (form == null) form = "";
        if (nickname == null) nickname = "";
        if (nature == null) nature = "";
        if (ability == null) ability = "";
        if (gender == null) gender = "";
        if (heldItem == null) heldItem = "";
        if (ball == null) ball = "";
        if (teraType == null) teraType = "";
        level = Math.max(1, Math.min(250, level));
        Map<String, Integer> iv = defaults(-1);
        Map<String, Integer> ev = defaults(0);
        for (String s : STATS) {
            iv.put(s, iv(s));
            ev.put(s, ev(s));
        }
        ivs = iv;
        evs = ev;
        if (moves == null) moves = new ArrayList<>();
        moves.removeIf(m -> m == null || m.isBlank());
        while (moves.size() > 4) moves.remove(moves.size() - 1);
    }
}
