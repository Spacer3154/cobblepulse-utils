package fr.cobblepulse.utils.playtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.PlaytimeConfig;
import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.gui.Pickers;
import fr.cobblepulse.utils.gui.RewardEditor;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.LuckPermsBridge;
import fr.cobblepulse.utils.staff.StaffPanel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Recompenses de temps de jeu : paliers de temps actif cumule (1 h, 5 h, 10 h...) et bonus repetable toutes les N minutes.
 * Le temps des joueurs AFK (ni deplacement, ni rotation, ni message depuis {@code afk_minutes}) n'est pas compte.
 */
public final class PlaytimeRewards {
    private static final class Activity {
        double x, y, z;
        float yaw, pitch;
        long lastActive = System.currentTimeMillis();
    }

    private static final Map<UUID, Activity> ACTIVITY = new ConcurrentHashMap<>();

    private PlaytimeRewards() {}

    private static PlaytimeConfig cfg() {
        return Cfg.playtime;
    }

    // ------------------------------------------------------------------ suivi

    /** Activite explicite (message, commande). */
    public static void active(ServerPlayer p) {
        if (p != null) ACTIVITY.computeIfAbsent(p.getUUID(), k -> new Activity()).lastActive = System.currentTimeMillis();
    }

    public static boolean afk(ServerPlayer p) {
        int m = cfg().afkMinutes;
        if (m <= 0) return false;
        Activity a = ACTIVITY.get(p.getUUID());
        return a != null && System.currentTimeMillis() - a.lastActive > m * 60_000L;
    }

    private static void seed(ServerPlayer p, PlayerData d) {
        if (d.activeSeeded) return;
        d.activeSeeded = true;
        // le temps deja joue avant l'installation compte (statistique vanilla)
        d.activeSeconds = Math.max(d.activeSeconds, PlayerStore.playtimeTicks(p) / 20);
        PlayerStore.dirty(p.getUUID());
    }

    /** Chaque seconde : temps actif, bonus repetable, paliers atteints. */
    public static void tick() {
        if (!cfg().enabled) return;
        long now = System.currentTimeMillis();
        for (ServerPlayer p : fr.cobblepulse.utils.CobbleUtils.server().getPlayerList().getPlayers()) {
            if (Players.isTest(p.getUUID())) continue;
            Activity a = ACTIVITY.computeIfAbsent(p.getUUID(), k -> new Activity());
            if (Math.abs(p.getX() - a.x) > 0.01 || Math.abs(p.getY() - a.y) > 0.01 || Math.abs(p.getZ() - a.z) > 0.01
                    || Math.abs(p.getYRot() - a.yaw) > 0.5 || Math.abs(p.getXRot() - a.pitch) > 0.5) {
                a.x = p.getX();
                a.y = p.getY();
                a.z = p.getZ();
                a.yaw = p.getYRot();
                a.pitch = p.getXRot();
                a.lastActive = now;
            }
            if (afk(p)) continue;
            addActive(p, 1);
        }
    }

    /** Ajoute du temps actif (aussi utilise par /ptr ajouter et les auto-tests). */
    public static void addActive(ServerPlayer p, long seconds) {
        PlayerData d = PlayerStore.get(p);
        seed(p, d);
        d.activeSeconds += seconds;
        d.repeatSeconds += seconds;
        PlayerStore.dirty(p.getUUID());
        PlaytimeConfig c = cfg();
        if (c.repeat.enabled && !c.repeat.rewards.isEmpty()) {
            long every = c.repeat.everyMinutes * 60;
            while (d.repeatSeconds >= every) {
                d.repeatSeconds -= every;
                giveRepeat(p);
            }
        }
        for (PlaytimeConfig.Tier t : c.tiers) {
            if (d.playtimeClaimed.contains(t.id) || !reached(d, t) || !allowed(p, t)) continue;
            if (c.autoClaim) claim(p, t);
            else if (d.playtimeNotified.add(t.id)) {
                Msg.send(p, "playtime.available", "tier", TextUtil.strip(t.name));
                PlayerStore.dirty(p.getUUID());
            }
        }
    }

