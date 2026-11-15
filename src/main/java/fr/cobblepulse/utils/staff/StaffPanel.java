package fr.cobblepulse.utils.staff;

import fr.cobblepulse.utils.boss.BossEditor;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.dex.DexRewards;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import fr.cobblepulse.utils.kits.Kits;
import fr.cobblepulse.utils.market.GtsMenus;
import fr.cobblepulse.utils.shop.ShopMenus;
import fr.cobblepulse.utils.teleport.WarpMenus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Panneau Staff (/cp admin) : acces aux editeurs selon les noeuds cobblepulse.admin.*. */
public final class StaffPanel {
    private static final String[] NODES = {"menu", "shop", "kits", "warps", "boss", "gts", "dexrewards"};

    private StaffPanel() {}

    public static boolean canOpen(ServerPlayer p) {
        for (String n : NODES) if (Perms.admin(p, n)) return true;
        return false;
    }

    public static void open(ServerPlayer p) {
        if (!canOpen(p)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        Gui g = new Gui(4, Msg.format("staff.panel_title"));
        g.fill(Gui.pane());
        if (Perms.admin(p, "shop")) g.button(10, ItemBuilder.of(Items.EMERALD).name(Msg.format("staff.panel_shop")).lore(Msg.format("staff.panel_shop_lore")).build(), ShopMenus::editor);
        if (Perms.admin(p, "kits")) g.button(11, ItemBuilder.of(Items.CHEST_MINECART).name(Msg.format("staff.panel_kits")).lore(Msg.format("staff.panel_kits_lore")).build(), Kits::editor);
        if (Perms.admin(p, "warps")) g.button(12, ItemBuilder.of(Items.COMPASS).name(Msg.format("staff.panel_warps")).lore(Msg.format("staff.panel_warps_lore")).build(), WarpMenus::editor);
        if (Perms.admin(p, "boss")) g.button(13, ItemBuilder.of(Items.DRAGON_HEAD).name(Msg.format("staff.panel_boss")).lore(Msg.format("staff.panel_boss_lore")).build(), BossEditor::open);
        if (Perms.admin(p, "gts")) {
            g.button(14, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("staff.panel_gts")).lore(Msg.format("staff.panel_gts_lore")).build(), GtsMenus::open);
            g.button(15, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("staff.panel_ranks")).lore(Msg.format("staff.panel_ranks_lore")).build(), StaffPanel::ranks);
        }
        if (Perms.admin(p, "dexrewards")) g.button(16, ItemBuilder.of(Items.BOOK).name(Msg.format("staff.panel_dex")).lore(Msg.format("staff.panel_dex_lore")).build(), DexRewards::editor);
        if (Perms.admin(p, "gts.toggle") || Perms.admin(p, "sts.toggle") || Perms.admin(p, "shop.toggle")) {
            g.button(20, toggleIcon("staff.panel_toggle_gts", Cfg.gts.enabled), pl -> {
                if (!Perms.admin(pl, "gts.toggle")) return;
                Cfg.gts.enabled = !Cfg.gts.enabled;
                Cfg.save("gts.json", Cfg.gts);
                open(pl);
            });
            g.button(21, toggleIcon("staff.panel_toggle_sts", Cfg.sts.enabled), pl -> {
                if (!Perms.admin(pl, "sts.toggle")) return;
                Cfg.sts.enabled = !Cfg.sts.enabled;
                Cfg.save("sts.json", Cfg.sts);
                open(pl);
            });
            g.button(22, toggleIcon("staff.panel_toggle_shop", Cfg.shop.enabled), pl -> {
                if (!Perms.admin(pl, "shop.toggle")) return;
                Cfg.shop.enabled = !Cfg.shop.enabled;
                Cfg.save("shop.json", Cfg.shop);
                open(pl);
            });
            g.button(23, toggleIcon("staff.panel_toggle_wt", Cfg.wt.enabled), pl -> {
                if (!Perms.admin(pl, "gts.toggle")) return;
                Cfg.wt.enabled = !Cfg.wt.enabled;
                Cfg.save("wondertrade.json", Cfg.wt);
                open(pl);
            });
        }
        if (Perms.admin(p, "boss.toggle")) g.button(24, toggleIcon("staff.panel_toggle_boss", Cfg.boss.naturalSpawn), pl -> {
            Cfg.boss.naturalSpawn = !Cfg.boss.naturalSpawn;
            Cfg.save("boss.json", Cfg.boss);
            open(pl);
        });
        if (Perms.admin(p, "reload")) g.button(25, ItemBuilder.of(Items.REPEATER).name(Msg.format("staff.panel_reload")).build(), pl -> {
            pl.closeContainer();
            pl.getServer().getCommands().performPrefixedCommand(pl.createCommandSourceStack(), "cp reload");
        });
        g.button(27, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
        g.open(p);
    }

