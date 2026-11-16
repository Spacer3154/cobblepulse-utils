package fr.cobblepulse.utils.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * utils/boss.json : Boss PVE (promotion des spawns naturels, 6 raretes + 3 paliers de Mega-Boss, butin,
 * regle Mega / Primo / Origine).
 */
public final class BossConfig {
    /** Spawn naturel des Boss (aussi /cp boss toggle). */
    public boolean naturalSpawn = true;
    /** Chance (%) qu'un Pokemon sauvage apparaissant naturellement soit promu Boss. */
    public double promotionChance = 1.0;
    /** Nombre maximal de Boss sauvages actifs en meme temps. */
    public int maxActive = 12;
    /** Duree de vie d'un Boss sans combat (minutes). */
    public int lifetimeMinutes = 30;
    /** Les legendaires / fabuleux / Ultra-Chimeres peuvent-ils etre promus. */
    public boolean promoteLegendaries = false;
    public List<String> blacklistSpecies = new ArrayList<>();
    /**
     * Chance (%) qu'un Pokemon promu dont l'espece a une Mega-Evolution ou une Primo-Resurgence devienne un Mega-Boss
     * (paliers « mega »), et qu'un Boss ordinaire prenne sa forme Originelle quand elle existe.
     */
    public double specialFormChance = 35;
    /** Fenetre de butin apres une victoire (recuperer, lacher ou jeter les objets). false : objets remis directement. */
    public boolean lootWindow = true;
    /** Butin garanti de l'objet de forme : 100 % a la 1re victoire du joueur, puis ce pourcentage. */
    public double formItemFirstChance = 100;
    public double formItemRepeatChance = 10;
    /** Objet associe a une forme : "espece:forme" -> objet. Les Mega-Gemmes sont deduites automatiquement. */
    public Map<String, String> formItems = new LinkedHashMap<>();
    /** Commande pour donner des BP de la Tour de Combat (%player%, %amount%). */
    public String bpCommand = "battletower admin bp give %player% %amount%";
    public Map<String, Tier> tiers = new LinkedHashMap<>();

    public static final class Tier {
        public String name = "";
        /** Couleur (code &). */
        public String color = "&f";
        /** Poids de tirage parmi les Boss promus. */
        public double weight = 10;
        public int levelMin = 30;
        public int levelMax = 40;
        public float scale = 1.3f;
        /** Nombre d'IV parfaits garantis (0-6). */
        public int perfectIvs = 1;
        /** Niveau de l'IA de combat (0-5, 5 = la plus forte). */
        public int aiSkill = 3;
        public boolean glow = true;
        /** Annonce globale a l'apparition. */
        public boolean announce = false;
        /** Delai personnel entre deux engagements (s), contournable avec cobblepulse.boss.bypasslimit. */
        public long engageCooldownSeconds = 0;
        /** Nombre de tirages dans la table de butin (chaque entree a sa propre chance). */
        public List<Reward> loot = new ArrayList<>();
        public String icon = "minecraft:iron_sword";
        /** Mega-Boss : toujours en Mega-Evolution ou en Primo-Resurgence (especes qui en ont une). Tirage a part. */
        public boolean mega = false;
    }

    public static final List<String> TIER_ORDER = List.of("commun", "non_commun", "rare", "epique", "legendaire", "ultime",
            "mega_epique", "mega_legendaire", "mega_ultime");

    private static Tier mega(Tier t) {
        t.mega = true;
        return t;
    }

    private static Tier tier(String name, String color, double w, int min, int max, float scale, int ivs, int ai, boolean announce,
                             long cooldown, String icon, long money, long bp, String... items) {
        Tier t = new Tier();
        t.name = name;
        t.color = color;
        t.weight = w;
        t.levelMin = min;
        t.levelMax = max;
        t.scale = scale;
        t.perfectIvs = ivs;
        t.aiSkill = ai;
        t.announce = announce;
        t.engageCooldownSeconds = cooldown;
        t.icon = icon;
        if (money > 0) t.loot.add(Reward.money(money, 100));
        if (bp > 0) t.loot.add(Reward.bp(bp, 50));
        for (String it : items) {
            String[] parts = it.split("@");
            t.loot.add(Reward.item(parts[0], parts.length > 1 ? Double.parseDouble(parts[1]) : 100));
        }
        return t;
    }

