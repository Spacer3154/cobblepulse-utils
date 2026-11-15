package fr.cobblepulse.utils.config;

import fr.cobblepulse.utils.core.Loc;

import java.util.ArrayList;
import java.util.List;

/** utils/warps.json : point d'apparition (/setspawn) et warps publics du staff. */
public final class WarpsConfig {
    public Loc spawn;
    public List<Warp> warps = new ArrayList<>();

    public static final class Warp {
        /** Identifiant (minuscules) : /warp <name>, noeud cobblepulse.warp.<name>. */
        public String name = "";
        public String display = "";
        /** Objet « embleme » du bouton (ex. minecraft:compass pour le Spawn). */
        public String icon = "minecraft:compass";
        public List<String> lore = new ArrayList<>();
        public Loc loc;
        /** true : warp prive, visible uniquement avec cobblepulse.warp.<name> (ou cobblepulse.warp.*). */
        public boolean restricted = false;
        /** Emplacement dans le menu (-1 = automatique). */
        public int slot = -1;
    }

    public Warp find(String name) {
        if (name == null) return null;
        for (Warp w : warps) if (w.name.equalsIgnoreCase(name)) return w;
        return null;
    }

    public void normalize() {
        if (warps == null) warps = new ArrayList<>();
        warps.removeIf(w -> w == null || w.name == null || w.name.isBlank() || w.loc == null);
        for (Warp w : warps) {
            w.name = w.name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
            if (w.display == null || w.display.isBlank()) w.display = "&b" + w.name;
            if (w.icon == null || w.icon.isBlank()) w.icon = "minecraft:compass";
            if (w.lore == null) w.lore = new ArrayList<>();
        }
    }
}
