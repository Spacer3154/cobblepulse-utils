package fr.cobblepulse.utils.config;

import fr.cobblepulse.utils.pokemon.MonSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Recompense generique (butin de Boss, paliers du Pokedex, kits...).
 * type : item, command, money, bp (points de la Tour de Combat), pokemon.
 */
public final class Reward {
    public String type = "item";
    /** item : SNBT complet ou identifiant ("minecraft:diamond 3"). */
    public String item = "";
    /** command : commandes console (%player%, %uuid%). */
    public List<String> commands = new ArrayList<>();
    /** money / bp : montant. */
    public long amount = 0;
    public MonSpec pokemon;
    /** Chance d'obtention en %. */
    public double chance = 100;
    /** Texte affiche dans les apercus (vide = automatique). */
    public String display = "";

    public static Reward item(String snbt, double chance) {
        Reward r = new Reward();
        r.type = "item";
        r.item = snbt;
        r.chance = chance;
        return r;
    }

    public static Reward money(long amount, double chance) {
        Reward r = new Reward();
        r.type = "money";
        r.amount = amount;
        r.chance = chance;
        return r;
    }

    public static Reward bp(long amount, double chance) {
        Reward r = new Reward();
        r.type = "bp";
        r.amount = amount;
        r.chance = chance;
        return r;
    }

    public static Reward command(String cmd, String display, double chance) {
        Reward r = new Reward();
        r.type = "command";
        r.commands = new ArrayList<>(List.of(cmd));
        r.display = display;
        r.chance = chance;
        return r;
    }

    public void normalize() {
        if (type == null || type.isBlank()) type = "item";
        type = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (item == null) item = "";
        if (commands == null) commands = new ArrayList<>();
        if (display == null) display = "";
        if (pokemon != null) pokemon.normalize();
        chance = Math.max(0, Math.min(100, chance));
    }
}
