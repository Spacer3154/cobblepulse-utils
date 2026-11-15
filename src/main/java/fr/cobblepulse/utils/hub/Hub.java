package fr.cobblepulse.utils.hub;

import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.alerts.SpawnAlerts;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.GeneralConfig;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.dex.DexRewards;
import fr.cobblepulse.utils.enderchest.Enderchests;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.kits.Kits;
import fr.cobblepulse.utils.market.GtsMenus;
import fr.cobblepulse.utils.market.StsMenus;
import fr.cobblepulse.utils.market.WonderTradeMenus;
import fr.cobblepulse.utils.net.Net;
import fr.cobblepulse.utils.shop.ShopMenus;
import fr.cobblepulse.utils.staff.StaffPanel;
import fr.cobblepulse.utils.teleport.WarpMenus;
import fr.cobblepulse.utils.wiki.WikiService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hub Central (/menu, /cp, touche du client) : outils joueurs (pole 1), passerelles CobblePulse (pole 2), panneau Staff (pole 3).
 * Avec le mod cote client : ecran dedie ; sinon : menu coffre equivalent.
 */
public final class Hub {
    /** Tuile du Hub (envoyee au client en JSON). */
    public static final class Tile {
        public String id;
        public int section;
        public String name;
        public String icon;
        public List<String> lore = new ArrayList<>();
    }

    private record Internal(String id, String command, String icon) {}

    private static final List<Internal> INTERNAL = List.of(
            new Internal("wiki", "wiki", "cobblemon:pokedex_red"),
            new Internal("warps", "warp", "minecraft:compass"),
            new Internal("shop", "shop", "minecraft:emerald"),
            new Internal("gts", "gts", "minecraft:gold_ingot"),
            new Internal("sts", "sts", "minecraft:hopper"),
            new Internal("wondertrade", "wondertrade", "minecraft:ender_eye"),
            new Internal("kits", "kit", "minecraft:chest_minecart"),
            new Internal("enderchest", "enderchest", "minecraft:ender_chest"),
            new Internal("dex", "dex", "minecraft:book"),
            new Internal("spawns", "spawns", "minecraft:spyglass"));

    private Hub() {}

    public static List<Tile> tiles(ServerPlayer p) {
        List<Tile> out = new ArrayList<>();
        for (Internal in : INTERNAL) {
            if (!Cfg.commands.enabled(in.command) || !Perms.command(p, in.command)) continue;
            if (!moduleOpen(in.id)) continue;
            Tile t = new Tile();
            t.id = in.id;
            t.section = 1;
            t.name = Msg.raw("hub.tiles." + in.id + ".name");
            t.icon = Reg.itemExists(in.icon) ? in.icon : "minecraft:book";
            t.lore = new ArrayList<>(List.of(Msg.raw("hub.tiles." + in.id + ".lore").split("\\|")));
            if (in.id.equals("shop") || in.id.equals("gts")) t.lore.add(Msg.format("hub.balance", "money", Economy.format(Economy.balance(p.getUUID()))));
            out.add(t);
        }
        for (Map.Entry<String, GeneralConfig.Gateway> e : Cfg.general.gateways.entrySet()) {
            GeneralConfig.Gateway g = e.getValue();
            if (!g.enabled || (!g.mod.isBlank() && !FabricLoader.getInstance().isModLoaded(g.mod))) continue;
            Tile t = new Tile();
            t.id = "gw:" + e.getKey();
            t.section = 2;
            t.name = g.name;
            t.icon = Reg.itemExists(g.icon) ? g.icon : "minecraft:paper";
            t.lore = new ArrayList<>(g.lore);
            out.add(t);
        }
        if (StaffPanel.canOpen(p)) {
            Tile t = new Tile();
            t.id = "staff";
            t.section = 3;
            t.name = Msg.raw("hub.tiles.staff.name");
            t.icon = "minecraft:command_block";
            t.lore = new ArrayList<>(List.of(Msg.raw("hub.tiles.staff.lore").split("\\|")));
            out.add(t);
        }
        return out;
    }

