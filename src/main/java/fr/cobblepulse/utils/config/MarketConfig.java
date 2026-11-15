package fr.cobblepulse.utils.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** utils/gts.json, utils/sts.json et utils/wondertrade.json. */
public final class MarketConfig {
    private MarketConfig() {}

    public static final class Gts {
        public boolean enabled = true;
        /** Valeurs utilisees si le joueur n'a aucun noeud cobblepulse.gts.tax/limit/duration.N. */
        public int defaultTax = 5;
        public int defaultLimit = 5;
        public int defaultDurationHours = 48;
        public long maxPrice = 100_000_000L;
        /** Prix planchers par categorie : items, pokemon, shiny, legendary, mythical, ultra_beast, paradox. */
        public Map<String, Long> priceFloors = new LinkedHashMap<>();
        public List<String> blacklistItems = new ArrayList<>();
        public List<String> blacklistSpecies = new ArrayList<>();
        /** Autoriser la vente de Pokemon marques non echangeables. */
        public boolean allowUntradeable = false;
        /** Garder au moins un Pokemon dans l'equipe. */
        public boolean keepOnePartyMember = true;
        public boolean broadcastNewListings = true;
        public long broadcastMinPrice = 1000;

        public void normalize() {
            if (priceFloors == null) priceFloors = new LinkedHashMap<>();
            Map<String, Long> def = new LinkedHashMap<>();
            def.put("items", 1L);
            def.put("pokemon", 100L);
            def.put("shiny", 2500L);
            def.put("legendary", 25000L);
            def.put("mythical", 25000L);
            def.put("ultra_beast", 10000L);
            def.put("paradox", 10000L);
            for (Map.Entry<String, Long> e : def.entrySet()) priceFloors.putIfAbsent(e.getKey(), e.getValue());
            if (blacklistItems == null) blacklistItems = new ArrayList<>();
            if (blacklistSpecies == null) blacklistSpecies = new ArrayList<>();
            defaultTax = Math.max(0, Math.min(100, defaultTax));
            defaultLimit = Math.max(0, defaultLimit);
            defaultDurationHours = Math.max(1, defaultDurationHours);
        }
    }

    public static final class Sts {
        public boolean enabled = true;
        /** Quota de Pokemon revendables par 24 h si aucun noeud cobblepulse.sts.limit.N. */
        public int defaultDailyLimit = 10;
        public long basePrice = 50;
        public long perLevel = 4;
        /** Bonus par IV parfait (31). */
        public long perPerfectIv = 25;
        /** Bonus par point de stats de base au-dela de 300. */
        public double perBstPoint = 0.5;
        public double shinyMultiplier = 5;
        public double legendaryMultiplier = 8;
        public double mythicalMultiplier = 8;
        public double ultraBeastMultiplier = 4;
        public double paradoxMultiplier = 4;
        /** Prix fixes par espece (ex. "pikachu": 500). */
        public Map<String, Long> speciesPrices = new LinkedHashMap<>();
        public List<String> blacklistSpecies = new ArrayList<>();
        public boolean keepOnePartyMember = true;

        public void normalize() {
            if (speciesPrices == null) speciesPrices = new LinkedHashMap<>();
            if (blacklistSpecies == null) blacklistSpecies = new ArrayList<>();
            defaultDailyLimit = Math.max(0, defaultDailyLimit);
        }
    }

    public static final class WonderTrade {
        public boolean enabled = true;
        /** Taille du reservoir (complete automatiquement avec des Pokemon aleatoires). */
        public int poolSize = 72;
        public long cooldownSeconds = 600;
        public boolean allowShiny = true;
        public boolean allowLegendary = false;
        public boolean allowUntradeable = false;
        public int minLevel = 1;
        public List<String> blacklistSpecies = new ArrayList<>();
        /** Pokemon generes pour remplir le reservoir. */
        public int generatedLevelMin = 5;
        public int generatedLevelMax = 35;
        /** Chance (%) qu'un Pokemon genere soit chromatique. */
        public double generatedShinyChance = 0.5;
        public boolean generatedAllowLegendary = false;
        /** Annonce quand un chromatique / legendaire entre dans le reservoir. */
        public boolean broadcastRareDeposits = true;
        public boolean keepOnePartyMember = true;

        public void normalize() {
            poolSize = Math.max(1, Math.min(1000, poolSize));
            if (blacklistSpecies == null) blacklistSpecies = new ArrayList<>();
            generatedLevelMin = Math.max(1, generatedLevelMin);
            generatedLevelMax = Math.max(generatedLevelMin, generatedLevelMax);
        }
    }
}