    private static void giveRepeat(ServerPlayer p) {
        List<String> got = new ArrayList<>();
        for (Reward r : cfg().repeat.rewards) {
            String s = Rewards.roll(p, r);
            if (s != null) got.add(s);
        }
        Players.saveNow(p);
        Msg.send(p, "playtime.repeat_given", "time", TimeFmt.duration(cfg().repeat.everyMinutes * 60_000L),
                "rewards", got.isEmpty() ? "-" : String.join("&7, ", got));
    }

    public static long activeSeconds(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        seed(p, d);
        return d.activeSeconds;
    }

    static boolean reached(PlayerData d, PlaytimeConfig.Tier t) {
        return d.activeSeconds >= t.minutes * 60;
    }

    static boolean allowed(ServerPlayer p, PlaytimeConfig.Tier t) {
        if (t.permission == null || t.permission.isBlank()) return true;
        Boolean v = LuckPermsBridge.check(p, t.permission);
        return Boolean.TRUE.equals(v);
    }

    /** Paliers atteints et pas encore reclames. */
    public static int available(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        seed(p, d);
        int n = 0;
        for (PlaytimeConfig.Tier t : cfg().tiers) if (!d.playtimeClaimed.contains(t.id) && reached(d, t) && allowed(p, t)) n++;
        return n;
    }

    public static boolean claim(ServerPlayer p, PlaytimeConfig.Tier t) {
        PlayerData d = PlayerStore.get(p);
        seed(p, d);
        if (d.playtimeClaimed.contains(t.id)) {
            Msg.send(p, "playtime.already");
            return false;
        }
        if (!allowed(p, t)) {
            Msg.send(p, "general.no_permission");
            return false;
        }
        if (!reached(d, t)) {
            Msg.send(p, "playtime.locked", "time", TimeFmt.duration((t.minutes * 60 - d.activeSeconds) * 1000L));
            return false;
        }
        d.playtimeClaimed.add(t.id);
        PlayerStore.dirty(p.getUUID());
        PlayerStore.save(p.getUUID());
        List<String> got = new ArrayList<>();
        for (Reward r : t.rewards) {
            String s = Rewards.roll(p, r);
            if (s != null) got.add(s);
        }
        Players.saveNow(p);
        Msg.send(p, "playtime.claimed", "tier", TextUtil.strip(t.name), "rewards", got.isEmpty() ? "-" : String.join("&7, ", got));
        return true;
    }

    public static int claimAll(ServerPlayer p) {
        int n = 0;
        PlayerData d = PlayerStore.get(p);
        for (PlaytimeConfig.Tier t : cfg().tiers) {
            if (!d.playtimeClaimed.contains(t.id) && reached(d, t) && allowed(p, t) && claim(p, t)) n++;
        }
        if (n == 0) Msg.send(p, "playtime.nothing");
        return n;
    }

    // ------------------------------------------------------------------ menu joueur

    static String minutes(long m) {
        return TimeFmt.duration(m * 60_000L);
    }

    private static ItemStack progressIcon(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        ItemBuilder b = ItemBuilder.of(Items.CLOCK).name(Msg.format("playtime.progress", "time", TimeFmt.duration(d.activeSeconds * 1000L)));
        b.lore(Msg.format("playtime.progress_total", "time", TimeFmt.duration(PlayerStore.playtimeTicks(p) * 50L)));
        b.lore(Msg.format(afk(p) ? "playtime.status_afk" : "playtime.status_active", "minutes", cfg().afkMinutes));
        if (cfg().repeat.enabled && !cfg().repeat.rewards.isEmpty()) {
            b.lore(Msg.format("playtime.repeat_next", "time", TimeFmt.duration((cfg().repeat.everyMinutes * 60 - d.repeatSeconds) * 1000L)));
        }
        return b.build();
    }

