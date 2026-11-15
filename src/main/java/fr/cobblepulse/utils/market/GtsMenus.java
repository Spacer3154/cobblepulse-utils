package fr.cobblepulse.utils.market;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.PokemonText;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.argument;

/** Interfaces et commandes du GTS. */
public final class GtsMenus {
    private GtsMenus() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "gts", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, GtsMenus::open))
                .then(Cmd.lit("sell").requires(src -> !src.isPlayer() || Perms.sub(src.getPlayer(), "gts", "sell"))
                        .then(argument("prix", LongArgumentType.longArg(1)).executes(ctx -> Cmd.run(ctx, p ->
                                        Gts.listHand(p, LongArgumentType.getLong(ctx, "prix"), ok -> {
                                        })))
                                .then(argument("duree", StringArgumentType.word()).suggests(DURATIONS).executes(ctx -> Cmd.run(ctx, p ->
                                        Gts.listHand(p, LongArgumentType.getLong(ctx, "prix"), hours(StringArgumentType.getString(ctx, "duree")), ok -> {
                                        }))))))
                .then(Cmd.lit("sellpoke").requires(src -> !src.isPlayer() || Perms.sub(src.getPlayer(), "gts", "sellpoke"))
                        .then(argument("slot", IntegerArgumentType.integer(1, 6)).then(argument("prix", LongArgumentType.longArg(1))
                                .executes(ctx -> Cmd.run(ctx, p -> Gts.listPokemon(p, IntegerArgumentType.getInteger(ctx, "slot") - 1,
                                        LongArgumentType.getLong(ctx, "prix"), ok -> {
                                        })))
                                .then(argument("duree", StringArgumentType.word()).suggests(DURATIONS).executes(ctx -> Cmd.run(ctx, p ->
                                        Gts.listPokemon(p, IntegerArgumentType.getInteger(ctx, "slot") - 1, LongArgumentType.getLong(ctx, "prix"),
                                                hours(StringArgumentType.getString(ctx, "duree")), ok -> {
                                                })))))))
                .then(Cmd.lit("listings").executes(ctx -> Cmd.run(ctx, GtsMenus::mine)))
                .then(Cmd.lit("collect").executes(ctx -> Cmd.run(ctx, GtsMenus::mailbox))));
    }

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> DURATIONS = (ctx, b) -> {
        ServerPlayer p = ctx.getSource().getPlayer();
        List<String> out = new ArrayList<>();
        if (p != null) for (int h : Gts.durationChoices(p)) out.add(h % 24 == 0 && h >= 48 ? (h / 24) + "j" : h + "h");
        return net.minecraft.commands.SharedSuggestionProvider.suggest(out, b);
    };

    /** « 72h », « 7j », « 3d » ou un nombre d'heures. */
    static int hours(String s) {
        Long sec = ChatInput.parseDurationSeconds(s.matches("\\d+") ? s + "h" : s);
        return sec == null ? -1 : (int) (sec / 3600);
    }

    /** Choix de la duree de l'annonce (plus longue = plus taxee), puis mise en vente. */
    public static void chooseDuration(ServerPlayer p, long price, String what, java.util.function.IntConsumer then) {
        List<Integer> choices = Gts.durationChoices(p);
        if (choices.size() <= 1) {
            then.accept(choices.isEmpty() ? Gts.durationHours(p) : choices.get(0));
            return;
        }
        Gui g = new Gui(3, Msg.format("gts.duration_title", "money", Economy.format(price)));
        g.fill(Gui.pane());
        g.set(4, ItemBuilder.of(Items.PAPER).name(Msg.format("gts.duration_item", "item", what)).lore(Msg.format("gts.duration_price", "money", Economy.format(price))).build());
        int base = Gts.durationHours(p);
        int slot = choices.size() <= 7 ? 9 + (9 - choices.size()) / 2 : 9;
        for (int h : choices) {
            if (slot > 17) break;
            int tax = Gts.taxFor(p, h);
            int extra = tax - Gts.tax(p);
            long net = price - price * tax / 100;
            ItemBuilder b = ItemBuilder.of(h <= base ? Items.LIME_DYE : extra >= 10 ? Items.ORANGE_DYE : Items.YELLOW_DYE)
                    .name(Msg.format("gts.duration_option", "duration", Gts.durationText(h)))
                    .lore(Msg.format("gts.duration_tax", "tax", tax, "extra", extra > 0 ? Msg.format("gts.duration_extra", "extra", extra) : Msg.format("gts.duration_included")),
                            Msg.format("gts.duration_net", "money", Economy.format(net)), "", Msg.format("gts.duration_click"));
            g.button(slot++, b.build(), pl -> then.accept(h));
        }
        g.button(22, ItemBuilder.of(Items.RED_CONCRETE).name(Msg.format("general.cancel")).build(), GtsMenus::open);
        g.open(p);
    }

    /** filtre : 0 tout, 1 objets, 2 Pokemon. */
    public static void open(ServerPlayer p) {
        browse(p, 0, 0);
    }

    public static void browse(ServerPlayer p, int filter, int page) {
        if (!Gts.open() && !Perms.admin(p, "gts")) {
            Msg.send(p, "gts.closed");
            return;
        }
        Gts.active().whenComplete((list, t) -> Scheduler.next(() -> {
            if (p.hasDisconnected()) return;
            if (t != null) {
                Msg.send(p, "general.db_error");
                return;
            }
            boolean staff = Perms.admin(p, "gts");
            List<Paged.Entry> entries = new ArrayList<>();
            for (Gts.Listing l : list) {
                if (filter == 1 && l.pokemon() || filter == 2 && !l.pokemon()) continue;
                List<String> extra = new ArrayList<>();
                extra.add(Msg.format("gts.icon_price", "money", Economy.format(l.price())));
                extra.add(Msg.format("gts.icon_seller", "player", l.sellerName()));
                extra.add(Msg.format("gts.icon_time", "time", Gts.timeLeft(l)));
                extra.add("");
                extra.add(Msg.format(l.seller().equals(p.getUUID()) ? "gts.icon_own" : "gts.icon_click"));
                if (staff) extra.add(Msg.format("gts.icon_staff"));
                entries.add(new Paged.Entry(Gts.icon(l, extra.toArray(new String[0])), (pl, c) -> {
                    if (staff && c.shift() && c.type() == net.minecraft.world.inventory.ClickType.QUICK_MOVE) {
                        Gts.cancel(pl, l, true, ok -> browse(pl, filter, page));
                    } else if (l.seller().equals(pl.getUUID())) {
                        confirmCancel(pl, l);
                    } else {
                        confirmBuy(pl, l, filter, page);
                    }
                }));
            }
            String title = Msg.format("gts.title", "count", entries.size()) + (Gts.open() ? "" : " " + Msg.format("shop.title_closed"));
            Paged.open(p, title, entries, page, Hub::open, gui -> {
                gui.button(45, ItemBuilder.of(filter == 0 ? Items.NETHER_STAR : filter == 1 ? Items.CHEST : Items.EGG)
                        .name(Msg.format("gts.filter", "filter", Msg.format("gts.filter_" + filter))).lore(Msg.format("gts.filter_lore")).build(),
                        pl -> browse(pl, (filter + 1) % 3, 0));
                gui.button(46, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("gts.sell_hand")).lore(Msg.format("gts.sell_hand_lore",
                        "tax", Gts.tax(p), "hours", Gts.durationHours(p), "max", Gts.limit(p))).build(), GtsMenus::askSellHand);
                gui.button(47, ItemBuilder.of(Items.EGG).name(Msg.format("gts.sell_pokemon")).build(), GtsMenus::choosePokemon);
                gui.button(48, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
                gui.button(50, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("gts.my_listings")).build(), GtsMenus::mine);
                gui.button(51, ItemBuilder.of(Items.ENDER_CHEST).name(Msg.format("gts.mailbox")).lore(Msg.format("gts.mailbox_lore")).build(), GtsMenus::mailbox);
                gui.button(52, ItemBuilder.of(Items.BOOK).name(Msg.format("gts.history")).build(), GtsMenus::history);
                gui.set(49, ItemBuilder.of(Items.GOLD_NUGGET).name(Msg.format("hub.balance", "money", Economy.format(Economy.balance(p.getUUID())))).build());
            });
        }));
    }

    private static void confirmBuy(ServerPlayer p, Gts.Listing l, int filter, int page) {
        Gui g = new Gui(3, Msg.format("gts.confirm_title", "money", Economy.format(l.price())));
        g.fill(Gui.pane());
        g.set(13, Gts.icon(l, Msg.format("gts.icon_price", "money", Economy.format(l.price())), Msg.format("gts.icon_seller", "player", l.sellerName())));
        g.button(11, ItemBuilder.of(Items.LIME_CONCRETE).name(Msg.format("gts.confirm_buy", "money", Economy.format(l.price()))).build(), pl -> {
            fr.cobblepulse.utils.gui.Gui.close(pl);
            Gts.buy(pl, l, ok -> browse(pl, filter, page));
        });
        g.button(15, ItemBuilder.of(Items.RED_CONCRETE).name(Msg.format("general.cancel")).build(), pl -> browse(pl, filter, page));
        g.open(p);
    }

    private static void confirmCancel(ServerPlayer p, Gts.Listing l) {
        Gui g = new Gui(3, Msg.format("gts.cancel_title"));
        g.fill(Gui.pane());
        g.set(13, Gts.icon(l, Msg.format("gts.icon_price", "money", Economy.format(l.price()))));
        g.button(11, ItemBuilder.of(Items.LIME_CONCRETE).name(Msg.format("gts.confirm_cancel")).build(), pl -> {
            fr.cobblepulse.utils.gui.Gui.close(pl);
            Gts.cancel(pl, l, false, ok -> mine(pl));
        });
        g.button(15, ItemBuilder.of(Items.RED_CONCRETE).name(Msg.format("general.back")).build(), GtsMenus::mine);
        g.open(p);
    }

    private static void askSellHand(ServerPlayer p) {
        if (p.getMainHandItem().isEmpty()) {
            Msg.send(p, "gts.hand_empty");
            return;
        }
        if (!Perms.sub(p, "gts", "sell")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        ChatInput.ask(p, "gts.ask_price", txt -> {
            Long v = ChatInput.parseAmount(txt);
            if (v == null || v <= 0) {
                Msg.send(p, "gts.bad_price");
                return;
            }
            String what = p.getMainHandItem().getCount() + " × " + p.getMainHandItem().getHoverName().getString();
            chooseDuration(p, v, what, h -> {
                Gui.close(p);
                Gts.listHand(p, v, h, ok -> {
                    if (ok) mine(p);
                });
            });
        }, "item", p.getMainHandItem().getCount() + " × " + p.getMainHandItem().getHoverName().getString());
    }

    private static void choosePokemon(ServerPlayer p) {
        if (!Perms.sub(p, "gts", "sellpoke")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        Gui g = new Gui(3, Msg.format("gts.choose_pokemon"));
        g.fill(Gui.pane());
        var party = PokemonText.party(p);
        for (int i = 0; i < 6; i++) {
            Pokemon pk = party.get(i);
            int slot = i;
            if (pk == null) {
                g.set(10 + i, ItemBuilder.of(Items.LIGHT_GRAY_STAINED_GLASS_PANE).name(Msg.format("gts.empty_slot", "slot", i + 1)).build());
                continue;
            }
            g.button(10 + i, PokemonText.icon(pk, Msg.format("gts.floor_hint", "money",
                    Economy.format(Cfg.gts.priceFloors.getOrDefault(PokemonText.category(pk), 1L))), Msg.format("gts.choose_click")), pl ->
                    ChatInput.ask(pl, "gts.ask_price", txt -> {
                        Long v = ChatInput.parseAmount(txt);
                        if (v == null || v <= 0) {
                            Msg.send(pl, "gts.bad_price");
                            return;
                        }
                        chooseDuration(pl, v, PokemonText.title(pk).getString(), h -> {
                            Gui.close(pl);
                            Gts.listPokemon(pl, slot, v, h, ok -> {
                                if (ok) mine(pl);
                            });
                        });
                    }, "item", PokemonText.title(pk).getString()));
        }
        g.button(22, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), GtsMenus::open);
        g.open(p);
    }

    public static void mine(ServerPlayer p) {
        Gts.mine(p.getUUID()).whenComplete((list, t) -> Scheduler.next(() -> {
            if (p.hasDisconnected() || t != null) return;
            List<Paged.Entry> entries = new ArrayList<>();
            for (Gts.Listing l : list) {
                entries.add(new Paged.Entry(Gts.icon(l, Msg.format("gts.icon_price", "money", Economy.format(l.price())),
                        Msg.format("gts.icon_time", "time", Gts.timeLeft(l)), "", Msg.format("gts.icon_own")), (pl, c) -> confirmCancel(pl, l)));
            }
            Paged.open(p, Msg.format("gts.my_listings_title", "count", list.size(), "max", Gts.limit(p)), entries, 0, GtsMenus::open);
        }));
    }

    public static void mailbox(ServerPlayer p) {
        Mailbox.list(p.getUUID()).whenComplete((mails, t) -> Scheduler.next(() -> {
            if (p.hasDisconnected() || t != null) return;
            List<Paged.Entry> entries = new ArrayList<>();
            for (Mailbox.Mail m : mails) {
                net.minecraft.world.item.ItemStack icon;
                switch (m.kind()) {
                    case "MONEY" -> icon = ItemBuilder.of(Items.GOLD_NUGGET).name("&a" + Economy.format(m.amount())).build();
                    case "POKEMON" -> {
                        Pokemon pk = PokemonText.fromJson(m.data());
                        icon = pk == null ? ItemBuilder.of(Items.BARRIER).name("&c?").build() : PokemonText.icon(pk);
                    }
                    default -> {
                        var st = fr.cobblepulse.utils.core.ItemSerial.decodeOne(m.data());
                        icon = st.isEmpty() ? ItemBuilder.of(Items.BARRIER).name("&c?").build() : st.copyWithCount(Math.max(1, Math.min(st.getMaxStackSize(), st.getCount())));
                    }
                }
                icon = ItemBuilder.of(icon).lore("", "&7" + m.reason(), "&8" + TimeFmt.date(m.created()), "", Msg.format("gts.mail_click")).build();
                entries.add(new Paged.Entry(icon, (pl, c) -> Mailbox.claim(pl, m, ok -> mailbox(pl))));
            }
            Paged.open(p, Msg.format("gts.mailbox_title", "count", mails.size()), entries, 0, GtsMenus::open, gui -> {
                if (!mails.isEmpty()) gui.button(51, ItemBuilder.of(Items.HOPPER).name(Msg.format("gts.mail_claim_all")).build(),
                        pl -> Gts.claimAllNow(pl, () -> mailbox(pl)));
            });
        }));
    }

    public static void history(ServerPlayer p) {
        Gts.history(p.getUUID()).whenComplete((list, t) -> Scheduler.next(() -> {
            if (p.hasDisconnected() || t != null) return;
            List<Paged.Entry> entries = new ArrayList<>();
            for (Gts.Listing l : list) {
                boolean sold = l.seller().equals(p.getUUID());
                String line = switch (l.status()) {
                    case "SOLD" -> sold ? Msg.format("gts.hist_sold", "player", l.buyerName(), "money", Economy.format(l.price() - l.tax()))
                            : Msg.format("gts.hist_bought", "player", l.sellerName(), "money", Economy.format(l.price()));
                    case "EXPIRED" -> Msg.format("gts.hist_expired");
                    case "REMOVED" -> Msg.format("gts.hist_removed");
                    default -> Msg.format("gts.hist_cancelled");
                };
                entries.add(new Paged.Entry(Gts.icon(l, line, "&8" + TimeFmt.date(l.closedAt())), null));
            }
            Paged.open(p, Msg.format("gts.history_title"), entries, 0, GtsMenus::open);
        }));
    }
}
