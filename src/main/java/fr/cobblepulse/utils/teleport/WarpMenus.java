package fr.cobblepulse.utils.teleport;

import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.WarpsConfig;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Loc;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Menus Teleportation : warps publics (filtres par permission), « Mes Homes », editeur de warps (staff). */
public final class WarpMenus {
    private WarpMenus() {}

    public static boolean canUse(ServerPlayer p, WarpsConfig.Warp w) {
        Boolean v = LuckPermsBridge.check(p, "cobblepulse.warp." + w.name);
        if (v != null) return v;
        if (!w.restricted) return true;
        return p.hasPermissions(Cfg.general.opLevel);
    }

    public static List<WarpsConfig.Warp> visible(ServerPlayer p) {
        List<WarpsConfig.Warp> out = new ArrayList<>();
        for (WarpsConfig.Warp w : Cfg.warps.warps) if (canUse(p, w)) out.add(w);
        return out;
    }

    private static ItemStack warpIcon(WarpsConfig.Warp w, boolean staff) {
        ItemBuilder b = ItemBuilder.of(Reg.stack(w.icon)).name(w.display);
        b.lore(w.lore);
        b.lore(Msg.format("warps.icon_world", "world", w.loc.shortWorld()));
        if (staff) b.lore(Msg.format("warps.icon_coords", "coords", w.loc.coords(), "name", w.name, "restricted", w.restricted ? "oui" : "non"));
        b.lore("", Msg.format("warps.icon_click"));
        return b.hideExtra().build();
    }

    /** Menu Teleportation : Spawn, Mes Homes, RTP et warps visibles. */
    public static void open(ServerPlayer p) {
        List<WarpsConfig.Warp> list = visible(p);
        boolean staff = Perms.admin(p, "warps");
        int rows = Math.max(3, Math.min(6, 2 + (list.size() + 6) / 7));
        Gui gui = new Gui(rows, Msg.format("warps.title"));
        gui.border();
        gui.button(1, ItemBuilder.of(Items.BEACON).name(Msg.format("warps.spawn_name")).lore(Msg.format("warps.spawn_lore")).build(),
                pl -> {
                    fr.cobblepulse.utils.gui.Gui.close(pl);
                    Teleports.teleport(pl, Teleports.spawn(), Msg.format("teleport.spawn_label"), null);
                });
        gui.button(4, ItemBuilder.of(Items.RED_BED).name(Msg.format("homes.menu_name"))
                .lore(Msg.format("homes.menu_lore", "count", PlayerStore.get(p).homes.size(), "max", HomeLimits.limit(p))).build(), WarpMenus::homes);
        if (Perms.command(p, "rtp")) {
            gui.button(7, ItemBuilder.of(Items.GRASS_BLOCK).name(Msg.format("warps.rtp_name")).lore(Msg.format("warps.rtp_lore")).build(), pl -> {
                fr.cobblepulse.utils.gui.Gui.close(pl);
                pl.getServer().getCommands().performPrefixedCommand(pl.createCommandSourceStack(), "rtp");
            });
        }
        int[] inner = Gui.innerSlots(rows);
        List<WarpsConfig.Warp> free = new ArrayList<>();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (WarpsConfig.Warp w : list) {
            if (w.slot >= 9 && w.slot < rows * 9 - 9 && w.slot % 9 != 0 && w.slot % 9 != 8 && used.add(w.slot)) {
                gui.button(w.slot, warpIcon(w, staff), pl -> go(pl, w));
            } else {
                free.add(w);
            }
        }
        int i = 0;
        for (WarpsConfig.Warp w : free) {
            while (i < inner.length && used.contains(inner[i])) i++;
            if (i >= inner.length) break;
            gui.button(inner[i++], warpIcon(w, staff), pl -> go(pl, w));
        }
        if (list.isEmpty()) gui.set(rows * 9 / 2, ItemBuilder.of(Items.BARRIER).name(Msg.format("warps.none")).build());
        gui.button(rows * 9 - 5, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
        if (staff) {
            gui.button(rows * 9 - 1, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("warps.editor_button")).build(), WarpMenus::editor);
        }
        gui.open(p);
    }

