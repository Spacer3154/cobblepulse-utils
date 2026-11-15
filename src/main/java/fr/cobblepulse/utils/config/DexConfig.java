package fr.cobblepulse.utils.config;

import java.util.ArrayList;
import java.util.List;

/** utils/dexrewards.json : paliers de completion du Pokedex (especes capturees). */
public final class DexConfig {
    public boolean enabled = true;
    /** Ne compter que les especes implementees (avec modele). */
    public boolean onlyImplemented = true;
    /** Compter aussi les fakemons de datapacks. */
    public boolean includeFakemons = true;
    public List<Tier> tiers = new ArrayList<>();

    public static final class Tier {
        public String id = "";
        public String name = "";
        /** Seuil : pourcentage (mode "percent") ou nombre d'especes (mode "count"). */
        public String mode = "percent";
        public double required = 10;
        public String icon = "minecraft:book";
        public List<String> lore = new ArrayList<>();
        public List<Reward> rewards = new ArrayList<>();
    }

    public static DexConfig defaults() {
        DexConfig c = new DexConfig();
        Object[][] t = {
                {"p5", "&a5 % du Pokédex", 5.0, "cobblemon:poke_ball", 1500L, "cobblemon:great_ball 10"},
                {"p10", "&a10 % du Pokédex", 10.0, "cobblemon:great_ball", 3000L, "cobblemon:ultra_ball 5"},
                {"p25", "&e25 % du Pokédex", 25.0, "cobblemon:ultra_ball", 7500L, "cobblemon:rare_candy 5"},
                {"p50", "&650 % du Pokédex", 50.0, "cobblemon:quick_ball", 15000L, "cobblemon:ability_capsule 1"},
                {"p75", "&c75 % du Pokédex", 75.0, "cobblemon:luxury_ball", 30000L, "cobblemon:ability_patch 1"},
                {"p100", "&d&l100 % du Pokédex", 100.0, "cobblemon:master_ball", 100000L, "cobblemon:master_ball 1"}};
        for (Object[] r : t) {
            Tier tier = new Tier();
            tier.id = (String) r[0];
            tier.name = (String) r[1];
            tier.required = (Double) r[2];
            tier.icon = (String) r[3];
            tier.rewards.add(Reward.money((Long) r[4], 100));
            tier.rewards.add(Reward.item((String) r[5], 100));
            c.tiers.add(tier);
        }
        return c;
    }

    public void normalize() {
        if (tiers == null) tiers = new ArrayList<>();
        tiers.removeIf(t -> t == null || t.id == null || t.id.isBlank());
        for (Tier t : tiers) {
            if (t.name == null || t.name.isBlank()) t.name = t.id;
            if (t.mode == null || !(t.mode.equals("count") || t.mode.equals("percent"))) t.mode = "percent";
            if (t.icon == null || t.icon.isBlank()) t.icon = "minecraft:book";
            if (t.lore == null) t.lore = new ArrayList<>();
            if (t.rewards == null) t.rewards = new ArrayList<>();
            t.rewards.forEach(Reward::normalize);
        }
    }
}
