package fr.cobblepulse.utils.config;

import java.util.ArrayList;
import java.util.List;

/** utils/playtimerewards.json : recompenses de temps de jeu (paliers cumules + recompense repetable). */
public final class PlaytimeConfig {
    public boolean enabled = true;
    /** Au-dela de ce nombre de minutes sans bouger ni ecrire, le temps ne compte plus (0 = toujours compter). */
    public int afkMinutes = 5;
    /** true : les paliers sont remis automatiquement ; false : a reclamer dans le menu (/ptr). */
    public boolean autoClaim = false;
    /** Paliers de temps de jeu actif cumule. */
    public List<Tier> tiers = new ArrayList<>();
    /** Recompense donnee automatiquement toutes les N minutes de jeu actif. */
    public Repeat repeat = new Repeat();

    public static final class Tier {
        public String id = "";
        public String name = "";
        /** Temps de jeu actif requis (minutes). */
        public long minutes = 60;
        public String icon = "minecraft:clock";
        public List<String> lore = new ArrayList<>();
        /** Noeud LuckPerms requis (vide = tous les joueurs). */
        public String permission = "";
        public List<Reward> rewards = new ArrayList<>();
    }

    public static final class Repeat {
        public boolean enabled = true;
        public String name = "&e&lBonus d'activité";
        public long everyMinutes = 60;
        public String icon = "minecraft:sunflower";
        public List<Reward> rewards = new ArrayList<>();
    }

    public static PlaytimeConfig defaults() {
        PlaytimeConfig c = new PlaytimeConfig();
        Object[][] t = {
                {"h1", "&a1 heure de jeu", 60L, "minecraft:clock", 1000L, "cobblemon:poke_ball 10"},
                {"h5", "&a5 heures de jeu", 300L, "cobblemon:great_ball", 3000L, "cobblemon:great_ball 10"},
                {"h10", "&e10 heures de jeu", 600L, "cobblemon:ultra_ball", 6000L, "cobblemon:ultra_ball 10"},
                {"h24", "&624 heures de jeu", 1440L, "cobblemon:rare_candy", 12000L, "cobblemon:rare_candy 5"},
                {"h50", "&c50 heures de jeu", 3000L, "cobblemon:ability_capsule", 25000L, "cobblemon:ability_capsule 1"},
                {"h100", "&d&l100 heures de jeu", 6000L, "cobblemon:master_ball", 50000L, "cobblemon:master_ball 1"}};
        for (Object[] r : t) {
            Tier tier = new Tier();
            tier.id = (String) r[0];
            tier.name = (String) r[1];
            tier.minutes = (Long) r[2];
            tier.icon = (String) r[3];
            tier.rewards.add(Reward.money((Long) r[4], 100));
            tier.rewards.add(Reward.item((String) r[5], 100));
            c.tiers.add(tier);
        }
        c.repeat.rewards.add(Reward.money(250, 100));
        c.repeat.rewards.add(Reward.item("cobblemon:exp_candy_s 2", 50));
        return c;
    }

    public void normalize() {
        if (tiers == null) tiers = new ArrayList<>();
        tiers.removeIf(t -> t == null || t.id == null || t.id.isBlank());
        for (Tier t : tiers) {
            if (t.name == null || t.name.isBlank()) t.name = t.id;
            if (t.icon == null || t.icon.isBlank()) t.icon = "minecraft:clock";
            if (t.lore == null) t.lore = new ArrayList<>();
            if (t.permission == null) t.permission = "";
            if (t.rewards == null) t.rewards = new ArrayList<>();
            t.minutes = Math.max(1, t.minutes);
            t.rewards.forEach(Reward::normalize);
        }
        tiers.sort(java.util.Comparator.comparingLong(t -> t.minutes));
        if (repeat == null) repeat = new Repeat();
        if (repeat.rewards == null) repeat.rewards = new ArrayList<>();
        repeat.rewards.forEach(Reward::normalize);
        if (repeat.name == null) repeat.name = "";
        if (repeat.icon == null || repeat.icon.isBlank()) repeat.icon = "minecraft:sunflower";
        repeat.everyMinutes = Math.max(1, repeat.everyMinutes);
        afkMinutes = Math.max(0, afkMinutes);
    }
}