    public static void go(ServerPlayer p, WarpsConfig.Warp w) {
        if (!canUse(p, w)) {
            Msg.send(p, "general.no_permission");
            return;
        }
        fr.cobblepulse.utils.gui.Gui.close(p);
        Teleports.teleport(p, w.loc, w.display, null);
    }

    // ---------------------------------------------------------------- Homes

    private static ItemStack homeIcon(String name, Loc l) {
        ItemStack base = switch (l.world) {
            case "minecraft:overworld" -> new ItemStack(Items.GRASS_BLOCK);
            case "minecraft:the_nether" -> new ItemStack(Items.NETHERRACK);
            case "minecraft:the_end" -> new ItemStack(Items.END_STONE);
            default -> new ItemStack(Items.COMPASS);
        };
        return ItemBuilder.of(base).name("&a" + name)
                .lore(Msg.format("homes.icon_lore", "world", l.shortWorld(), "coords", l.coords()))
                .lore("", Msg.format("homes.icon_click")).build();
    }

    public static void homes(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        List<Paged.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Loc> e : d.homes.entrySet()) {
            String name = e.getKey();
            Loc loc = e.getValue();
            entries.add(new Paged.Entry(homeIcon(name, loc), (pl, click) -> {
                if (click.right() || click.shift()) confirmDelete(pl, name);
                else {
                    fr.cobblepulse.utils.gui.Gui.close(pl);
                    Teleports.teleport(pl, loc, Msg.format("homes.label", "home", name), null);
                }
            }));
        }
        Paged.open(p, Msg.format("homes.title", "count", d.homes.size(), "max", HomeLimits.limit(p)), entries, 0, WarpMenus::open, gui -> {
            if (entries.isEmpty()) gui.set(22, ItemBuilder.of(Items.BARRIER).name(Msg.format("homes.none")).lore(Msg.format("homes.none_lore")).build());
            gui.button(51, ItemBuilder.of(Items.LIME_BED).name(Msg.format("homes.set_here")).lore(Msg.format("homes.set_here_lore")).build(),
                    pl -> ChatInput.ask(pl, "homes.ask_name", name -> {
                        HomeLimits.setHome(pl, name);
                        homes(pl);
                    }));
        });
    }

    private static void confirmDelete(ServerPlayer p, String name) {
        Gui g = new Gui(3, Msg.format("homes.delete_title", "home", name));
        g.fill(Gui.pane());
        g.button(11, ItemBuilder.of(Items.LIME_CONCRETE).name(Msg.format("general.confirm")).build(), pl -> {
            PlayerData d = PlayerStore.get(pl);
            if (d.homes.remove(name) != null) {
                PlayerStore.dirty(pl.getUUID());
                Msg.send(pl, "homes.deleted", "home", name);
            }
            homes(pl);
        });
        g.button(15, ItemBuilder.of(Items.RED_CONCRETE).name(Msg.format("general.cancel")).build(), WarpMenus::homes);
        g.open(p);
    }

    // ---------------------------------------------------------------- Editeur (staff)

    public static void editor(ServerPlayer p) {
        if (!Perms.admin(p, "warps")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        List<Paged.Entry> entries = new ArrayList<>();
        for (WarpsConfig.Warp w : Cfg.warps.warps) {
            entries.add(new Paged.Entry(ItemBuilder.of(warpIcon(w, true)).lore("", Msg.format("warps.editor_click")).build(), (pl, c) -> edit(pl, w)));
        }
        Paged.open(p, Msg.format("warps.editor_title"), entries, 0, WarpMenus::open, gui -> gui.button(51,
                ItemBuilder.of(Items.LIME_DYE).name(Msg.format("warps.editor_new")).lore(Msg.format("warps.editor_new_lore")).build(),
                pl -> ChatInput.ask(pl, "warps.ask_name", name -> {
                    WarpsConfig.Warp w = createAt(pl, name);
                    if (w != null) edit(pl, w);
                })));
    }

    public static WarpsConfig.Warp createAt(ServerPlayer p, String rawName) {
        String name = rawName.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        if (name.isEmpty() || name.length() > 32) {
            Msg.send(p, "warps.bad_name");
            return null;
        }
        WarpsConfig.Warp w = Cfg.warps.find(name);
        boolean created = w == null;
        if (w == null) {
            w = new WarpsConfig.Warp();
            w.name = name;
            w.display = "&b" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            ItemStack hand = p.getMainHandItem();
            if (!hand.isEmpty()) w.icon = Reg.id(hand.getItem());
            Cfg.warps.warps.add(w);
        }
        w.loc = Loc.of(p);
        Cfg.save("warps.json", Cfg.warps);
        Msg.send(p, created ? "warps.created" : "warps.moved", "warp", name);
        return w;
    }

    public static void edit(ServerPlayer p, WarpsConfig.Warp w) {
        Gui g = new Gui(3, Msg.format("warps.edit_title", "warp", w.name));
        g.fill(Gui.pane());
        g.set(4, warpIcon(w, true));
        g.button(10, ItemBuilder.of(Items.ITEM_FRAME).name(Msg.format("warps.edit_icon")).lore(Msg.format("warps.edit_icon_lore")).build(), pl -> {
            ItemStack hand = pl.getMainHandItem();
            if (hand.isEmpty()) {
                Msg.send(pl, "warps.edit_icon_empty");
                return;
            }
            w.icon = Reg.id(hand.getItem());
            Cfg.save("warps.json", Cfg.warps);
            edit(pl, w);
        });
        g.button(11, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("warps.edit_name")).lore(Msg.format("warps.edit_name_lore")).build(),
                pl -> ChatInput.ask(pl, "warps.ask_display", txt -> {
                    w.display = txt;
                    Cfg.save("warps.json", Cfg.warps);
                    edit(pl, w);
                }, () -> edit(pl, w)));
        g.button(12, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("warps.edit_lore")).lore(Msg.format("warps.edit_lore_lore")).build(),
                pl -> ChatInput.ask(pl, "warps.ask_lore", txt -> {
                    w.lore = txt.equals("-") ? new ArrayList<>() : new ArrayList<>(List.of(txt.split("\\|")));
                    Cfg.save("warps.json", Cfg.warps);
                    edit(pl, w);
                }, () -> edit(pl, w)));
        g.button(13, ItemBuilder.of(Items.COMPASS).name(Msg.format("warps.edit_move")).lore(Msg.format("warps.edit_move_lore")).build(), pl -> {
            w.loc = Loc.of(pl);
            Cfg.save("warps.json", Cfg.warps);
            Msg.send(pl, "warps.moved", "warp", w.name);
            edit(pl, w);
        });
        g.button(14, ItemBuilder.of(w.restricted ? Items.IRON_BARS : Items.OAK_FENCE_GATE)
                .name(Msg.format(w.restricted ? "warps.edit_private" : "warps.edit_public"))
                .lore(Msg.format("warps.edit_restricted_lore", "name", w.name)).build(), pl -> {
            w.restricted = !w.restricted;
            Cfg.save("warps.json", Cfg.warps);
            edit(pl, w);
        });
        g.button(15, ItemBuilder.of(Items.HOPPER).name(Msg.format("warps.edit_slot", "slot", w.slot < 0 ? "auto" : String.valueOf(w.slot)))
                .lore(Msg.format("warps.edit_slot_lore")).build(), pl -> ChatInput.ask(pl, "warps.ask_slot", txt -> {
            Long v = ChatInput.parseAmount(txt);
            w.slot = v == null ? -1 : (int) Math.min(53, v);
            Cfg.save("warps.json", Cfg.warps);
            edit(pl, w);
        }, () -> edit(pl, w)));
        g.button(16, ItemBuilder.of(Items.ENDER_PEARL).name(Msg.format("warps.edit_tp")).build(), pl -> go(pl, w));
        g.button(22, ItemBuilder.of(Items.LAVA_BUCKET).name(Msg.format("warps.edit_delete")).lore(Msg.format("warps.edit_delete_lore")).build(), (pl, c) -> {
            if (!c.shift()) {
                Msg.send(pl, "warps.edit_delete_shift");
                return;
            }
            Cfg.warps.warps.remove(w);
            Cfg.save("warps.json", Cfg.warps);
            Msg.send(pl, "warps.deleted", "warp", w.name);
            editor(pl);
        });
        g.button(18, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), WarpMenus::editor);
        g.open(p);
    }
}
