package fr.cobblepulse.utils.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** utils/general.json : reglages communs (economie, teleportation, chat, enderchest, passerelles du Hub). */
public final class GeneralConfig {
    /** Niveau d'operateur equivalent aux noeuds admin quand LuckPerms est absent ou muet. */
    public int opLevel = 2;
    /** AUTO, COBBLEDOLLARS ou IMPACTOR. */
    public String economy = "COBBLEDOLLARS";
    /** Format d'affichage des montants. */
    public String moneyFormat = "%amount% ₽";
    /** Menus en ecrans CobblePulse pour les joueurs qui ont le mod (sinon, et pour les autres : menus coffre). */
    public boolean clientScreens = true;
    /**
     * Langue des noms d'especes ecrits par le serveur et des noms de secours des Fakemons (fichiers de langue des
     * datapacks et des mods), par exemple fr_fr ou en_us.
     */
    public String language = "fr_fr";

    public Teleport teleport = new Teleport();
    public Chat chat = new Chat();
    public Enderchest enderchest = new Enderchest();
    public Misc misc = new Misc();
    /** Passerelles du Hub vers les autres mods CobblePulse. */
    public Map<String, Gateway> gateways = new LinkedHashMap<>();

    public static final class Teleport {
        /** Compte a rebours avant teleportation (s), annule si le joueur bouge. */
        public int delaySeconds = 3;
        public boolean cancelOnMove = true;
        public int tpaTimeoutSeconds = 60;
        public int tpaCooldownSeconds = 10;
        public int backCooldownSeconds = 0;
        public int rtpCooldownSeconds = 300;
        public int rtpAttempts = 16;
        public int homeDefaultLimit = 3;
        public int jumpMaxDistance = 120;
        /** Sans LuckPerms : /back vers le lieu de mort autorise pour tous. */
        public boolean backOnDeathDefault = true;
    }

    public static final class Chat {
        public int nickMaxLength = 16;
        /** Prefixe ajoute devant les surnoms (ex. "~"). */
        public String nickPrefix = "~";
        public boolean ignoreBlocksPublicChat = true;
    }

    public static final class Enderchest {
        public int defaultRows = 3;
        public int defaultPages = 1;
        /** Le clic sur un coffre de l'Ender ouvre l'Enderchest evolutif. */
        public boolean replaceVanilla = true;
        /** Importe une fois le contenu du coffre de l'Ender vanilla dans la page 1. */
        public boolean importVanilla = true;
    }

    public static final class Misc {
        public int pokehealCooldownSeconds = 300;
        public int trashRows = 4;
        public int repairAllCooldownSeconds = 0;
        public int afkPlaytime = 0;
    }

    public static final class Gateway {
        public boolean enabled = true;
        /** Identifiant du mod requis (la tuile est masquee s'il est absent). */
        public String mod = "";
        /** Commande executee par le joueur (sans /). Valeurs speciales : @crates (liste des caisses), @clans (menu Clans). */
        public String command = "";
        public String name = "";
        public String icon = "minecraft:paper";
        public List<String> lore = new ArrayList<>();

        public Gateway() {}

        Gateway(String mod, String command, String name, String icon, String... lore) {
            this.mod = mod;
            this.command = command;
            this.name = name;
            this.icon = icon;
            this.lore = new ArrayList<>(List.of(lore));
        }
    }

    public void normalize() {
        if (opLevel < 0 || opLevel > 4) opLevel = 2;
        if (economy == null || economy.isBlank()) economy = "AUTO";
        economy = economy.trim().toUpperCase(java.util.Locale.ROOT);
        if (moneyFormat == null || !moneyFormat.contains("%amount%")) moneyFormat = "%amount% ₽";
        if (teleport == null) teleport = new Teleport();
        teleport.rtpAttempts = Math.max(1, Math.min(64, teleport.rtpAttempts));
        teleport.delaySeconds = Math.max(0, teleport.delaySeconds);
        if (chat == null) chat = new Chat();
        if (chat.nickPrefix == null) chat.nickPrefix = "";
        if (enderchest == null) enderchest = new Enderchest();
        enderchest.defaultRows = Math.max(1, Math.min(6, enderchest.defaultRows));
        enderchest.defaultPages = Math.max(1, Math.min(5, enderchest.defaultPages));
        if (misc == null) misc = new Misc();
        misc.trashRows = Math.max(1, Math.min(6, misc.trashRows));
        if (gateways == null) gateways = new LinkedHashMap<>();
        Map<String, Gateway> def = new LinkedHashMap<>();
        def.put("battletower", new Gateway("cobblepulse_battletower", "battletower", "&6&lTour de Combat", "minecraft:netherite_sword",
                "&7Grimpez les étages de la Tour", "&7et gagnez des BP."));
        def.put("crates", new Gateway("cobblepulse_crates", "@crates", "&d&lCaisses & Clés", "minecraft:chest",
                "&7Aperçu des caisses mystères", "&7et de leurs récompenses."));
        def.put("battlepass", new Gateway("cobblepulse_battlepass", "passe", "&e&lPasse de Combat", "minecraft:nether_star",
                "&7Paliers, quêtes et récompenses", "&7de la saison."));
        def.put("voeux_adn", new Gateway("cobblepulse_crates", "cpbuilder", "&b&lVœux ADN", "minecraft:amethyst_shard",
                "&7Remodelez vos Pokémon", "&7avec vos Vœux ADN."));
        def.put("clans", new Gateway("cobblepulse", "@clans", "&a&lClans, Ligue & Tournois", "minecraft:white_banner",
                "&7Votre clan, la Ligue,", "&7les tournois et votre carte de dresseur."));
        for (Map.Entry<String, Gateway> e : def.entrySet()) gateways.putIfAbsent(e.getKey(), e.getValue());
        for (Gateway g : gateways.values()) {
            if (g.lore == null) g.lore = new ArrayList<>();
            if (g.command == null) g.command = "";
            if (g.mod == null) g.mod = "";
            if (g.name == null) g.name = "";
            if (g.icon == null) g.icon = "minecraft:paper";
        }
    }
}
