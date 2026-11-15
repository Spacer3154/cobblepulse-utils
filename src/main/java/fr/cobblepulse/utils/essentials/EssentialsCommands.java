package fr.cobblepulse.utils.essentials;

import com.cobblemon.mod.common.api.permission.CobblemonPermissions;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.pc.link.PCLink;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.net.messages.client.storage.pc.OpenPCPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.teleport.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;

/** Commandes de confort, sociales, economiques simples et de moderation (§4.3, §4.4, §4.5). */
public final class EssentialsCommands {
    private EssentialsCommands() {}

    private static boolean otherAllowed(CommandSourceStack src, String name) {
        return !src.isPlayer() || Perms.admin(src.getPlayer(), name + ".others") || Perms.admin(src.getPlayer(), name);
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        // ---- confort
        Cmd.player(d, "fly", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> fly(ctx.getSource(), p)))
                .then(argument("joueur", EntityArgument.player()).requires(src -> otherAllowed(src, "fly"))
                        .executes(ctx -> fly(ctx.getSource(), EntityArgument.getPlayer(ctx, "joueur")))));
        Cmd.player(d, "heal", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> heal(ctx.getSource(), p)))
                .then(argument("joueur", EntityArgument.player()).requires(src -> otherAllowed(src, "heal"))
                        .executes(ctx -> heal(ctx.getSource(), EntityArgument.getPlayer(ctx, "joueur")))));
        Cmd.player(d, "feed", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> feed(ctx.getSource(), p)))
                .then(argument("joueur", EntityArgument.player()).requires(src -> otherAllowed(src, "feed"))
                        .executes(ctx -> feed(ctx.getSource(), EntityArgument.getPlayer(ctx, "joueur")))));
        Cmd.player(d, "hat", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            ItemStack hand = p.getMainHandItem();
            if (hand.isEmpty()) {
                Msg.send(p, "misc.hat_empty");
                return 0;
            }
            ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
            p.setItemSlot(EquipmentSlot.HEAD, hand.copyWithCount(1));
            hand.shrink(1);
            if (!head.isEmpty() && !p.getInventory().add(head)) p.drop(head, false);
            Msg.send(p, "misc.hat_done");
            return 1;
        })));
        Cmd.player(d, "trash", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            openTrash(p);
            return 1;
        })));
        Cmd.player(d, "repair", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> repair(p, false)))
                .then(Cmd.lit("all").executes(ctx -> Cmd.asPlayer(ctx, p -> repair(p, true)))));
        Cmd.player(d, "craft", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new CraftingMenu(id, inv, ContainerLevelAccess.create(p.level(), p.blockPosition())) {
                @Override
                public boolean stillValid(Player player) {
                    return true;
                }
            }, Component.translatable("container.crafting")));
            return 1;
        })));
        Cmd.player(d, "anvil", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new AnvilMenu(id, inv, ContainerLevelAccess.create(p.level(), p.blockPosition())) {
                @Override
                public boolean stillValid(Player player) {
                    return true;
                }
            }, Component.translatable("container.repair")));
            return 1;
        })));

        // ---- social
        Cmd.player(d, "list", l -> Cmd.lit(l).executes(ctx -> list(ctx.getSource())));
        Cmd.player(d, "playtime", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> playtime(ctx.getSource(), p.getUUID())))
                .then(argument("joueur", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> {
                            UUID u = ChatFeatures.resolve(StringArgumentType.getString(ctx, "joueur"));
                            if (u == null) {
                                Msg.send(ctx.getSource(), "general.player_not_found", "player", StringArgumentType.getString(ctx, "joueur"));
                                return 0;
                            }
                            return playtime(ctx.getSource(), u);
                        })));
        Cmd.player(d, "pokeshout", l -> Cmd.lit(l).then(argument("slot", IntegerArgumentType.integer(1, 6)).executes(ctx -> Cmd.asPlayer(ctx, p ->
                pokeshout(p, IntegerArgumentType.getInteger(ctx, "slot"))))));
        Cmd.player(d, "pokeshoutall", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> pokeshout(p, 0))));
        Cmd.player(d, "pokeheal", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, EssentialsCommands::pokeheal)));
        Cmd.player(d, "pc", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> openPc(p, null)))
                .then(argument("boite", IntegerArgumentType.integer(1, 100)).executes(ctx -> Cmd.asPlayer(ctx, p ->
                        openPc(p, IntegerArgumentType.getInteger(ctx, "boite"))))));

        // ---- economie simple
        Cmd.player(d, "balance", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
                    Msg.send(p, "economy.balance", "money", Economy.format(Economy.balance(p.getUUID())));
                    return 1;
                }))
                .then(argument("joueur", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> {
                            String n = StringArgumentType.getString(ctx, "joueur");
                            UUID u = ChatFeatures.resolve(n);
                            if (u == null) {
                                Msg.send(ctx.getSource(), "general.player_not_found", "player", n);
                                return 0;
                            }
                            Msg.send(ctx.getSource(), "economy.balance_other", "player", Players.name(u), "money", Economy.format(Economy.balance(u)));
                            return 1;
                        })));
        Cmd.player(d, "pay", l -> Cmd.lit(l).then(argument("joueur", EntityArgument.player())
                .then(argument("montant", LongArgumentType.longArg(1)).executes(ctx -> Cmd.asPlayer(ctx, p -> {
                    ServerPlayer to = EntityArgument.getPlayer(ctx, "joueur");
                    long amount = LongArgumentType.getLong(ctx, "montant");
                    if (to == p) {
                        Msg.send(p, "economy.pay_self");
                        return 0;
                    }
                    if (!Economy.withdraw(p.getUUID(), amount)) {
                        Msg.send(p, "economy.not_enough", "money", Economy.format(amount));
                        return 0;
                    }
                    if (!Economy.deposit(to.getUUID(), amount)) {
                        Economy.deposit(p.getUUID(), amount);
                        Msg.send(p, "economy.error");
                        return 0;
                    }
                    Players.saveNow(p);
                    Players.saveNow(to);
                    Msg.send(p, "economy.paid", "player", to.getGameProfile().getName(), "money", Economy.format(amount));
                    Msg.send(to, "economy.received", "player", p.getGameProfile().getName(), "money", Economy.format(amount));
                    return 1;
                })))));

        // ---- staff
        Cmd.admin(d, "invsee", "invsee", l -> Cmd.lit(l).then(argument("joueur", EntityArgument.player()).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            ServerPlayer t = EntityArgument.getPlayer(ctx, "joueur");
            openInvsee(p, t);
            return 1;
        }))));
        Cmd.admin(d, "vanish", "vanish", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            Vanish.toggle(p);
            return 1;
        })));
        Cmd.admin(d, "god", "god", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> god(ctx.getSource(), p)))
                .then(argument("joueur", EntityArgument.player()).executes(ctx -> god(ctx.getSource(), EntityArgument.getPlayer(ctx, "joueur")))));
        Cmd.admin(d, "gamemode", "gamemode", l -> Cmd.lit(l).then(argument("mode", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("0", "1", "2", "3", "survie", "creatif", "aventure", "spectateur"), b))
                .executes(ctx -> Cmd.asPlayer(ctx, p -> gamemode(ctx.getSource(), p, StringArgumentType.getString(ctx, "mode"))))
                .then(argument("joueur", EntityArgument.player()).executes(ctx ->
                        gamemode(ctx.getSource(), EntityArgument.getPlayer(ctx, "joueur"), StringArgumentType.getString(ctx, "mode"))))));
        Cmd.admin(d, "speed", "speed", l -> Cmd.lit(l).then(argument("vitesse", IntegerArgumentType.integer(1, 10))
                .executes(ctx -> Cmd.asPlayer(ctx, p -> speed(p, IntegerArgumentType.getInteger(ctx, "vitesse"))))));
        Cmd.admin(d, "broadcast", "broadcast", l -> Cmd.lit(l).then(argument("message", StringArgumentType.greedyString()).executes(ctx -> {
            String m = StringArgumentType.getString(ctx, "message");
            Msg.broadcast(Msg.get("staff.broadcast", "message", m));
            for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                if (Msg.reachable(p)) p.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 0.8f, 1.2f);
            }
            return 1;
        })));
        Cmd.admin(d, "sudo", "sudo", l -> Cmd.lit(l).then(argument("joueur", EntityArgument.player())
                .then(argument("commande", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "joueur");
                    String c = StringArgumentType.getString(ctx, "commande");
                    if (c.startsWith("c:")) {
                        t.getServer().getPlayerList().broadcastSystemMessage(Component.empty().append("<").append(t.getDisplayName()).append("> ")
                                .append(Component.literal(c.substring(2).trim())), false);
                    } else {
                        t.getServer().getCommands().performPrefixedCommand(t.createCommandSourceStack(), c.startsWith("/") ? c.substring(1) : c);
                    }
                    Msg.send(ctx.getSource(), "staff.sudo_done", "player", t.getGameProfile().getName(), "command", c);
                    return 1;
                }))));
        Cmd.admin(d, "tempban", "sanctions", l -> Cmd.lit(l).then(argument("joueur", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerNames(), b))
                .then(argument("duree", StringArgumentType.word())
                        .executes(ctx -> tempban(ctx.getSource(), StringArgumentType.getString(ctx, "joueur"), StringArgumentType.getString(ctx, "duree"), ""))
                        .then(argument("raison", StringArgumentType.greedyString()).executes(ctx -> tempban(ctx.getSource(),
                                StringArgumentType.getString(ctx, "joueur"), StringArgumentType.getString(ctx, "duree"),
                                StringArgumentType.getString(ctx, "raison")))))));
        // Commandes vanilla de moderation accessibles avec les noeuds CobblePulse
        for (String v : new String[]{"kick", "ban", "pardon", "ban-ip", "pardon-ip"}) Cmd.allowAlso(d, v, Perms.adminReq("sanctions").and(CommandSourceStack::isPlayer));
        Cmd.allowAlso(d, "gamemode", Perms.adminReq("gamemode").and(CommandSourceStack::isPlayer));
    }

    // ------------------------------------------------------------------ confort

    private static int fly(CommandSourceStack src, ServerPlayer t) {
        boolean on = !t.getAbilities().mayfly;
        t.getAbilities().mayfly = on;
        if (!on) t.getAbilities().flying = false;
        t.onUpdateAbilities();
        Msg.send(t, on ? "misc.fly_on" : "misc.fly_off");
        if (src.getPlayer() != t) Msg.send(src, on ? "misc.fly_on_other" : "misc.fly_off_other", "player", t.getGameProfile().getName());
        return 1;
    }

    private static int heal(CommandSourceStack src, ServerPlayer t) {
        t.setHealth(t.getMaxHealth());
        t.getFoodData().setFoodLevel(20);
        t.getFoodData().setSaturation(20f);
        t.clearFire();
        List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> bad = new ArrayList<>();
        for (MobEffectInstance e : t.getActiveEffects()) if (e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) bad.add(e.getEffect());
        for (var e : bad) t.removeEffect(e);
        Msg.send(t, "misc.healed");
        if (src.getPlayer() != t) Msg.send(src, "misc.healed_other", "player", t.getGameProfile().getName());
        return 1;
    }

    private static int feed(CommandSourceStack src, ServerPlayer t) {
        t.getFoodData().setFoodLevel(20);
        t.getFoodData().setSaturation(20f);
        Msg.send(t, "misc.fed");
        if (src.getPlayer() != t) Msg.send(src, "misc.fed_other", "player", t.getGameProfile().getName());
        return 1;
    }

    public static void openTrash(ServerPlayer p) {
        int rows = Cfg.general.misc.trashRows;
        SimpleContainer c = new SimpleContainer(rows * 9);
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(type(rows), id, inv, c, rows) {
            @Override
            public void removed(Player player) {
                super.removed(player);
                int n = 0;
                for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) n += c.getItem(i).getCount();
                c.clearContent();
                if (n > 0 && player instanceof ServerPlayer sp) Msg.send(sp, "misc.trash_done", "count", n);
            }
        }, TextUtil.legacy(Msg.format("misc.trash_title"))));
    }

    public static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    private static int repair(ServerPlayer p, boolean all) {
        int n = 0;
        if (!all) {
            ItemStack s = p.getMainHandItem();
            if (s.isDamageableItem() && s.isDamaged()) {
                s.setDamageValue(0);
                n = 1;
            }
        } else {
            Inventory inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isDamageableItem() && s.isDamaged()) {
                    s.setDamageValue(0);
                    n++;
                }
            }
        }
        Msg.send(p, n == 0 ? "misc.repair_nothing" : "misc.repaired", "count", n);
        return n;
    }

    // ------------------------------------------------------------------ social

    private static int list(CommandSourceStack src) {
        ServerPlayer viewer = src.getPlayer();
        Map<String, List<ServerPlayer>> groups = new LinkedHashMap<>();
        List<ServerPlayer> online = new ArrayList<>(src.getServer().getPlayerList().getPlayers());
        online.removeIf(pl -> Players.isTest(pl.getUUID()) || (Vanish.isVanished(pl) && !Vanish.canSee(viewer)));
        for (ServerPlayer pl : online) {
            String g = LuckPermsBridge.primaryGroup(pl.getUUID());
            groups.computeIfAbsent(g == null ? "joueurs" : g, k -> new ArrayList<>()).add(pl);
        }
        List<String> order = new ArrayList<>(groups.keySet());
        order.sort(Comparator.comparingInt(LuckPermsBridge::groupWeight).reversed().thenComparing(s -> s));
        src.sendSystemMessage(Msg.get("social.list_header", "count", online.size(), "max", src.getServer().getMaxPlayers()));
        for (String g : order) {
            MutableComponent line = TextUtil.legacy(Msg.format("social.list_group", "group", LuckPermsBridge.groupDisplay(g), "count", groups.get(g).size()));
            boolean first = true;
            for (ServerPlayer pl : groups.get(g)) {
                if (!first) line.append(TextUtil.legacy("&7, "));
                first = false;
                line.append(pl.getDisplayName().copy());
                if (Vanish.isVanished(pl)) line.append(TextUtil.legacy(" &8[V]"));
            }
            src.sendSystemMessage(line);
        }
        return online.size();
    }

    private static int playtime(CommandSourceStack src, UUID u) {
        ServerPlayer p = Players.online(u);
        long ticks = p != null ? PlayerStore.playtimeTicks(p) : PlayerStore.peek(u).playtimeTicks;
        PlayerData d = PlayerStore.peek(u);
        Msg.send(src, "social.playtime", "player", Players.name(u), "time", TimeFmt.duration(ticks * 50L),
                "first", d.firstJoin > 0 ? TimeFmt.date(d.firstJoin) : "-");
        return 1;
    }

    private static int pokeshout(ServerPlayer p, int slot) {
        PlayerData d = PlayerStore.get(p);
        if (d.muted()) {
            Msg.send(p, "chat.muted_self", "time", ChatFeatures.muteLeft(d));
            return 0;
        }
        long left = d.cooldownLeft("pokeshout");
        if (left > 0 && !Perms.perk(p, Perms.TP_BYPASS_COOLDOWN)) {
            Msg.send(p, "social.pokeshout_cooldown", "time", TimeFmt.duration(left));
            return 0;
        }
        var party = PokemonText.party(p);
        MutableComponent msg = Msg.withComponent(slot == 0 ? "social.pokeshoutall" : "social.pokeshout", "player", p.getDisplayName());
        int n = 0;
        if (slot > 0) {
            Pokemon pk = party.get(slot - 1);
            if (pk == null) {
                Msg.send(p, "social.pokeshout_empty", "slot", slot);
                return 0;
            }
            msg.append(PokemonText.chat(pk));
            n = 1;
        } else {
            for (int i = 0; i < 6; i++) {
                Pokemon pk = party.get(i);
                if (pk == null) continue;
                if (n > 0) msg.append(Component.literal(" "));
                msg.append(PokemonText.chat(pk));
                n++;
            }
            if (n == 0) {
                Msg.send(p, "social.pokeshout_none");
                return 0;
            }
        }
        d.setCooldown("pokeshout", 10_000);
        Msg.broadcast(msg);
        return n;
    }

    private static int pokeheal(ServerPlayer p) {
        if (Teleports.inBattle(p)) {
            Msg.send(p, "social.pokeheal_battle");
            return 0;
        }
        PlayerData d = PlayerStore.get(p);
        long left = d.cooldownLeft("pokeheal");
        if (left > 0 && !Perms.perk(p, Perms.TP_BYPASS_COOLDOWN)) {
            Msg.send(p, "social.pokeheal_cooldown", "time", TimeFmt.duration(left));
            return 0;
        }
        int n = 0;
        for (Pokemon pk : PokemonText.party(p)) {
            if (pk == null) continue;
            pk.heal();
            n++;
        }
        d.setCooldown("pokeheal", Cfg.general.misc.pokehealCooldownSeconds * 1000L);
        PlayerStore.dirty(p.getUUID());
        Msg.send(p, "social.pokeheal_done", "count", n);
        return n;
    }

    public static int openPc(ServerPlayer p, Integer box) {
        if (PlayerExtensionsKt.isInBattle(p)) {
            Msg.send(p, "social.pc_battle");
            return 0;
        }
        PCStore pc = PlayerExtensionsKt.pc(p);
        int b = box == null ? 0 : Math.max(0, Math.min(pc.getBoxes().size() - 1, box - 1));
        PCLinkManager.INSTANCE.addLink(new PCLink(pc, p.getUUID()));
        new OpenPCPacket(pc, b, false).sendToPlayer(p);
        return 1;
    }

    // ------------------------------------------------------------------ staff

    /** Inventaire d'un autre joueur, modifiable en direct (36 + armure + seconde main). */
    public static void openInvsee(ServerPlayer viewer, ServerPlayer target) {
        Inventory inv = target.getInventory();
        Container view = new InvseeContainer(inv);
        viewer.openMenu(new SimpleMenuProvider((id, pinv, pl) -> new ChestMenu(MenuType.GENERIC_9x5, id, pinv, view, 5) {
            @Override
            public boolean stillValid(Player player) {
                return !target.hasDisconnected();
            }
        }, TextUtil.legacy(Msg.format("staff.invsee_title", "player", target.getGameProfile().getName()))));
    }

    private static int god(CommandSourceStack src, ServerPlayer t) {
        PlayerData d = PlayerStore.get(t);
        d.god = !d.god;
        PlayerStore.dirty(t.getUUID());
        Msg.send(t, d.god ? "staff.god_on" : "staff.god_off");
        if (src.getPlayer() != t) Msg.send(src, d.god ? "staff.god_on_other" : "staff.god_off_other", "player", t.getGameProfile().getName());
        return 1;
    }

    public static boolean isGod(ServerPlayer p) {
        return PlayerStore.get(p).god;
    }

    private static int gamemode(CommandSourceStack src, ServerPlayer t, String mode) {
        GameType gt = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "0", "s", "survival", "survie" -> GameType.SURVIVAL;
            case "1", "c", "creative", "creatif", "créatif" -> GameType.CREATIVE;
            case "2", "a", "adventure", "aventure" -> GameType.ADVENTURE;
            case "3", "sp", "spectator", "spectateur" -> GameType.SPECTATOR;
            default -> null;
        };
        if (gt == null) {
            Msg.send(src, "staff.gamemode_unknown", "mode", mode);
            return 0;
        }
        t.setGameMode(gt);
        Msg.send(src, "staff.gamemode_set", "player", t.getGameProfile().getName(), "mode", gt.getLongDisplayName().getString());
        return 1;
    }

    private static int speed(ServerPlayer p, int n) {
        float mult = 1f + (n - 1) * 0.5f;
        if (p.getAbilities().flying) {
            p.getAbilities().setFlyingSpeed(0.05f * mult);
            p.onUpdateAbilities();
            Msg.send(p, "staff.speed_fly", "speed", n);
        } else {
            var attr = p.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null) attr.setBaseValue(0.1 * mult);
            Msg.send(p, "staff.speed_walk", "speed", n);
        }
        return 1;
    }

    private static int tempban(CommandSourceStack src, String name, String duration, String reason) {
        Long secs = ChatInput.parseDurationSeconds(duration);
        if (secs == null || secs <= 0) {
            Msg.send(src, "staff.tempban_duration", "duration", duration);
            return 0;
        }
        GameProfile gp = Players.profile(name);
        if (gp == null) {
            Msg.send(src, "general.player_not_found", "player", name);
            return 0;
        }
        String r = reason == null || reason.isBlank() ? Msg.format("staff.tempban_default_reason") : reason;
        Date expires = new Date(System.currentTimeMillis() + secs * 1000);
        src.getServer().getPlayerList().getBans().add(new UserBanListEntry(gp, new Date(), src.getTextName(), expires, r));
        ServerPlayer online = Players.online(gp.getId());
        if (online != null) {
            online.connection.disconnect(TextUtil.legacy(Msg.format("staff.tempban_kick", "time", TimeFmt.duration(secs * 1000), "reason", r)));
        }
        Msg.send(src, "staff.tempban_done", "player", gp.getName(), "time", TimeFmt.duration(secs * 1000), "reason", r);
        return 1;
    }
}
