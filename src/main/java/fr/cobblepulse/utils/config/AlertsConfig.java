package fr.cobblepulse.utils.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** utils/alerts.json : annonces et historique des spawns rares. */
public final class AlertsConfig {
    public boolean enabled = true;
    /** Nombre de spawns conserves par categorie dans l'historique. */
    public int historySize = 10;
    /** Rayon de recherche du joueur le plus proche (blocs). */
    public double nearestPlayerRadius = 256;
    /** Ignorer les apparitions non naturelles (commandes, appats...). */
    public boolean naturalOnly = false;
    /** Annonce quand un joueur capture un Pokemon rare annonce. */
    public boolean announceCapture = true;
    /** Annonce quand un joueur met K.O. en combat un Pokemon rare annonce. */
    public boolean announceDefeat = true;
    /** Annonce quand un joueur tue (hors combat) un Pokemon rare annonce. */
    public boolean announceKill = true;
    public Map<String, Category> categories = new LinkedHashMap<>();

    public static final class Category {
        public boolean enabled = true;
        public String label = "";
        public String color = "&f";
        public String icon = "minecraft:paper";
        public boolean sound = true;

        public Category() {}

        Category(String label, String color, String icon) {
            this.label = label;
            this.color = color;
            this.icon = icon;
        }
    }

    public void normalize() {
        if (categories == null) categories = new LinkedHashMap<>();
        Map<String, Category> def = new LinkedHashMap<>();
        def.put("shiny", new Category("Chromatique", "&e", "minecraft:glowstone_dust"));
        def.put("paradox", new Category("Paradoxe", "&5", "minecraft:echo_shard"));
        def.put("ultra_beast", new Category("Ultra-Chimère", "&3", "minecraft:ender_eye"));
        def.put("legendary", new Category("Légendaire", "&6", "minecraft:nether_star"));
        def.put("mythical", new Category("Fabuleux", "&d", "minecraft:amethyst_shard"));
        for (Map.Entry<String, Category> e : def.entrySet()) categories.putIfAbsent(e.getKey(), e.getValue());
        historySize = Math.max(1, Math.min(54, historySize));
    }
}
