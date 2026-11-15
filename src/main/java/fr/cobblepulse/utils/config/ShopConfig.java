package fr.cobblepulse.utils.config;

import fr.cobblepulse.utils.pokemon.MonSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Shop Admin : utils/shop.json (reglages) + utils/shop/<categorie>.json (une categorie par fichier).
 * Compatible CobbleDollars et Impactor (voir general.json « economy »).
 */
public final class ShopConfig {
    public boolean enabled = true;
    public boolean allowSell = true;
    /** Promotion globale (%) appliquee a tous les achats. */
    public double globalDiscount = 0;
    public String title = "&8Boutique";

    public static final class Category {
        public String id = "";
        public String name = "";
        public String icon = "minecraft:chest";
        public List<String> lore = new ArrayList<>();
        public boolean enabled = true;
        /** Promotion de la categorie (%). */
        public double discount = 0;
        public int slot = -1;
        public List<Entry> entries = new ArrayList<>();
    }

    public static final class Entry {
        public String id = "";
        /** item, command ou pokemon. */
        public String type = "item";
        /** Objet vendu (SNBT ou identifiant) ; pour command/pokemon : icone optionnelle. */
        public String item = "";
        public String display = "";
        public List<String> lore = new ArrayList<>();
        /** Prix d'achat unitaire (-1 = non achetable). */
        public long buy = -1;
        /** Prix de revente unitaire au serveur (-1 = non revendable). */
        public long sell = -1;
        /** Commandes console pour type=command (%player%, %amount%). */
        public List<String> commands = new ArrayList<>();
        public MonSpec pokemon;
        /** Promotion propre a l'article (%). */
        public double discount = 0;
        public int slot = -1;
    }

    public void normalize() {
        if (title == null) title = "&8Boutique";
        globalDiscount = Math.max(0, Math.min(100, globalDiscount));
    }

    public static void normalize(Category c) {
        if (c.name == null || c.name.isBlank()) c.name = "&f" + c.id;
        if (c.icon == null || c.icon.isBlank()) c.icon = "minecraft:chest";
        if (c.lore == null) c.lore = new ArrayList<>();
        if (c.entries == null) c.entries = new ArrayList<>();
        c.discount = Math.max(0, Math.min(100, c.discount));
        int i = 0;
        for (Entry e : c.entries) {
            if (e.id == null || e.id.isBlank()) e.id = "article_" + i;
            if (e.type == null || e.type.isBlank()) e.type = "item";
            e.type = e.type.toLowerCase(java.util.Locale.ROOT);
            if (e.item == null) e.item = "";
            if (e.display == null) e.display = "";
            if (e.lore == null) e.lore = new ArrayList<>();
            if (e.commands == null) e.commands = new ArrayList<>();
            if (e.pokemon != null) e.pokemon.normalize();
            e.discount = Math.max(0, Math.min(100, e.discount));
            i++;
        }
    }

    private static Entry item(String id, String item, long buy, long sell) {
        Entry e = new Entry();
        e.id = id;
        e.item = item;
        e.buy = buy;
        e.sell = sell;
        return e;
    }

    /** Categories d'exemple creees au premier lancement. */
    public static List<Category> defaults() {
        List<Category> out = new ArrayList<>();
        Category balls = new Category();
        balls.id = "pokeballs";
        balls.name = "&c&lPoké Balls";
        balls.icon = "cobblemon:poke_ball";
        balls.lore = new ArrayList<>(List.of("&7Tout pour capturer."));
        balls.slot = 10;
        balls.entries.add(item("poke_ball", "cobblemon:poke_ball", 100, 25));
        balls.entries.add(item("great_ball", "cobblemon:great_ball", 300, 75));
        balls.entries.add(item("ultra_ball", "cobblemon:ultra_ball", 800, 200));
        balls.entries.add(item("quick_ball", "cobblemon:quick_ball", 1000, -1));
        balls.entries.add(item("luxury_ball", "cobblemon:luxury_ball", 1000, -1));
        out.add(balls);
        Category care = new Category();
        care.id = "soins";
        care.name = "&d&lSoins";
        care.icon = "cobblemon:potion";
        care.lore = new ArrayList<>(List.of("&7Potions, rappels et baies."));
        care.slot = 12;
        care.entries.add(item("potion", "cobblemon:potion", 200, 50));
        care.entries.add(item("super_potion", "cobblemon:super_potion", 600, 150));
        care.entries.add(item("hyper_potion", "cobblemon:hyper_potion", 1200, 300));
        care.entries.add(item("revive", "cobblemon:revive", 1500, 375));
        care.entries.add(item("rare_candy", "cobblemon:rare_candy", 5000, -1));
        out.add(care);
        Category blocks = new Category();
        blocks.id = "ressources";
        blocks.name = "&6&lRessources";
        blocks.icon = "minecraft:diamond";
        blocks.lore = new ArrayList<>(List.of("&7Achetez et revendez vos ressources."));
        blocks.slot = 14;
        blocks.entries.add(item("diamond", "minecraft:diamond", 2000, 400));
        blocks.entries.add(item("iron_ingot", "minecraft:iron_ingot", 150, 30));
        blocks.entries.add(item("gold_ingot", "minecraft:gold_ingot", 250, 50));
        blocks.entries.add(item("oak_log", "minecraft:oak_log", 20, 4));
        blocks.entries.add(item("wheat", "minecraft:wheat", -1, 2));
        out.add(blocks);
        Category special = new Category();
        special.id = "speciaux";
        special.name = "&b&lSpéciaux";
        special.icon = "minecraft:nether_star";
        special.lore = new ArrayList<>(List.of("&7Pokémon et services exclusifs."));
        special.slot = 16;
        Entry mon = new Entry();
        mon.id = "eevee_shiny";
        mon.type = "pokemon";
        mon.display = "&eÉvoli chromatique &7(IV parfaits)";
        mon.buy = 250000;
        mon.pokemon = new MonSpec();
        mon.pokemon.species = "eevee";
        mon.pokemon.level = 10;
        mon.pokemon.shiny = true;
        mon.pokemon.ivs = MonSpec.defaults(31);
        special.entries.add(mon);
        Entry cmd = new Entry();
        cmd.id = "xp";
        cmd.type = "command";
        cmd.item = "minecraft:experience_bottle";
        cmd.display = "&a30 niveaux d'expérience";
        cmd.commands = new ArrayList<>(List.of("experience add %player% 30 levels"));
        cmd.buy = 5000;
        special.entries.add(cmd);
        out.add(special);
        return out;
    }
}
