package fr.cobblepulse.utils.shop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.ShopConfig;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.MonSpec;
import fr.cobblepulse.utils.staff.StaffPanel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static net.minecraft.commands.Commands.argument;

/** Menus du Shop Admin (joueurs + editeur cobblepulse.admin.shop). */
public final class ShopMenus {
    private ShopMenus() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "shop", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, ShopMenus::open))
                .then(argument("categorie", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Cfg.SHOP.keySet(), b))
                        .executes(ctx -> Cmd.run(ctx, p -> {
                            ShopConfig.Category c = Cfg.SHOP.get(StringArgumentType.getString(ctx, "categorie").toLowerCase(Locale.ROOT));
                            if (c == null) {
                                Msg.send(p, "shop.unknown_category", "category", StringArgumentType.getString(ctx, "categorie"));
                                return;
                            }
                            category(p, c, 0);
                        }))));
        Cmd.player(d, "sell", l -> Cmd.lit(l).then(Cmd.lit("hand").executes(ctx -> Cmd.run(ctx, p -> ShopService.sellCommand(p, false)))
                .then(Cmd.lit("all").executes(ctx -> Cmd.run(ctx, p -> ShopService.sellCommand(p, true))))));
    }

    // ------------------------------------------------------------------ joueurs

    public static void open(ServerPlayer p) {
        if (!Cfg.shop.enabled && !Perms.admin(p, "shop")) {
            Msg.send(p, "shop.closed");
            return;
        }
        List<ShopConfig.Category> cats = new ArrayList<>(Cfg.SHOP.values());
        int rows = Math.max(3, Math.min(6, 2 + (cats.size() + 6) / 7));
        Gui g = new Gui(rows, Msg.format("shop.title") + (Cfg.shop.enabled ? "" : " " + Msg.format("shop.title_closed")));
        g.border();
        g.set(4, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("hub.balance", "money", Economy.format(Economy.balance(p.getUUID()))))
                .lore(personal(p)).build());
        Set<Integer> used = new HashSet<>();
        List<ShopConfig.Category> auto = new ArrayList<>();
        for (ShopConfig.Category c : cats) {
            if (c.slot >= 9 && c.slot < rows * 9 - 9 && used.add(c.slot)) g.button(c.slot, catIcon(c), pl -> category(pl, c, 0));
            else auto.add(c);
        }
        int[] inner = Gui.innerSlots(rows);
        int i = 0;
        for (ShopConfig.Category c : auto) {
            while (i < inner.length && used.contains(inner[i])) i++;
            if (i >= inner.length) break;
            g.button(inner[i++], catIcon(c), pl -> category(pl, c, 0));
        }
        g.button(rows * 9 - 5, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
        if (Perms.admin(p, "shop")) g.button(rows * 9 - 1, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("shop.editor_button")).build(), ShopMenus::editor);
        g.open(p);
    }

    private static String personal(ServerPlayer p) {
        double d = ShopService.personalDiscount(p);
        return d > 0 ? Msg.format("shop.personal_discount", "percent", fmt(d)) : "";
    }

    private static ItemStack catIcon(ShopConfig.Category c) {
        ItemBuilder b = ItemBuilder.of(Reg.stack(c.icon)).name(c.name).lore(c.lore);
        if (c.discount > 0) b.lore(Msg.format("shop.promo", "percent", fmt(c.discount)));
        b.lore(Msg.format("shop.cat_count", "count", c.entries.size()));
        b.lore(ShopService.open(c) ? Msg.format("shop.cat_open") : Msg.format("shop.cat_closed"));
        return b.glow(c.discount > 0).hideExtra().build();
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.FRANCE, "%.1f", d);
    }

    private static ItemStack entryIcon(ServerPlayer p, ShopConfig.Category c, ShopConfig.Entry e) {
        ItemStack base = "item".equals(e.type) ? ShopService.stack(e) : Reg.stack(e.item.isBlank() ? ("pokemon".equals(e.type) ? "cobblemon:poke_ball" : "minecraft:paper") : e.item);
        if (base.isEmpty()) base = new ItemStack(Items.BARRIER);
        ItemBuilder b = ItemBuilder.of(base.copyWithCount(Math.max(1, Math.min(base.getMaxStackSize(), base.getCount()))));
        if (!e.display.isBlank()) b.name(e.display);
        b.lore(e.lore);
        if ("pokemon".equals(e.type) && e.pokemon != null) b.lore(monLore(e.pokemon));
        b.lore("");
        long unit = ShopService.unitBuy(p, c, e);
        if (unit >= 0) {
            double disc = ShopService.totalDiscount(p, c, e);
            b.lore(Msg.format("shop.price_buy", "money", Economy.format(unit)) + (disc > 0 ? " " + Msg.format("shop.price_was", "money", Economy.format(e.buy), "percent", fmt(disc)) : ""));
        }
        if (e.sell >= 0 && "item".equals(e.type) && Cfg.shop.allowSell) b.lore(Msg.format("shop.price_sell", "money", Economy.format(e.sell)));
        b.lore("", Msg.format(unit >= 0 && e.sell >= 0 ? "shop.click_both" : unit >= 0 ? "shop.click_buy" : "shop.click_sell"));
        return b.glow(e.discount > 0).build();
    }

    private static List<String> monLore(MonSpec m) {
        List<String> l = new ArrayList<>();
        l.add("&7Niveau : &f" + m.level + (m.shiny ? "  &e★ Chromatique" : ""));
        if (!m.nature.isBlank()) l.add("&7Nature : &f" + fr.cobblepulse.utils.pokemon.Names.nature(m.nature));
        if (!m.ability.isBlank()) l.add("&7Talent : &f" + m.ability);
        StringBuilder iv = new StringBuilder();
        for (String s : MonSpec.STATS) iv.append(iv.length() == 0 ? "" : "&8/").append(m.iv(s) < 0 ? "&7?" : (m.iv(s) == 31 ? "&a" : "&f") + m.iv(s));
        l.add("&7IV : " + iv);
        if (!m.heldItem.isBlank()) l.add("&7Objet tenu : &f" + m.heldItem);
        if (!m.moves.isEmpty()) l.add("&7Capacités : &f" + String.join(", ", m.moves));
        return l;
    }

    public static void category(ServerPlayer p, ShopConfig.Category c, int page) {
        if (!ShopService.open(c) && !Perms.admin(p, "shop")) {
            Msg.send(p, "shop.closed_category", "category", TextUtil.strip(c.name));
            return;
        }
        List<Paged.Entry> entries = new ArrayList<>();
        List<ShopConfig.Entry> sorted = new ArrayList<>(c.entries);
        for (ShopConfig.Entry e : sorted) {
            entries.add(new Paged.Entry(entryIcon(p, c, e), (pl, click) -> {
                long unit = ShopService.unitBuy(pl, c, e);
                if (click.shift() && click.type() == net.minecraft.world.inventory.ClickType.QUICK_MOVE && unit < 0 && e.sell >= 0) {
                    ShopService.sell(pl, c, e, -1, false);
                    category(pl, c, page);
                } else {
                    trade(pl, c, e, 1, page);
                }
            }));
        }
        Paged.open(p, c.name + (ShopService.open(c) ? "" : " " + Msg.format("shop.title_closed")), entries, page, ShopMenus::open,
                gui -> gui.set(4 + 45, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("hub.balance", "money", Economy.format(Economy.balance(p.getUUID())))).build()));
    }

    /** Achat / vente groupes : choix de la quantite. */
    public static void trade(ServerPlayer p, ShopConfig.Category c, ShopConfig.Entry e, int qty, int page) {
        int max = "pokemon".equals(e.type) ? 6 : "command".equals(e.type) ? 64 : 2304;
        int q = Math.max(1, Math.min(max, qty));
        long unit = ShopService.unitBuy(p, c, e);
        Gui g = new Gui(4, Msg.format("shop.trade_title"));
        g.fill(Gui.pane());
        ItemStack icon = entryIcon(p, c, e);
        g.set(4, ItemBuilder.of(icon).count(Math.min(64, q)).build());
        int[][] steps = {{9, -64}, {10, -10}, {11, -1}, {15, 1}, {16, 10}, {17, 64}};
        for (int[] st : steps) {
            int delta = st[1];
            g.button(st[0], ItemBuilder.of(delta < 0 ? Items.RED_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE)
                    .name((delta < 0 ? "&c" : "&a+") + delta).build(), pl -> trade(pl, c, e, q + delta, page));
        }
        g.set(13, ItemBuilder.of(Items.PAPER).name(Msg.format("shop.trade_qty", "amount", q)).build());
        if (unit >= 0) {
            g.button(21, ItemBuilder.of(Items.EMERALD).name(Msg.format("shop.trade_buy", "amount", q, "money", Economy.format(unit * q))).build(), pl -> {
                if (ShopService.buy(pl, c, e, q)) trade(pl, c, e, q, page);
            });
        }
        if (e.sell >= 0 && "item".equals(e.type) && Cfg.shop.allowSell) {
            ItemStack ref = ShopService.stack(e);
            int have = ref.isEmpty() ? 0 : ShopService.count(p, ref) / Math.max(1, ref.getCount());
            g.button(23, ItemBuilder.of(Items.GOLD_NUGGET).name(Msg.format("shop.trade_sell", "amount", q, "money", Economy.format(e.sell * Math.min(q, have))))
                    .lore(Msg.format("shop.trade_have", "amount", have)).build(), pl -> {
                if (ShopService.sell(pl, c, e, q, false) > 0) trade(pl, c, e, q, page);
            });
            g.button(24, ItemBuilder.of(Items.GOLD_BLOCK).name(Msg.format("shop.trade_sell_all", "amount", have, "money", Economy.format(e.sell * have))).build(), pl -> {
                if (ShopService.sell(pl, c, e, -1, false) > 0) trade(pl, c, e, q, page);
            });
        }
        g.set(31, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("hub.balance", "money", Economy.format(Economy.balance(p.getUUID())))).build());
        g.button(27, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), pl -> category(pl, c, page));
        g.open(p);
    }

    // ------------------------------------------------------------------ editeur

    public static void editor(ServerPlayer p) {
        if (!Perms.admin(p, "shop")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        List<Paged.Entry> entries = new ArrayList<>();
        for (ShopConfig.Category c : Cfg.SHOP.values()) {
            entries.add(new Paged.Entry(ItemBuilder.of(catIcon(c)).lore("", Msg.format("shop.editor_cat_click")).build(), (pl, click) -> {
                if (click.right()) {
                    c.enabled = !c.enabled;
                    Cfg.saveShopCategory(c);
                    editor(pl);
                } else editCategory(pl, c, 0);
            }));
        }
        Paged.open(p, Msg.format("shop.editor_title"), entries, 0, StaffPanel::open, gui -> {
            gui.button(46, ItemBuilder.of(Cfg.shop.enabled ? Items.LIME_DYE : Items.GRAY_DYE).name(Msg.format(Cfg.shop.enabled ? "shop.editor_open" : "shop.editor_closed")).build(), pl -> {
                Cfg.shop.enabled = !Cfg.shop.enabled;
                Cfg.save("shop.json", Cfg.shop);
                editor(pl);
            });
            gui.button(47, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("shop.editor_global_discount", "percent", fmt(Cfg.shop.globalDiscount))).build(),
                    pl -> ChatInput.ask(pl, "shop.ask_discount", txt -> {
                        Long v = ChatInput.parseAmount(txt.replace("%", ""));
                        if (v != null) Cfg.shop.globalDiscount = Math.min(100, v);
                        Cfg.save("shop.json", Cfg.shop);
                        editor(pl);
                    }, () -> editor(pl)));
            gui.button(48, ItemBuilder.of(Cfg.shop.allowSell ? Items.GOLD_NUGGET : Items.IRON_NUGGET).name(Msg.format(Cfg.shop.allowSell ? "shop.editor_sell_on" : "shop.editor_sell_off")).build(), pl -> {
                Cfg.shop.allowSell = !Cfg.shop.allowSell;
                Cfg.save("shop.json", Cfg.shop);
                editor(pl);
            });
            gui.button(51, ItemBuilder.of(Items.LIME_DYE).name(Msg.format("shop.editor_new_cat")).build(), pl -> ChatInput.ask(pl, "shop.ask_cat_id", txt -> {
                String id = txt.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
                if (id.isEmpty() || Cfg.SHOP.containsKey(id)) {
                    Msg.send(pl, "kits.bad_name");
                    editor(pl);
                    return;
                }
                ShopConfig.Category c = new ShopConfig.Category();
                c.id = id;
                c.name = "&f" + id;
                ShopConfig.normalize(c);
                Cfg.SHOP.put(id, c);
                Cfg.saveShopCategory(c);
                editCategory(pl, c, 0);
            }, () -> editor(pl)));
        });
    }

    public static void editCategory(ServerPlayer p, ShopConfig.Category c, int page) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (ShopConfig.Entry e : c.entries) {
            entries.add(new Paged.Entry(ItemBuilder.of(entryIcon(p, c, e)).lore(Msg.format("shop.editor_entry_info", "id", e.id, "type", e.type))
                    .lore(Msg.format("shop.editor_entry_click")).build(), (pl, click) -> editEntry(pl, c, e)));
        }
        Paged.open(p, Msg.format("shop.editor_cat_title", "category", c.id), entries, page, ShopMenus::editor, gui -> {
            gui.button(45, ItemBuilder.of(Items.CHEST).name(Msg.format("shop.editor_add_item")).lore(Msg.format("shop.editor_add_item_lore")).build(), pl -> {
                ItemStack hand = pl.getMainHandItem();
                if (hand.isEmpty()) {
                    Msg.send(pl, "warps.edit_icon_empty");
                    return;
                }
                ShopConfig.Entry e = new ShopConfig.Entry();
                e.id = Reg.id(hand.getItem()).replace(':', '_') + "_" + c.entries.size();
                e.item = ItemSerial.toSnbt(hand);
                c.entries.add(e);
                ChatInput.ask(pl, "shop.ask_prices", txt -> {
                    applyPrices(e, txt);
                    Cfg.saveShopCategory(c);
                    editEntry(pl, c, e);
                }, () -> {
                    Cfg.saveShopCategory(c);
                    editEntry(pl, c, e);
                });
            });
            gui.button(46, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.format("shop.editor_add_command")).build(), pl -> ChatInput.ask(pl, "shop.ask_command_entry", txt -> {
                ShopConfig.Entry e = new ShopConfig.Entry();
                e.type = "command";
                e.id = "commande_" + c.entries.size();
                e.item = "minecraft:paper";
                e.commands = new ArrayList<>(List.of(txt.startsWith("/") ? txt.substring(1) : txt));
                e.display = "&f/" + e.commands.get(0);
                c.entries.add(e);
                Cfg.saveShopCategory(c);
                editEntry(pl, c, e);
            }, () -> editCategory(pl, c, page)));
            gui.button(47, ItemBuilder.of(Items.EGG).name(Msg.format("shop.editor_add_pokemon")).lore(Msg.format("shop.editor_add_pokemon_lore")).build(),
                    pl -> ChatInput.ask(pl, "shop.ask_pokemon", txt -> {
                        MonSpec m = MonSpecParser.parse(txt);
                        if (m == null) {
                            Msg.send(pl, "shop.bad_pokemon");
                            editCategory(pl, c, page);
                            return;
                        }
                        ShopConfig.Entry e = new ShopConfig.Entry();
                        e.type = "pokemon";
                        e.id = "pokemon_" + c.entries.size();
                        e.pokemon = m;
                        e.buy = 10000;
                        c.entries.add(e);
                        Cfg.saveShopCategory(c);
                        editEntry(pl, c, e);
                    }, () -> editCategory(pl, c, page)));
            gui.button(48, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), ShopMenus::editor);
            gui.button(50, ItemBuilder.of(c.enabled ? Items.LIME_DYE : Items.GRAY_DYE).name(Msg.format(c.enabled ? "shop.editor_cat_open" : "shop.editor_cat_closed")).build(), pl -> {
                c.enabled = !c.enabled;
                Cfg.saveShopCategory(c);
                editCategory(pl, c, page);
            });
            gui.button(51, ItemBuilder.of(Reg.stack(c.icon)).name(Msg.format("shop.editor_cat_settings")).lore(Msg.format("shop.editor_cat_settings_lore")).build(),
                    pl -> categorySettings(pl, c));
        });
    }

    private static void applyPrices(ShopConfig.Entry e, String txt) {
        String[] parts = txt.trim().split("[\\s/;]+");
        if (parts.length > 0) {
            Long b = parts[0].equals("-") ? Long.valueOf(-1) : ChatInput.parseAmount(parts[0]);
            if (b != null) e.buy = b;
        }
        if (parts.length > 1) {
            Long s = parts[1].equals("-") ? Long.valueOf(-1) : ChatInput.parseAmount(parts[1]);
            if (s != null) e.sell = s;
        }
    }

    private static void categorySettings(ServerPlayer p, ShopConfig.Category c) {
        Gui g = new Gui(3, Msg.format("shop.editor_cat_settings"));
        g.fill(Gui.pane());
        g.set(4, catIcon(c));
        g.button(10, ItemBuilder.of(Items.ITEM_FRAME).name(Msg.format("warps.edit_icon")).build(), pl -> {
            if (!pl.getMainHandItem().isEmpty()) c.icon = Reg.id(pl.getMainHandItem().getItem());
            Cfg.saveShopCategory(c);
            categorySettings(pl, c);
        });
        g.button(11, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("warps.edit_name")).build(), pl -> ChatInput.ask(pl, "kits.ask_display", txt -> {
            c.name = txt;
            Cfg.saveShopCategory(c);
            categorySettings(pl, c);
        }, () -> categorySettings(pl, c)));
        g.button(12, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("warps.edit_lore")).build(), pl -> ChatInput.ask(pl, "warps.ask_lore", txt -> {
            c.lore = txt.equals("-") ? new ArrayList<>() : new ArrayList<>(List.of(txt.split("\\|")));
            Cfg.saveShopCategory(c);
            categorySettings(pl, c);
        }, () -> categorySettings(pl, c)));
        g.button(13, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("shop.editor_cat_discount", "percent", fmt(c.discount))).build(), pl -> ChatInput.ask(pl, "shop.ask_discount", txt -> {
            Long v = ChatInput.parseAmount(txt.replace("%", ""));
            if (v != null) c.discount = Math.min(100, v);
            Cfg.saveShopCategory(c);
            categorySettings(pl, c);
        }, () -> categorySettings(pl, c)));
        g.button(14, ItemBuilder.of(Items.HOPPER).name(Msg.format("warps.edit_slot", "slot", c.slot < 0 ? "auto" : String.valueOf(c.slot))).build(),
                pl -> ChatInput.ask(pl, "warps.ask_slot", txt -> {
                    Long v = ChatInput.parseAmount(txt);
                    c.slot = v == null ? -1 : (int) Math.min(53, v);
                    Cfg.saveShopCategory(c);
                    categorySettings(pl, c);
                }, () -> categorySettings(pl, c)));
        g.button(16, ItemBuilder.of(Items.LAVA_BUCKET).name(Msg.format("shop.editor_cat_delete")).lore(Msg.format("warps.edit_delete_lore")).build(), (pl, click) -> {
            if (!click.shift()) {
                Msg.send(pl, "warps.edit_delete_shift");
                return;
            }
            Cfg.deleteShopCategory(c.id);
            editor(pl);
        });
        g.button(18, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), pl -> editCategory(pl, c, 0));
        g.open(p);
    }

    public static void editEntry(ServerPlayer p, ShopConfig.Category c, ShopConfig.Entry e) {
        Gui g = new Gui(3, Msg.format("shop.editor_entry_title", "id", e.id));
        g.fill(Gui.pane());
        g.set(4, entryIcon(p, c, e));
        g.button(10, ItemBuilder.of(Items.EMERALD).name(Msg.format("shop.editor_buy", "money", e.buy < 0 ? "-" : Economy.format(e.buy))).build(),
                pl -> ChatInput.ask(pl, "shop.ask_price", txt -> {
                    Long v = txt.trim().equals("-") ? Long.valueOf(-1) : ChatInput.parseAmount(txt);
                    if (v != null) e.buy = v;
                    Cfg.saveShopCategory(c);
                    editEntry(pl, c, e);
                }, () -> editEntry(pl, c, e)));
        g.button(11, ItemBuilder.of(Items.GOLD_NUGGET).name(Msg.format("shop.editor_sell", "money", e.sell < 0 ? "-" : Economy.format(e.sell))).build(),
                pl -> ChatInput.ask(pl, "shop.ask_price", txt -> {
                    Long v = txt.trim().equals("-") ? Long.valueOf(-1) : ChatInput.parseAmount(txt);
                    if (v != null) e.sell = v;
                    Cfg.saveShopCategory(c);
                    editEntry(pl, c, e);
                }, () -> editEntry(pl, c, e)));
        g.button(12, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("shop.editor_entry_discount", "percent", fmt(e.discount))).build(),
                pl -> ChatInput.ask(pl, "shop.ask_discount", txt -> {
                    Long v = ChatInput.parseAmount(txt.replace("%", ""));
                    if (v != null) e.discount = Math.min(100, v);
                    Cfg.saveShopCategory(c);
                    editEntry(pl, c, e);
                }, () -> editEntry(pl, c, e)));
        g.button(13, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("warps.edit_name")).build(), pl -> ChatInput.ask(pl, "kits.ask_display", txt -> {
            e.display = txt.equals("-") ? "" : txt;
            Cfg.saveShopCategory(c);
            editEntry(pl, c, e);
        }, () -> editEntry(pl, c, e)));
        if ("item".equals(e.type)) {
            g.button(14, ItemBuilder.of(Items.ITEM_FRAME).name(Msg.format("shop.editor_replace_item")).build(), pl -> {
                if (!pl.getMainHandItem().isEmpty()) e.item = ItemSerial.toSnbt(pl.getMainHandItem());
                Cfg.saveShopCategory(c);
                editEntry(pl, c, e);
            });
        } else if ("pokemon".equals(e.type)) {
            g.button(14, ItemBuilder.of(Items.EGG).name(Msg.format("shop.editor_edit_pokemon")).lore(Msg.format("shop.editor_add_pokemon_lore")).build(),
                    pl -> ChatInput.ask(pl, "shop.ask_pokemon", txt -> {
                        MonSpec m = MonSpecParser.parse(txt);
                        if (m != null) e.pokemon = m;
                        else Msg.send(pl, "shop.bad_pokemon");
                        Cfg.saveShopCategory(c);
                        editEntry(pl, c, e);
                    }, () -> editEntry(pl, c, e)));
        } else {
            g.button(14, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.format("kits.edit_commands", "count", e.commands.size()))
                    .lore(e.commands.stream().map(x -> "&7/" + x).toList()).lore(Msg.format("kits.edit_commands_lore")).build(), (pl, click) -> {
                if (click.right() || click.shift()) {
                    e.commands.clear();
                    Cfg.saveShopCategory(c);
                    editEntry(pl, c, e);
                    return;
                }
                ChatInput.ask(pl, "kits.ask_command", txt -> {
                    e.commands.add(txt.startsWith("/") ? txt.substring(1) : txt);
                    Cfg.saveShopCategory(c);
                    editEntry(pl, c, e);
                }, () -> editEntry(pl, c, e));
            });
        }
        g.button(15, ItemBuilder.of(Items.HOPPER).name(Msg.format("shop.editor_move")).lore(Msg.format("shop.editor_move_lore")).build(), (pl, click) -> {
            int i = c.entries.indexOf(e);
            int j = click.right() ? i + 1 : i - 1;
            if (i >= 0 && j >= 0 && j < c.entries.size()) {
                c.entries.remove(i);
                c.entries.add(j, e);
                Cfg.saveShopCategory(c);
            }
            editEntry(pl, c, e);
        });
        g.button(16, ItemBuilder.of(Items.LAVA_BUCKET).name(Msg.format("shop.editor_entry_delete")).lore(Msg.format("warps.edit_delete_lore")).build(), (pl, click) -> {
            if (!click.shift()) {
                Msg.send(pl, "warps.edit_delete_shift");
                return;
            }
            c.entries.remove(e);
            Cfg.saveShopCategory(c);
            editCategory(pl, c, 0);
        });
        g.button(18, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), pl -> editCategory(pl, c, 0));
        g.open(p);
    }
}