    private static ItemStack tierIcon(ServerPlayer p, PlaytimeConfig.Tier t) {
        PlayerData d = PlayerStore.get(p);
        boolean claimed = d.playtimeClaimed.contains(t.id);
        boolean ok = reached(d, t);
        boolean perm = allowed(p, t);
        ItemBuilder b = ItemBuilder.of(claimed ? new ItemStack(Items.LIME_STAINED_GLASS_PANE) : Reg.stack(t.icon)).name(t.name).lore(t.lore);
        b.lore(Msg.format("playtime.tier_need", "time", minutes(t.minutes)));
        String status;
        if (claimed) status = Msg.format("dex.status_claimed");
        else if (!perm) status = Msg.format("playtime.status_rank");
        else if (ok) status = Msg.format("dex.status_ready");
        else status = Msg.format("playtime.status_left", "time", TimeFmt.duration((t.minutes * 60 - d.activeSeconds) * 1000L));
        b.lore(status);
        b.lore(Msg.format("dex.tier_rewards"));
        for (Reward r : t.rewards) b.lore("  &8• " + Rewards.describe(r) + (r.chance < 100 ? " &8(" + fmt(r.chance) + " %)" : ""));
        b.glow(ok && perm && !claimed);
        return b.hideExtra().build();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.FRANCE, "%.1f", d);
    }

    public static void open(ServerPlayer p) {
        if (!cfg().enabled) {
            Msg.send(p, "playtime.disabled");
            return;
        }
        PlayerData d = PlayerStore.get(p);
        seed(p, d);
        List<Paged.Entry> entries = new ArrayList<>();
        PlaytimeConfig.Repeat rep = cfg().repeat;
        if (rep.enabled && !rep.rewards.isEmpty()) {
            ItemBuilder b = ItemBuilder.of(Reg.stack(rep.icon)).name(rep.name);
            b.lore(Msg.format("playtime.repeat_every", "time", minutes(rep.everyMinutes)));
            b.lore(Msg.format("playtime.repeat_next", "time", TimeFmt.duration((rep.everyMinutes * 60 - d.repeatSeconds) * 1000L)));
            for (Reward r : rep.rewards) b.lore("  &8• " + Rewards.describe(r) + (r.chance < 100 ? " &8(" + fmt(r.chance) + " %)" : ""));
            entries.add(new Paged.Entry(b.hideExtra().build(), null));
        }
        for (PlaytimeConfig.Tier t : cfg().tiers) {
            entries.add(new Paged.Entry(tierIcon(p, t), (pl, c) -> {
                if (claim(pl, t)) open(pl);
            }));
        }
        Paged.open(p, Msg.format("playtime.title", "time", TimeFmt.duration(d.activeSeconds * 1000L)), entries, 0, Hub::open, gui -> {
            gui.set(49, progressIcon(p));
            gui.button(48, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
            gui.button(50, ItemBuilder.of(Items.HOPPER).name(Msg.format("dex.claim_all")).build(), pl -> {
                claimAll(pl);
                open(pl);
            });
            if (Perms.admin(p, "playtime")) gui.button(53, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("playtime.editor_button")).build(), PlaytimeRewards::editor);
            // progression en direct
            gui.every(20, gg -> gg.set(49, progressIcon(p)));
        });
    }

    // ------------------------------------------------------------------ editeur (cobblepulse.admin.playtime)

    private static void save() {
        cfg().normalize();
        Cfg.save("playtimerewards.json", cfg());
    }

    public static void editor(ServerPlayer p) {
        if (!Perms.admin(p, "playtime")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        List<Paged.Entry> entries = new ArrayList<>();
        PlaytimeConfig.Repeat rep = cfg().repeat;
        entries.add(new Paged.Entry(ItemBuilder.of(Reg.stack(rep.icon)).name(rep.name)
                .lore(Msg.format("playtime.editor_repeat", "time", minutes(rep.everyMinutes), "state", rep.enabled ? "&aactif" : "&cdésactivé",
                        "rewards", rep.rewards.size()))
                .lore("", Msg.format("dex.editor_click")).hideExtra().build(), (pl, c) -> editRepeat(pl)));
        for (PlaytimeConfig.Tier t : cfg().tiers) {
            entries.add(new Paged.Entry(ItemBuilder.of(Reg.stack(t.icon)).name(t.name)
                    .lore(Msg.format("playtime.editor_info", "id", t.id, "time", minutes(t.minutes), "rewards", t.rewards.size()))
                    .lore("", Msg.format("dex.editor_click")).hideExtra().build(), (pl, c) -> edit(pl, t)));
        }
        Paged.open(p, Msg.format("playtime.editor_title"), entries, 0, StaffPanel::open, gui -> {
            gui.button(51, ItemBuilder.of(Items.LIME_DYE).name(Msg.format("playtime.editor_new")).build(),
                    pl -> ChatInput.ask(pl, "playtime.ask_time", txt -> {
                        Long sec = ChatInput.parseDurationSeconds(txt);
                        if (sec == null || sec < 60) {
                            Msg.send(pl, "general.bad_number");
                            editor(pl);
                            return;
                        }
                        PlaytimeConfig.Tier t = new PlaytimeConfig.Tier();
                        t.minutes = sec / 60;
                        t.id = "m" + t.minutes;
                        int k = 2;
                        while (find(t.id) != null) t.id = "m" + t.minutes + "_" + k++;
                        t.name = "&e" + minutes(t.minutes) + " de jeu";
                        cfg().tiers.add(t);
                        save();
                        edit(pl, t);
                    }, () -> editor(pl)));
            gui.button(47, ItemBuilder.of(cfg().enabled ? Items.LIME_DYE : Items.GRAY_DYE)
                    .name(Msg.format(cfg().enabled ? "playtime.editor_on" : "playtime.editor_off")).build(), pl -> {
                cfg().enabled = !cfg().enabled;
                save();
                editor(pl);
            });
            gui.button(46, ItemBuilder.of(Items.HOPPER).name(Msg.format(cfg().autoClaim ? "playtime.editor_auto_on" : "playtime.editor_auto_off"))
                    .lore(Msg.format("playtime.editor_auto_lore")).build(), pl -> {
                cfg().autoClaim = !cfg().autoClaim;
                save();
                editor(pl);
            });
            gui.button(50, ItemBuilder.of(Items.ARMOR_STAND).name(Msg.format("playtime.editor_afk", "minutes", cfg().afkMinutes))
                    .lore(Msg.format("playtime.editor_afk_lore")).build(), pl -> ChatInput.ask(pl, "playtime.ask_afk", txt -> {
                Long v = ChatInput.parseAmount(txt);
                if (v != null) cfg().afkMinutes = (int) Math.max(0, Math.min(1440, v));
                save();
                editor(pl);
            }, () -> editor(pl)));
        });
    }

    static PlaytimeConfig.Tier find(String id) {
        for (PlaytimeConfig.Tier t : cfg().tiers) if (t.id.equalsIgnoreCase(id)) return t;
        return null;
    }

    public static void edit(ServerPlayer p, PlaytimeConfig.Tier t) {
        RewardEditor.open(p, Msg.format("playtime.edit_title", "tier", TextUtil.strip(t.name)), t.rewards, PlaytimeRewards::save,
                PlaytimeRewards::editor, g -> {
                    g.button(50, ItemBuilder.of(Reg.stack(t.icon)).name(t.name).lore(Msg.format("playtime.edit_icon_lore")).build(), (pl, c) -> {
                        if (c.right()) {
                            ChatInput.ask(pl, "kits.ask_display", txt -> {
                                t.name = txt;
                                save();
                                edit(pl, t);
                            }, () -> edit(pl, t));
                            return;
                        }
                        Pickers.item(pl, "worlds.pick_icon", id -> {
                            t.icon = id;
                            save();
                            edit(pl, t);
                        }, () -> edit(pl, t));
                    });
                    g.button(51, ItemBuilder.of(Items.CLOCK).name(Msg.format("playtime.edit_time", "time", minutes(t.minutes))).build(),
                            pl -> ChatInput.ask(pl, "playtime.ask_time", txt -> {
                                Long sec = ChatInput.parseDurationSeconds(txt);
                                if (sec != null && sec >= 60) t.minutes = sec / 60;
                                save();
                                edit(pl, t);
                            }, () -> edit(pl, t)));
                    g.button(52, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("playtime.edit_permission",
                            "node", t.permission.isBlank() ? "-" : t.permission)).lore(Msg.format("playtime.edit_permission_lore")).build(),
                            pl -> ChatInput.ask(pl, "playtime.ask_permission", txt -> {
                                t.permission = txt.trim().equals("-") ? "" : txt.trim();
                                save();
                                edit(pl, t);
                            }, () -> edit(pl, t)));
                    g.button(53, ItemBuilder.of(Items.LAVA_BUCKET).name(Msg.format("dex.edit_delete")).lore(Msg.format("warps.edit_delete_lore")).build(), (pl, c) -> {
                        if (!c.shift()) {
                            Msg.send(pl, "warps.edit_delete_shift");
                            return;
                        }
                        cfg().tiers.remove(t);
                        save();
                        editor(pl);
                    });
                });
    }

    public static void editRepeat(ServerPlayer p) {
        PlaytimeConfig.Repeat rep = cfg().repeat;
        RewardEditor.open(p, Msg.format("playtime.edit_title", "tier", TextUtil.strip(rep.name)), rep.rewards, PlaytimeRewards::save,
                PlaytimeRewards::editor, g -> {
                    g.button(50, ItemBuilder.of(rep.enabled ? Items.LIME_DYE : Items.GRAY_DYE)
                            .name(Msg.format(rep.enabled ? "playtime.editor_on" : "playtime.editor_off")).build(), pl -> {
                        rep.enabled = !rep.enabled;
                        save();
                        editRepeat(pl);
                    });
                    g.button(51, ItemBuilder.of(Items.CLOCK).name(Msg.format("playtime.edit_every", "time", minutes(rep.everyMinutes))).build(),
                            pl -> ChatInput.ask(pl, "playtime.ask_time", txt -> {
                                Long sec = ChatInput.parseDurationSeconds(txt);
                                if (sec != null && sec >= 60) rep.everyMinutes = sec / 60;
                                save();
                                editRepeat(pl);
                            }, () -> editRepeat(pl)));
                    g.button(52, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("dex.edit_name")).build(), pl -> ChatInput.ask(pl, "kits.ask_display", txt -> {
                        rep.name = txt;
                        save();
                        editRepeat(pl);
                    }, () -> editRepeat(pl)));
                });
    }

    // ------------------------------------------------------------------ commandes

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> ONLINE = (ctx, b) ->
            net.minecraft.commands.SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), b);

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "playtimerewards", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, PlaytimeRewards::open))
                .then(literal("reclamer").executes(ctx -> Cmd.asPlayer(ctx, PlaytimeRewards::claimAll)))
                .then(literal("ajouter").requires(Perms.adminReq("playtime"))
                        .then(argument("joueur", StringArgumentType.word()).suggests(ONLINE).then(argument("duree", StringArgumentType.word()).executes(ctx -> {
                            ServerPlayer t = Players.online(StringArgumentType.getString(ctx, "joueur"));
                            if (t == null) {
                                Msg.send(ctx.getSource(), "general.player_not_found", "player", StringArgumentType.getString(ctx, "joueur"));
                                return 0;
                            }
                            Long sec = ChatInput.parseDurationSeconds(StringArgumentType.getString(ctx, "duree"));
                            if (sec == null || sec <= 0) {
                                Msg.send(ctx.getSource(), "general.bad_number");
                                return 0;
                            }
                            addActive(t, sec);
                            Msg.send(ctx.getSource(), "playtime.added", "player", t.getGameProfile().getName(), "time", TimeFmt.duration(sec * 1000L));
                            return 1;
                        }))))
                .then(literal("reinitialiser").requires(Perms.adminReq("playtime"))
                        .then(argument("joueur", StringArgumentType.word()).suggests(ONLINE).executes(ctx -> {
                            ServerPlayer t = Players.online(StringArgumentType.getString(ctx, "joueur"));
                            if (t == null) {
                                Msg.send(ctx.getSource(), "general.player_not_found", "player", StringArgumentType.getString(ctx, "joueur"));
                                return 0;
                            }
                            PlayerData pd = PlayerStore.get(t);
                            pd.playtimeClaimed.clear();
                            pd.playtimeNotified.clear();
                            pd.repeatSeconds = 0;
                            PlayerStore.dirty(t.getUUID());
                            Msg.send(ctx.getSource(), "playtime.reset", "player", t.getGameProfile().getName());
                            return 1;
                        }))));
    }

    /** Texte d'appoint pour /playtime : prochain palier. */
    public static String nextTierText(ServerPlayer p) {
        PlayerData d = PlayerStore.get(p);
        seed(p, d);
        for (PlaytimeConfig.Tier t : cfg().tiers) {
            if (d.playtimeClaimed.contains(t.id) || !allowed(p, t)) continue;
            if (reached(d, t)) return Msg.format("playtime.next_ready", "tier", TextUtil.strip(t.name));
            return Msg.format("playtime.next_in", "tier", TextUtil.strip(t.name), "time", TimeFmt.duration((t.minutes * 60 - d.activeSeconds) * 1000L));
        }
        return "";
    }

    public static void forget(UUID uuid) {
        ACTIVITY.remove(uuid);
    }
}