    private static net.minecraft.world.item.ItemStack toggleIcon(String key, boolean on) {
        return ItemBuilder.of(on ? Items.LIME_DYE : Items.GRAY_DYE).name(Msg.format(key, "state", Msg.format(on ? "staff.state_open" : "staff.state_closed"))).build();
    }

    // ------------------------------------------------------------------ generateur de permissions par grade

    /** GTS / STS : taxes, quotas et durees par grade LuckPerms (ecrit les noeuds cobblepulse.gts.tax.N etc. sur le groupe). */
    public static void ranks(ServerPlayer p) {
        if (!LuckPermsBridge.present()) {
            Msg.send(p, "staff.ranks_no_lp");
            return;
        }
        List<Paged.Entry> entries = new ArrayList<>();
        for (String group : LuckPermsBridge.groups()) {
            entries.add(new Paged.Entry(ItemBuilder.of(Items.NAME_TAG).name("&e" + group).lore(Msg.format("staff.ranks_group_lore",
                    "tax", show(LuckPermsBridge.groupNumeric(group, "cobblepulse.gts.tax"), "%"),
                    "limit", show(LuckPermsBridge.groupNumeric(group, "cobblepulse.gts.limit"), ""),
                    "duration", show(LuckPermsBridge.groupNumeric(group, "cobblepulse.gts.duration"), " h"),
                    "sts", show(LuckPermsBridge.groupNumeric(group, "cobblepulse.sts.limit"), "/24 h"))).build(), (pl, c) -> group(pl, group)));
        }
        Paged.open(p, Msg.format("staff.ranks_title"), entries, 0, StaffPanel::open);
    }

    private static String show(Integer v, String unit) {
        return v == null ? Msg.format("staff.ranks_default") : v + unit;
    }

    private static void group(ServerPlayer p, String group) {
        Gui g = new Gui(3, Msg.format("staff.ranks_edit_title", "group", group));
        g.fill(Gui.pane());
        Object[][] rows = {
                {10, Items.GOLD_NUGGET, "cobblepulse.gts.tax", "staff.ranks_tax", "%"},
                {12, Items.CHEST, "cobblepulse.gts.limit", "staff.ranks_limit", ""},
                {14, Items.CLOCK, "cobblepulse.gts.duration", "staff.ranks_duration", " h"},
                {16, Items.HOPPER, "cobblepulse.sts.limit", "staff.ranks_sts", "/24 h"}};
        for (Object[] r : rows) {
            String prefix = (String) r[2];
            Integer cur = LuckPermsBridge.groupNumeric(group, prefix);
            g.button((Integer) r[0], ItemBuilder.of((net.minecraft.world.item.Item) r[1]).name(Msg.format((String) r[3], "value", show(cur, (String) r[4])))
                    .lore(Msg.format("staff.ranks_node", "node", prefix + ".<n>"), Msg.format("staff.ranks_click")).build(), (pl, c) -> {
                if (c.right() || c.shift()) {
                    LuckPermsBridge.setGroupNumeric(group, prefix, null);
                    Msg.send(pl, "staff.ranks_cleared", "node", prefix, "group", group);
                    fr.cobblepulse.utils.core.Scheduler.later(10, () -> group(pl, group));
                    return;
                }
                ChatInput.ask(pl, "staff.ranks_ask", txt -> {
                    Long v = ChatInput.parseAmount(txt.replace("%", "").replace("h", ""));
                    if (v == null) {
                        Msg.send(pl, "general.bad_number");
                    } else {
                        LuckPermsBridge.setGroupNumeric(group, prefix, v.intValue());
                        Msg.send(pl, "staff.ranks_set", "node", prefix + "." + v, "group", group);
                    }
                    fr.cobblepulse.utils.core.Scheduler.later(10, () -> group(pl, group));
                }, () -> group(pl, group), "node", prefix, "group", group);
            });
        }
        g.button(18, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), StaffPanel::ranks);
        g.open(p);
    }
}
