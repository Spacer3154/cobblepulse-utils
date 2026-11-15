package fr.cobblepulse.utils.essentials;

import com.mojang.brigadier.CommandDispatcher;
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
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;

/** Messages prives (/msg, /reply), /ignore, /mute, /nick et filtrage du chat public. */
public final class ChatFeatures {
    private static final Map<UUID, UUID> LAST = new ConcurrentHashMap<>();
    /** Surnoms formates (lus par le mixin du nom affiche). */
    private static final Map<UUID, Component> NICKS = new ConcurrentHashMap<>();

    private ChatFeatures() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "msg", l -> Cmd.lit(l).then(argument("joueur", EntityArgument.player())
                .then(argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayer to = EntityArgument.getPlayer(ctx, "joueur");
                    return send(ctx.getSource(), to, StringArgumentType.getString(ctx, "message"));
                }))));
        Cmd.player(d, "reply", l -> Cmd.lit(l).then(argument("message", StringArgumentType.greedyString()).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            UUID last = LAST.get(p.getUUID());
            ServerPlayer to = last == null ? null : Players.online(last);
            if (to == null || (Vanish.isVanished(to) && !Vanish.canSee(p))) {
                Msg.send(p, "chat.no_reply");
                return 0;
            }
            return send(ctx.getSource(), to, StringArgumentType.getString(ctx, "message"));
        }))));
        Cmd.player(d, "ignore", l -> Cmd.lit(l).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            PlayerData data = PlayerStore.get(p);
            List<String> names = new ArrayList<>();
            for (UUID u : data.ignored) names.add(Players.name(u));
            Msg.send(p, "chat.ignore_list", "list", names.isEmpty() ? "-" : String.join(", ", names));
            return 1;
        })).then(argument("joueur", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                c.getSource().getServer().getPlayerNames(), b)).executes(ctx -> Cmd.asPlayer(ctx, p -> {
            String name = StringArgumentType.getString(ctx, "joueur");
            UUID target = resolve(name);
            if (target == null) {
                Msg.send(p, "general.player_not_found", "player", name);
                return 0;
            }
            if (target.equals(p.getUUID())) {
                Msg.send(p, "chat.ignore_self");
                return 0;
            }
            PlayerData data = PlayerStore.get(p);
            boolean now = !data.ignored.remove(target);
            if (now) data.ignored.add(target);
            PlayerStore.dirty(p.getUUID());
            Msg.send(p, now ? "chat.ignore_on" : "chat.ignore_off", "player", Players.name(target));
            return 1;
        }))));
        Cmd.player(d, "nick", l -> Cmd.lit(l).then(argument("pseudo", StringArgumentType.greedyString()).executes(ctx -> Cmd.asPlayer(ctx, p ->
                nick(p, p, StringArgumentType.getString(ctx, "pseudo"))))));
        Cmd.admin(d, "mute", "mute", l -> Cmd.lit(l).then(argument("joueur", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerNames(), b))
                .executes(ctx -> mute(ctx.getSource(), StringArgumentType.getString(ctx, "joueur"), null, ""))
                .then(argument("duree", StringArgumentType.word()).executes(ctx -> mute(ctx.getSource(), StringArgumentType.getString(ctx, "joueur"),
                                StringArgumentType.getString(ctx, "duree"), ""))
                        .then(argument("raison", StringArgumentType.greedyString()).executes(ctx -> mute(ctx.getSource(),
                                StringArgumentType.getString(ctx, "joueur"), StringArgumentType.getString(ctx, "duree"),
                                StringArgumentType.getString(ctx, "raison")))))));
        Cmd.admin(d, "unmute", "mute", l -> Cmd.lit(l).then(argument("joueur", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerNames(), b)).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "joueur");
                    UUID u = resolve(name);
                    if (u == null) {
                        Msg.send(ctx.getSource(), "general.player_not_found", "player", name);
                        return 0;
                    }
                    PlayerData data = PlayerStore.get(u);
                    data.mutedUntil = 0;
                    data.muteReason = "";
                    PlayerStore.save(u);
                    Msg.send(ctx.getSource(), "chat.unmuted", "player", Players.name(u));
                    Msg.send(u, "chat.unmuted_target");
                    return 1;
                })));
    }

    public static UUID resolve(String name) {
        ServerPlayer p = Players.online(name);
        if (p != null) return p.getUUID();
        UUID u = PlayerStore.findByName(name);
        if (u != null) return u;
        com.mojang.authlib.GameProfile gp = Players.profile(name);
        return gp == null ? null : gp.getId();
    }

    private static int send(CommandSourceStack src, ServerPlayer to, String text) {
        ServerPlayer from = src.getPlayer();
        String fromName = from == null ? "Console" : from.getGameProfile().getName();
        if (from != null) {
            PlayerData fd = PlayerStore.get(from);
            if (fd.muted() && !Perms.perk(from, Perms.MUTE_BYPASS)) {
                Msg.send(from, "chat.muted_self", "time", muteLeft(fd));
                return 0;
            }
            if (Vanish.isVanished(to) && !Vanish.canSee(from)) {
                Msg.send(from, "general.player_not_found", "player", to.getGameProfile().getName());
                return 0;
            }
            if (from == to) {
                Msg.send(from, "chat.msg_self");
                return 0;
            }
        }
        boolean ignored = from != null && PlayerStore.get(to).ignored.contains(from.getUUID());
        String body = from != null && Perms.perk(from, Perms.CHAT_COLOR) ? text : TextUtil.strip(text);
        src.sendSystemMessage(Msg.get("chat.msg_sent", "player", to.getGameProfile().getName()).append(TextUtil.legacy(body)));
        if (ignored) return 1;
        Msg.send(to, Msg.get("chat.msg_received", "player", fromName).append(TextUtil.legacy(body)));
        if (from != null) {
            LAST.put(to.getUUID(), from.getUUID());
            LAST.put(from.getUUID(), to.getUUID());
        }
        return 1;
    }

    static int nick(CommandSourceStack src, ServerPlayer target, String raw) {
        ServerPlayer actor = src.getPlayer();
        return nick(actor, target, raw);
    }

    public static int nick(ServerPlayer actor, ServerPlayer target, String raw) {
        PlayerData d = PlayerStore.get(target);
        String v = raw == null ? "" : raw.trim();
        if (v.equalsIgnoreCase("off") || v.isEmpty()) {
            d.nick = "";
            NICKS.remove(target.getUUID());
            PlayerStore.dirty(target.getUUID());
            Msg.send(actor, "chat.nick_off");
            refreshName(target);
            return 1;
        }
        if (actor != null && !Perms.perk(actor, Perms.CHAT_COLOR)) v = TextUtil.strip(v);
        String plain = TextUtil.strip(v);
        if (plain.length() > Cfg.general.chat.nickMaxLength || plain.length() < 2 || !plain.matches("[\\p{L}0-9_ .\\-]+")) {
            Msg.send(actor, "chat.nick_invalid", "max", Cfg.general.chat.nickMaxLength);
            return 0;
        }
        d.nick = v;
        PlayerStore.dirty(target.getUUID());
        applyNick(target.getUUID(), v, target.getGameProfile().getName());
        refreshName(target);
        Msg.send(actor, "chat.nick_set", "nick", v);
        return 1;
    }

    public static void applyNick(UUID uuid, String nick, String realName) {
        if (nick == null || nick.isBlank()) {
            NICKS.remove(uuid);
            return;
        }
        MutableComponent c = TextUtil.legacy(Cfg.general.chat.nickPrefix + nick);
        c.withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(realName))));
        NICKS.put(uuid, c);
    }

    public static Component nickOf(UUID uuid) {
        return NICKS.get(uuid);
    }

    public static void onJoin(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        applyNick(p.getUUID(), d.nick, p.getGameProfile().getName());
    }

    public static void onLeave(ServerPlayer p) {
        LAST.remove(p.getUUID());
        if (!Players.isTest(p.getUUID())) NICKS.remove(p.getUUID());
    }

    private static void refreshName(ServerPlayer p) {
        p.getServer().getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
                net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, p));
    }

    private static int mute(CommandSourceStack src, String name, String duration, String reason) {
        UUID u = resolve(name);
        if (u == null) {
            Msg.send(src, "general.player_not_found", "player", name);
            return 0;
        }
        Long secs = duration == null ? null : ChatInput.parseDurationSeconds(duration);
        if (duration != null && secs == null) {
            reason = (duration + " " + reason).trim();
        }
        PlayerData d = PlayerStore.get(u);
        d.mutedUntil = secs == null || secs <= 0 ? -1 : System.currentTimeMillis() + secs * 1000;
        d.muteReason = reason == null ? "" : reason;
        PlayerStore.save(u);
        String time = d.mutedUntil < 0 ? Msg.format("chat.permanent") : TimeFmt.duration(secs * 1000);
        Msg.send(src, "chat.muted", "player", Players.name(u), "time", time, "reason", d.muteReason.isBlank() ? "-" : d.muteReason);
        Msg.send(u, "chat.muted_target", "time", time, "reason", d.muteReason.isBlank() ? "-" : d.muteReason);
        return 1;
    }

    static String muteLeft(PlayerData d) {
        return d.mutedUntil < 0 ? Msg.format("chat.permanent") : TimeFmt.duration(d.mutedUntil - System.currentTimeMillis());
    }

    /** Chat public : saisie d'editeur, muet. @return false pour bloquer le message. */
    public static boolean allowChat(ServerPlayer p, String text) {
        if (ChatInput.intercept(p, text)) return false;
        PlayerData d = PlayerStore.get(p);
        if (d.muted() && !Perms.perk(p, Perms.MUTE_BYPASS)) {
            Msg.send(p, "chat.muted_self", "time", muteLeft(d));
            return false;
        }
        return true;
    }

    /** Destinataire ignorant l'expediteur (filtre du chat public, appele par le mixin). */
    public static boolean ignores(ServerPlayer receiver, UUID sender) {
        if (!Cfg.general.chat.ignoreBlocksPublicChat || sender == null || receiver.getUUID().equals(sender)) return false;
        PlayerData d = PlayerStore.get(receiver);
        return d.ignored.contains(sender) && !hasStaff(sender);
    }

    private static boolean hasStaff(UUID sender) {
        ServerPlayer p = Players.online(sender);
        if (p == null) return false;
        Boolean v = LuckPermsBridge.check(p, "cobblepulse.admin.mute");
        return v != null && v;
    }
}