    private static boolean moduleOpen(String id) {
        return switch (id) {
            case "shop" -> Cfg.shop.enabled;
            case "gts" -> Cfg.gts.enabled;
            case "sts" -> Cfg.sts.enabled;
            case "wondertrade" -> Cfg.wt.enabled;
            case "dex" -> Cfg.dex.enabled;
            case "spawns" -> Cfg.alerts.enabled;
            default -> true;
        };
    }

    public static void open(ServerPlayer p) {
        List<Tile> tiles = tiles(p);
        if (Net.hasClient(p)) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("title", Msg.format("hub.title"));
            o.addProperty("player", p.getGameProfile().getName());
            o.addProperty("balance", Economy.format(Economy.balance(p.getUUID())));
            o.add("tiles", fr.cobblepulse.utils.core.Json.COMPACT.toJsonTree(tiles));
            Net.send(p, "hub", o.toString());
            return;
        }
        chest(p, tiles);
    }

    /** Menu coffre equivalent (client sans le mod). */
    public static void chest(ServerPlayer p, List<Tile> tiles) {
        Gui gui = new Gui(6, Msg.format("hub.title"));
        gui.fill(Gui.pane());
        int[][] layout = {{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25}, {29, 30, 31, 32, 33, 37, 38, 39, 40, 41, 42, 43}, {49}};
        int[] idx = new int[3];
        gui.set(4, ItemBuilder.of(net.minecraft.world.item.Items.PLAYER_HEAD).head(p.getGameProfile())
                .name("&e" + p.getGameProfile().getName()).lore(Msg.format("hub.balance", "money", Economy.format(Economy.balance(p.getUUID())))).build());
        for (Tile t : tiles) {
            int s = Math.max(1, Math.min(3, t.section)) - 1;
            if (idx[s] >= layout[s].length) continue;
            int slot = layout[s][idx[s]++];
            gui.button(slot, ItemBuilder.of(Reg.stack(t.icon)).name(t.name).lore(t.lore).hideExtra().build(), pl -> action(pl, t.id));
        }
        gui.open(p);
    }

    /** Clic sur une tuile (permissions re-verifiees). */
    public static void action(ServerPlayer p, String id) {
        if (id == null) return;
        if (id.startsWith("gw:")) {
            gateway(p, id.substring(3));
            return;
        }
        boolean allowed = id.equals("staff") ? StaffPanel.canOpen(p) : tiles(p).stream().anyMatch(t -> t.id.equals(id));
        if (!allowed) {
            Msg.send(p, "general.no_permission");
            return;
        }
        switch (id) {
            case "wiki" -> WikiService.open(p, null);
            case "warps" -> WarpMenus.open(p);
            case "shop" -> ShopMenus.open(p);
            case "gts" -> GtsMenus.open(p);
            case "sts" -> StsMenus.open(p);
            case "wondertrade" -> WonderTradeMenus.open(p);
            case "kits" -> Kits.open(p);
            case "enderchest" -> Enderchests.open(p, p.getUUID(), p.getGameProfile().getName(), -1);
            case "dex" -> DexRewards.open(p);
            case "spawns" -> SpawnAlerts.openHistory(p, null);
            case "staff" -> StaffPanel.open(p);
            default -> {
            }
        }
    }

    private static void gateway(ServerPlayer p, String key) {
        GeneralConfig.Gateway g = Cfg.general.gateways.get(key);
        if (g == null || !g.enabled || (!g.mod.isBlank() && !FabricLoader.getInstance().isModLoaded(g.mod))) {
            Msg.send(p, "hub.gateway_missing");
            return;
        }
        String cmd = g.command.startsWith("/") ? g.command.substring(1) : g.command;
        switch (cmd) {
            case "@crates" -> Gateways.crates(p);
            case "@clans" -> Gateways.clans(p);
            default -> {
                if (cmd.isBlank()) return;
                p.closeContainer();
                CobbleUtils.server().getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd.replace("%player%", p.getGameProfile().getName()));
            }
        }
    }
}
