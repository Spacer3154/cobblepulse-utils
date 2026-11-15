package fr.cobblepulse.utils.kits;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.KitsConfig;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.essentials.EssentialsCommands;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import fr.cobblepulse.utils.staff.StaffPanel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.argument;

/** Kits filtres par permission (cobblepulse.kit.<nom>), delais (cobblepulse.kit.bypasscooldown), editeur en jeu. */
public final class Kits {
    private Kits() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "kit", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, Kits::open))
                .then(argument("nom", StringArgumentType.word()).suggests((c, b) -> {
                    ServerPlayer p = c.getSource().getPlayer();
                    List<String> names = new ArrayList<>();
                    for (KitsConfig.Kit k : Cfg.kits.kits) if (p == null || canUse(p, k)) names.add(k.name);
                    return SharedSuggestionProvider.suggest(names, b);
                }).executes(ctx -> Cmd.asPlayer(ctx, p -> {
                    KitsConfig.Kit k = Cfg.kits.find(StringArgumentType.getString(ctx, "nom"));
                    if (k == null || !canUse(p, k)) {
                        Msg.send(p, "kits.unknown", "kit", StringArgumentType.getString(ctx, "nom"));
                        return 0;
                    }
                    return claim(p, k) ? 1 : 0;
                }))));
    }

    public static boolean canUse(ServerPlayer p, KitsConfig.Kit k) {
        Boolean v = LuckPermsBridge.check(p, "cobblepulse.kit." + k.name);
        if (v != null) return v;
        return k.defaultAccess || p.hasPermissions(Cfg.general.opLevel);
    }

    private static String key(KitsConfig.Kit k) {
        return "kit:" + k.name;
    }

    public static List<ItemStack> items(KitsConfig.Kit k) {
        List<ItemStack> out = new ArrayList<>();
        for (String s : k.items) {
            ItemStack st = ItemSerial.fromSnbt(s);
            if (!st.isEmpty()) out.add(st);
        }
        return out;
    }

    /** Etat d'un kit pour un joueur (texte de lore). */
    private static String status(ServerPlayer p, KitsConfig.Kit k) {
        PlayerData d = PlayerStore.get(p);
        if (k.oneTime && d.kitsClaimed.contains(k.name)) return Msg.format("kits.status_claimed");
        long left = d.cooldownLeft(key(k));
        if (left > 0 && !Perms.perk(p, Perms.KIT_BYPASS)) return Msg.format("kits.status_cooldown", "time", TimeFmt.duration(left));
        return Msg.format("kits.status_ready");
    }

    public static void open(ServerPlayer p) {
        List<Paged.Entry> entries = new ArrayList<>();
        for (KitsConfig.Kit k : Cfg.kits.kits) {
            if (!canUse(p, k)) continue;
            ItemBuilder b = ItemBuilder.of(Reg.stack(k.icon)).name(k.display).lore(k.lore);
            b.lore("", Msg.format("kits.icon_cooldown", "time", k.oneTime ? Msg.format("kits.one_time") : k.cooldownSeconds == 0
                    ? Msg.format("kits.no_cooldown") : TimeFmt.duration(k.cooldownSeconds * 1000)));
            b.lore(status(p, k), "", Msg.format("kits.icon_click"));
            entries.add(new Paged.Entry(b.hideExtra().build(), (pl, c) -> {
                if (c.right()) preview(pl, k);
                else if (claim(pl, k)) open(pl);
            }));
        }
        Paged.open(p, Msg.format("kits.title"), entries, 0, Hub::open, gui -> {
            if (entries.isEmpty()) gui.set(22, ItemBuilder.of(Items.BARRIER).name(Msg.format("kits.none")).build());
            if (Perms.admin(p, "kits")) gui.button(51, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("kits.editor_button")).build(), Kits::editor);
        });
    }

    public static void preview(ServerPlayer p, KitsConfig.Kit k) {
        List<ItemStack> items = items(k);
        Gui g = new Gui(Math.max(2, Math.min(6, (items.size() + 8) / 9 + 1)), Msg.format("kits.preview_title", "kit", TextUtil.strip(k.display)));
        for (int i = 0; i < items.size() && i < g.size() - 9; i++) g.set(i, items.get(i));
        if (!k.commands.isEmpty()) {
            g.set(g.size() - 9, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.format("kits.preview_commands")).lore(k.commands.stream().map(c -> "&7/" + c).toList()).build());
        }
        g.button(g.size() - 5, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Kits::open);
        g.open(p);
    }

    public static boolean claim(ServerPlayer p, KitsConfig.Kit k) {
        if (!canUse(p, k)) {
            Msg.send(p, "general.no_permission");
            return false;
        }
        PlayerData d = PlayerStore.get(p);
        if (k.oneTime && d.kitsClaimed.contains(k.name)) {
            Msg.send(p, "kits.already", "kit", TextUtil.strip(k.display));
            return false;
        }
        long left = d.cooldownLeft(key(k));
        if (left > 0 && !Perms.perk(p, Perms.KIT_BYPASS)) {
            Msg.send(p, "kits.cooldown", "kit", TextUtil.strip(k.display), "time", TimeFmt.duration(left));
            return false;
        }
        int dropped = 0;
        for (ItemStack st : items(k)) {
            ItemStack copy = st.copy();
            if (!p.getInventory().add(copy) || !copy.isEmpty()) {
                p.drop(copy, false);
                dropped++;
            }
        }
        for (String c : k.commands) {
            String cmd = c.replace("%player%", p.getGameProfile().getName()).replace("%uuid%", p.getUUID().toString());
            CobbleUtils.server().getCommands().performPrefixedCommand(CobbleUtils.server().createCommandSourceStack(), cmd.startsWith("/") ? cmd.substring(1) : cmd);
        }
        if (k.oneTime) d.kitsClaimed.add(k.name);
        if (k.cooldownSeconds > 0) d.setCooldown(key(k), k.cooldownSeconds * 1000);
        PlayerStore.dirty(p.getUUID());
        PlayerStore.save(p.getUUID());
        Players.saveNow(p);
        Msg.send(p, "kits.claimed", "kit", TextUtil.strip(k.display));
        if (dropped > 0) Msg.send(p, "kits.dropped", "count", dropped);
        return true;
    }

    // ------------------------------------------------------------------ editeur

    public static void editor(ServerPlayer p) {
        if (!Perms.admin(p, "kits")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        List<Paged.Entry> entries = new ArrayList<>();
        for (KitsConfig.Kit k : Cfg.kits.kits) {
            entries.add(new Paged.Entry(ItemBuilder.of(Reg.stack(k.icon)).name(k.display)
                    .lore(Msg.format("kits.editor_info", "name", k.name, "items", k.items.size(), "commands", k.commands.size(),
                            "cooldown", k.oneTime ? Msg.format("kits.one_time") : TimeFmt.duration(k.cooldownSeconds * 1000),
                            "access", k.defaultAccess ? Msg.format("kits.access_all") : "cobblepulse.kit." + k.name))
                    .lore("", Msg.format("kits.editor_click")).hideExtra().build(), (pl, c) -> edit(pl, k)));
        }
        Paged.open(p, Msg.format("kits.editor_title"), entries, 0, StaffPanel::open, gui -> gui.button(51,
                ItemBuilder.of(Items.LIME_DYE).name(Msg.format("kits.editor_new")).build(),
                pl -> ChatInput.ask(pl, "kits.ask_name", name -> {
                    String n = name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
                    if (n.isEmpty() || Cfg.kits.find(n) != null) {
                        Msg.send(pl, "kits.bad_name");
                        editor(pl);
                        return;
                    }
                    KitsConfig.Kit k = new KitsConfig.Kit();
                    k.name = n;
                    k.display = "&b" + n;
                    Cfg.kits.kits.add(k);
                    save();
                    edit(pl, k);
                })));
    }

    private static void save() {
        Cfg.kits.normalize();
        Cfg.save("kits.json", Cfg.kits);
    }

    public static void edit(ServerPlayer p, KitsConfig.Kit k) {
        Gui g = new Gui(4, Msg.format("kits.edit_title", "kit", k.name));
        g.fill(Gui.pane());
        g.set(4, ItemBuilder.of(Reg.stack(k.icon)).name(k.display).lore(k.lore).hideExtra().build());
        g.button(10, ItemBuilder.of(Items.CHEST).name(Msg.format("kits.edit_items")).lore(Msg.format("kits.edit_items_lore", "count", k.items.size())).build(),
                pl -> editContents(pl, k));
        g.button(11, ItemBuilder.of(Items.ITEM_FRAME).name(Msg.format("kits.edit_icon")).build(), pl -> {
            if (pl.getMainHandItem().isEmpty()) {
                Msg.send(pl, "warps.edit_icon_empty");
                return;
            }
            k.icon = Reg.id(pl.getMainHandItem().getItem());
            save();
            edit(pl, k);
        });
        g.button(12, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("kits.edit_name")).build(), pl -> ChatInput.ask(pl, "kits.ask_display", txt -> {
            k.display = txt;
            save();
            edit(pl, k);
        }, () -> edit(pl, k)));
        g.button(13, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("kits.edit_lore")).build(), pl -> ChatInput.ask(pl, "warps.ask_lore", txt -> {
            k.lore = txt.equals("-") ? new ArrayList<>() : new ArrayList<>(List.of(txt.split("\\|")));
            save();
            edit(pl, k);
        }, () -> edit(pl, k)));
        g.button(14, ItemBuilder.of(Items.CLOCK).name(Msg.format("kits.edit_cooldown", "time", TimeFmt.duration(k.cooldownSeconds * 1000))).build(),
                pl -> ChatInput.ask(pl, "kits.ask_cooldown", txt -> {
                    Long s = ChatInput.parseDurationSeconds(txt);
                    if (s != null) k.cooldownSeconds = s;
                    save();
                    edit(pl, k);
                }, () -> edit(pl, k)));
        g.button(15, ItemBuilder.of(k.oneTime ? Items.LIME_DYE : Items.GRAY_DYE).name(Msg.format(k.oneTime ? "kits.edit_once_on" : "kits.edit_once_off")).build(), pl -> {
            k.oneTime = !k.oneTime;
            save();
            edit(pl, k);
        });
        g.button(16, ItemBuilder.of(k.defaultAccess ? Items.OAK_FENCE_GATE : Items.IRON_BARS)
                .name(Msg.format(k.defaultAccess ? "kits.edit_access_all" : "kits.edit_access_perm", "name", k.name)).build(), pl -> {
            k.defaultAccess = !k.defaultAccess;
            save();
            edit(pl, k);
        });
        g.button(20, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.format("kits.edit_commands", "count", k.commands.size()))
                .lore(k.commands.stream().map(c -> "&7/" + c).toList()).lore(Msg.format("kits.edit_commands_lore")).build(), (pl, c) -> {
            if (c.right() || c.shift()) {
                k.commands.clear();
                save();
                edit(pl, k);
                return;
            }
            ChatInput.ask(pl, "kits.ask_command", txt -> {
                k.commands.add(txt.startsWith("/") ? txt.substring(1) : txt);
                save();
                edit(pl, k);
            }, () -> edit(pl, k));
        });
        g.button(22, ItemBuilder.of(Items.HOPPER).name(Msg.format("warps.edit_slot", "slot", k.slot < 0 ? "auto" : String.valueOf(k.slot))).build(),
                pl -> ChatInput.ask(pl, "warps.ask_slot", txt -> {
                    Long v = ChatInput.parseAmount(txt);
                    k.slot = v == null ? -1 : (int) Math.min(44, v);
                    save();
                    edit(pl, k);
                }, () -> edit(pl, k)));
        g.button(24, ItemBuilder.of(Items.LAVA_BUCKET).name(Msg.format("kits.edit_delete")).lore(Msg.format("warps.edit_delete_lore")).build(), (pl, c) -> {
            if (!c.shift()) {
                Msg.send(pl, "warps.edit_delete_shift");
                return;
            }
            Cfg.kits.kits.remove(k);
            save();
            Msg.send(pl, "kits.deleted", "kit", k.name);
            editor(pl);
        });
        g.button(27, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Kits::editor);
        g.open(p);
    }

    /** Coffre modifiable : le contenu (NBT complet, enchantements compris) devient celui du kit a la fermeture. */
    public static void editContents(ServerPlayer p, KitsConfig.Kit k) {
        SimpleContainer c = new SimpleContainer(54);
        List<ItemStack> items = items(k);
        for (int i = 0; i < items.size() && i < 54; i++) c.setItem(i, items.get(i));
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(MenuType.GENERIC_9x6, id, inv, c, 6) {
            @Override
            public void removed(Player player) {
                super.removed(player);
                List<String> out = new ArrayList<>();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.isEmpty()) out.add(ItemSerial.toSnbt(s));
                }
                k.items = out;
                save();
                if (player instanceof ServerPlayer sp) {
                    Msg.send(sp, "kits.contents_saved", "kit", k.name, "count", out.size());
                    fr.cobblepulse.utils.core.Scheduler.next(() -> edit(sp, k));
                }
            }
        }, TextUtil.legacy(Msg.format("kits.contents_title", "kit", k.name))));
    }
}
