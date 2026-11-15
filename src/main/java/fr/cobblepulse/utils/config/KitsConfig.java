package fr.cobblepulse.utils.config;

import java.util.ArrayList;
import java.util.List;

/** utils/kits.json : kits filtres par permission (cobblepulse.kit.<nom>), editables en jeu. */
public final class KitsConfig {
    public List<Kit> kits = new ArrayList<>();

    public static final class Kit {
        public String name = "";
        public String display = "";
        public String icon = "minecraft:chest";
        public List<String> lore = new ArrayList<>();
        /** Objets (SNBT complet : enchantements, noms, NBT de mods conserves). */
        public List<String> items = new ArrayList<>();
        /** Commandes console executees a la recuperation (%player%). */
        public List<String> commands = new ArrayList<>();
        /** Delai entre deux recuperations (s). 0 = aucun. */
        public long cooldownSeconds = 86400;
        /** Recuperable une seule fois. */
        public boolean oneTime = false;
        /** true : disponible sans le noeud cobblepulse.kit.<nom>. */
        public boolean defaultAccess = false;
        public int slot = -1;
    }

    public Kit find(String name) {
        if (name == null) return null;
        for (Kit k : kits) if (k.name.equalsIgnoreCase(name)) return k;
        return null;
    }

    public static KitsConfig defaults() {
        KitsConfig c = new KitsConfig();
        Kit start = new Kit();
        start.name = "depart";
        start.display = "&a&lKit de départ";
        start.icon = "cobblemon:poke_ball";
        start.lore = new ArrayList<>(List.of("&7Pour bien commencer l'aventure !"));
        start.items = new ArrayList<>(List.of("cobblemon:poke_ball 16", "cobblemon:potion 5", "minecraft:bread 16", "minecraft:stone_sword"));
        start.oneTime = true;
        start.cooldownSeconds = 0;
        start.defaultAccess = true;
        c.kits.add(start);
        Kit daily = new Kit();
        daily.name = "quotidien";
        daily.display = "&e&lKit quotidien";
        daily.icon = "cobblemon:great_ball";
        daily.lore = new ArrayList<>(List.of("&7Une fois par jour."));
        daily.items = new ArrayList<>(List.of("cobblemon:great_ball 8", "cobblemon:super_potion 3", "cobblemon:rare_candy 1"));
        daily.cooldownSeconds = 86400;
        daily.defaultAccess = true;
        c.kits.add(daily);
        Kit vip = new Kit();
        vip.name = "vip";
        vip.display = "&6&lKit VIP";
        vip.icon = "cobblemon:ultra_ball";
        vip.lore = new ArrayList<>(List.of("&7Réservé aux grades VIP.", "&8Permission : cobblepulse.kit.vip"));
        vip.items = new ArrayList<>(List.of("cobblemon:ultra_ball 16", "cobblemon:hyper_potion 5", "cobblemon:rare_candy 3", "minecraft:golden_apple 4"));
        vip.cooldownSeconds = 7 * 86400;
        c.kits.add(vip);
        return c;
    }

    public void normalize() {
        if (kits == null) kits = new ArrayList<>();
        kits.removeIf(k -> k == null || k.name == null || k.name.isBlank());
        for (Kit k : kits) {
            k.name = k.name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
            if (k.display == null || k.display.isBlank()) k.display = "&b" + k.name;
            if (k.icon == null || k.icon.isBlank()) k.icon = "minecraft:chest";
            if (k.lore == null) k.lore = new ArrayList<>();
            if (k.items == null) k.items = new ArrayList<>();
            if (k.commands == null) k.commands = new ArrayList<>();
            k.cooldownSeconds = Math.max(0, k.cooldownSeconds);
        }
    }
}
