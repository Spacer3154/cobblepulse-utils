package fr.cobblepulse.utils.config;

import fr.cobblepulse.utils.core.Loc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * worlds.json : reglages par monde / dimension (vanilla, mods de dimensions, mondes crees par un mod multimonde).
 * Les mondes charges par le serveur sont ajoutes automatiquement avec des valeurs prudentes.
 */
public final class WorldsConfig {
    /** Monde principal : /spawn y renvoie (spawn global de warps.json). */
    public String mainWorld = "minecraft:overworld";
    /** true : /spawn renvoie au spawn du monde ou se trouve le joueur (s'il est defini), sinon au spawn principal. */
    public boolean spawnInCurrentWorld = false;
    public Map<String, World> worlds = new LinkedHashMap<>();

    public static final class World {
        /** Nom affiche (codes couleur acceptes). */
        public String display = "";
        public String icon = "minecraft:grass_block";
        public List<String> lore = new ArrayList<>();
        /** Visible dans le menu Mondes (teleportation au spawn du monde) et via /monde. */
        public boolean listed = false;
        /** true : acces au menu / a /monde uniquement avec cobblepulse.world.<id> (ex. cobblepulse.world.minecraft.the_nether). */
        public boolean restricted = false;
        /** Homes : ALL (tous), PERMISSION (cobblepulse.home.multidimension), NONE (interdits). */
        public String homes = "PERMISSION";
        /** /rtp autorise dans ce monde, rayon et centre (centre absent = point d'apparition du monde). */
        public boolean rtp = false;
        public int rtpMinRadius = 300;
        public int rtpMaxRadius = 3000;
        public Integer rtpCenterX;
        public Integer rtpCenterZ;
        /** /tpa et /tpahere peuvent amener un joueur dans ce monde. */
        public boolean tpa = true;
        /** Promotion des spawns naturels en Boss. */
        public boolean bosses = true;
        /** Annonce des spawns rares (shiny, legendaires...). */
        public boolean alerts = true;
        /** Point d'apparition du monde (/setspawn dans ce monde). Absent : calcule (spawn du monde, plate-forme de l'End...). */
        public Loc spawn;
        /** Ordre dans le menu Mondes. */
        public int order = 100;
    }

    public static WorldsConfig defaults() {
        WorldsConfig c = new WorldsConfig();
        World ow = new World();
        ow.display = "&a&lMonde principal";
        ow.icon = "minecraft:grass_block";
        ow.lore.add("&7Le monde de l'aventure.");
        ow.listed = true;
        ow.homes = "ALL";
        ow.rtp = true;
        ow.order = 0;
        c.worlds.put("minecraft:overworld", ow);
        World nether = new World();
        nether.display = "&c&lNether";
        nether.icon = "minecraft:netherrack";
        nether.lore.add("&7Chaleur et lave à perte de vue.");
        nether.rtpMinRadius = 100;
        nether.rtpMaxRadius = 1000;
        nether.order = 10;
        c.worlds.put("minecraft:the_nether", nether);
        World end = new World();
        end.display = "&5&lEnd";
        end.icon = "minecraft:end_stone";
        end.lore.add("&7Les îles flottantes de l'Ender.");
        end.rtpMinRadius = 1000;
        end.rtpMaxRadius = 3000;
        end.order = 20;
        c.worlds.put("minecraft:the_end", end);
        return c;
    }

    public void normalize() {
        if (worlds == null) worlds = new LinkedHashMap<>();
        if (mainWorld == null || mainWorld.isBlank()) mainWorld = "minecraft:overworld";
        for (World w : worlds.values()) {
            if (w.lore == null) w.lore = new ArrayList<>();
            if (w.display == null) w.display = "";
            if (w.icon == null || w.icon.isBlank()) w.icon = "minecraft:grass_block";
            if (w.homes == null) w.homes = "PERMISSION";
            w.homes = w.homes.toUpperCase(java.util.Locale.ROOT);
            if (!w.homes.equals("ALL") && !w.homes.equals("NONE")) w.homes = "PERMISSION";
            w.rtpMinRadius = Math.max(0, w.rtpMinRadius);
            w.rtpMaxRadius = Math.max(w.rtpMinRadius + 1, w.rtpMaxRadius);
        }
    }
}