    public void normalize() {
        if (blacklistSpecies == null) blacklistSpecies = new ArrayList<>();
        if (formItems == null) formItems = new LinkedHashMap<>();
        Map<String, String> fi = new LinkedHashMap<>();
        fi.put("kyogre:primal", "mega_showdown:blue_orb");
        fi.put("groudon:primal", "mega_showdown:red_orb");
        fi.put("dialga:origin", "mega_showdown:adamant_crystal");
        fi.put("palkia:origin", "mega_showdown:lustrous_globe");
        fi.put("giratina:origin", "mega_showdown:griseous_core");
        for (Map.Entry<String, String> e : fi.entrySet()) formItems.putIfAbsent(e.getKey(), e.getValue());
        if (tiers == null) tiers = new LinkedHashMap<>();
        Map<String, Tier> def = new LinkedHashMap<>();
        def.put("commun", tier("Commun", "&f", 50, 25, 40, 1.25f, 1, 1, false, 0, "minecraft:iron_sword", 500, 0,
                "cobblemon:great_ball 3", "cobblemon:potion 2@60"));
        def.put("non_commun", tier("Non-commun", "&a", 25, 40, 55, 1.35f, 2, 2, false, 0, "minecraft:golden_sword", 1200, 2,
                "cobblemon:ultra_ball 3", "cobblemon:super_potion 2@60", "cobblemon:rare_candy 1@25"));
        def.put("rare", tier("Rare", "&9", 13, 55, 70, 1.5f, 3, 3, false, 0, "minecraft:diamond_sword", 2500, 5,
                "cobblemon:ultra_ball 5", "cobblemon:rare_candy 2@50", "cobblemon:hyper_potion 2@50"));
        def.put("epique", tier("Épique", "&5", 7, 70, 85, 1.7f, 4, 4, true, 0, "minecraft:netherite_sword", 5000, 10,
                "cobblemon:rare_candy 3", "cobblemon:ability_capsule 1@30", "cobblemon:exp_candy_xl 2@40"));
        def.put("legendaire", tier("Légendaire", "&6", 4, 85, 100, 1.9f, 5, 5, true, 0, "minecraft:totem_of_undying", 10000, 20,
                "cobblemon:rare_candy 5", "cobblemon:ability_patch 1@25", "cobblemon:ability_capsule 1@30"));
        def.put("ultime", tier("Ultime", "&c&l", 1, 110, 140, 2.2f, 6, 5, true, 3600, "minecraft:dragon_egg", 25000, 50,
                "cobblemon:rare_candy 10", "cobblemon:master_ball 1@10", "cobblemon:ability_patch 1@40"));
        // Mega-Boss : poids compares entre eux seulement (60 / 30 / 10)
        def.put("mega_epique", mega(tier("Méga Épique", "&d&l", 60, 75, 90, 1.8f, 4, 4, true, 0, "minecraft:amethyst_cluster", 8000, 15,
                "cobblemon:rare_candy 4", "cobblemon:ability_capsule 1@40", "cobblemon:exp_candy_xl 3@50")));
        def.put("mega_legendaire", mega(tier("Méga Légendaire", "&6&l", 30, 90, 100, 2.0f, 5, 5, true, 0, "minecraft:nether_star", 15000, 30,
                "cobblemon:rare_candy 6", "cobblemon:ability_patch 1@35", "cobblemon:ability_capsule 1@40", "cobblemon:master_ball 1@5")));
        def.put("mega_ultime", mega(tier("Méga Ultime", "&4&l", 10, 120, 150, 2.4f, 6, 5, true, 3600, "minecraft:end_crystal", 40000, 75,
                "cobblemon:rare_candy 12", "cobblemon:master_ball 1@15", "cobblemon:ability_patch 1@50", "cobblemon:exp_candy_xl 5@60")));
        for (String k : TIER_ORDER) tiers.putIfAbsent(k, def.get(k));
        for (Tier t : tiers.values()) {
            if (t.loot == null) t.loot = new ArrayList<>();
            t.loot.forEach(Reward::normalize);
            t.levelMin = Math.max(1, Math.min(250, t.levelMin));
            t.levelMax = Math.max(t.levelMin, Math.min(250, t.levelMax));
            t.perfectIvs = Math.max(0, Math.min(6, t.perfectIvs));
            t.aiSkill = Math.max(0, Math.min(5, t.aiSkill));
            t.scale = Math.max(0.5f, Math.min(5f, t.scale));
            if (t.color == null) t.color = "&f";
            if (t.name == null || t.name.isBlank()) t.name = "Boss";
            if (t.icon == null || t.icon.isBlank()) t.icon = "minecraft:iron_sword";
        }
        promotionChance = Math.max(0, Math.min(100, promotionChance));
        maxActive = Math.max(0, maxActive);
        lifetimeMinutes = Math.max(1, lifetimeMinutes);
    }
}
