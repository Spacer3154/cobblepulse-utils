package fr.cobblepulse.utils.boss;

import fr.cobblepulse.utils.config.BossConfig;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.staff.StaffPanel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

/** Editeur en jeu des Boss (cobblepulse.admin.boss) : reglages generaux, paliers et tables de butin. */
public final class BossEditor {
    private BossEditor() {}

    private static void save() {
        Cfg.boss.normalize();
        Cfg.save("boss.json", Cfg.boss);
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.FRANCE, "%.2f", d);
    }

    public static void open(ServerPlayer p) {
        if (!Perms.admin(p, "boss")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        Gui g = new Gui(4, Msg.format("boss.editor_title"));
        g.fill(Gui.pane());
        int slot = 10;
        for (Map.Entry<String, BossConfig.Tier> e : Cfg.boss.tiers.entrySet()) {
            BossConfig.Tier t = e.getValue();
            String id = e.getKey();
            g.button(slot++, ItemBuilder.of(Reg.stack(t.icon)).name(t.color + t.name)
                    .lore(Msg.format("boss.editor_tier_lore", "min", t.levelMin, "max", t.levelMax, "weight", fmt(t.weight), "loot", t.loot.size(),
                            "ai", t.aiSkill, "ivs", t.perfectIvs, "scale", fmt(t.scale)))
                    .lore("", Msg.format("boss.editor_tier_click")).hideExtra().build(), pl -> tier(pl, id));
            if (slot == 17) slot = 19;
        }
        g.button(28, ItemBuilder.of(Cfg.boss.naturalSpawn ? Items.LIME_DYE : Items.GRAY_DYE)
                .name(Msg.format(Cfg.boss.naturalSpawn ? "boss.editor_spawn_on" : "boss.editor_spawn_off")).build(), pl -> {
            Cfg.boss.naturalSpawn = !Cfg.boss.naturalSpawn;
            save();
            open(pl);
        });
        g.button(29, ItemBuilder.of(Items.EXPERIENCE_BOTTLE).name(Msg.format("boss.editor_chance", "chance", fmt(Cfg.boss.promotionChance))).build(),
                pl -> askNumber(pl, "boss.ask_chance", v -> Cfg.boss.promotionChance = v, () -> open(pl)));
        g.button(30, ItemBuilder.of(Items.SPAWNER).name(Msg.format("boss.editor_max", "max", Cfg.boss.maxActive)).build(),
                pl -> askNumber(pl, "boss.ask_max", v -> Cfg.boss.maxActive = (int) v, () -> open(pl)));
        g.button(31, ItemBuilder.of(Items.CLOCK).name(Msg.format("boss.editor_lifetime", "minutes", Cfg.boss.lifetimeMinutes)).build(),
                pl -> askNumber(pl, "boss.ask_lifetime", v -> Cfg.boss.lifetimeMinutes = (int) v, () -> open(pl)));
        g.button(32, ItemBuilder.of(Items.AMETHYST_SHARD).name(Msg.format("boss.editor_special", "chance", fmt(Cfg.boss.specialFormChance),
                "first", fmt(Cfg.boss.formItemFirstChance), "repeat", fmt(Cfg.boss.formItemRepeatChance))).lore(Msg.format("boss.editor_special_lore")).build(),
                (pl, c) -> {
                    if (c.right()) askNumber(pl, "boss.ask_repeat", v -> Cfg.boss.formItemRepeatChance = v, () -> open(pl));
                    else askNumber(pl, "boss.ask_special", v -> Cfg.boss.specialFormChance = v, () -> open(pl));
                });
        g.button(33, ItemBuilder.of(Cfg.boss.promoteLegendaries ? Items.NETHER_STAR : Items.FIREWORK_STAR)
                .name(Msg.format(Cfg.boss.promoteLegendaries ? "boss.editor_legend_on" : "boss.editor_legend_off")).build(), pl -> {
            Cfg.boss.promoteLegendaries = !Cfg.boss.promoteLegendaries;
            save();
            open(pl);
        });
        g.set(4, ItemBuilder.of(Items.DRAGON_HEAD).name(Msg.format("boss.editor_info", "count", Bosses.activeCount())).build());
        g.button(27, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), StaffPanel::open);
        if (Perms.admin(p, "boss.spawn")) {
            g.button(34, ItemBuilder.of(Items.SPAWNER).name(Msg.format("boss.editor_test_all")).lore(Msg.format("boss.editor_test_all_lore")).build(), pl -> {
                fr.cobblepulse.utils.gui.Gui.close(pl);
                fr.cobblepulse.utils.command.RootCommands.spawnAllTiers(pl.createCommandSourceStack());
            });
        }
        if (Perms.admin(p, "boss.clear")) {
            g.button(35, ItemBuilder.of(Items.BARRIER).name(Msg.format("boss.editor_clear")).build(), pl -> {
                Msg.send(pl, "boss.cleared", "count", Bosses.clearAll());
                open(pl);
            });
        }
        g.open(p);
    }

    private static void askNumber(ServerPlayer p, String key, java.util.function.DoubleConsumer set, Runnable back) {
        ChatInput.ask(p, key, txt -> {
            try {
                set.accept(Double.parseDouble(txt.trim().replace(',', '.').replace("%", "")));
                save();
            } catch (NumberFormatException ignored) {
                Msg.send(p, "general.bad_number");
            }
            back.run();
        }, back);
    }

    public static void tier(ServerPlayer p, String id) {
        BossConfig.Tier t = Cfg.boss.tiers.get(id);
        if (t == null) {
            open(p);
            return;
        }
        Gui g = new Gui(6, Msg.format("boss.editor_tier_title", "tier", t.color + t.name));
        for (int i = 45; i < 54; i++) g.set(i, Gui.pane());
        int slot = 0;
        for (Reward r : new ArrayList<>(t.loot)) {
            if (slot >= 36) break;
            g.button(slot++, ItemBuilder.of(Rewards.icon(r)).name(Rewards.describe(r)).lore(Msg.format("dex.edit_reward_lore", "chance", fmt(r.chance))).build(), (pl, c) -> {
                if (c.shift()) {
                    t.loot.remove(r);
                    save();
                    tier(pl, id);
                } else {
                    askNumber(pl, "dex.ask_chance", v -> r.chance = Math.max(0, Math.min(100, v)), () -> tier(pl, id));
                }
            });
        }
        for (int i = 36; i < 45; i++) g.set(i, Gui.pane());
        if (Perms.admin(p, "boss.spawn")) {
            // tests : apparition, combat immediat, version Mega / Primo / Originelle
            g.button(39, ItemBuilder.of(Items.SPAWNER).name(Msg.format("boss.editor_test_spawn")).lore(Msg.format("boss.editor_test_spawn_lore")).build(), pl -> {
                fr.cobblepulse.utils.gui.Gui.close(pl);
                fr.cobblepulse.utils.command.RootCommands.spawnBoss(pl.createCommandSourceStack(), id, null, "auto", false);
            });
            g.button(40, ItemBuilder.of(Items.IRON_SWORD).name(Msg.format("boss.editor_test_fight")).lore(Msg.format("boss.editor_test_fight_lore")).hideExtra().build(), pl -> {
                fr.cobblepulse.utils.gui.Gui.close(pl);
                fr.cobblepulse.utils.command.RootCommands.spawnBoss(pl.createCommandSourceStack(), id, null, "auto", true);
            });
            g.button(41, ItemBuilder.of(Items.AMETHYST_SHARD).name(Msg.format("boss.editor_test_mega")).lore(Msg.format("boss.editor_test_mega_lore")).build(), pl -> {
                fr.cobblepulse.utils.gui.Gui.close(pl);
                fr.cobblepulse.utils.command.RootCommands.spawnBoss(pl.createCommandSourceStack(), id, null, "mega", false);
            });
        }
        g.button(45, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), BossEditor::open);
        g.button(46, ItemBuilder.of(Items.CHEST).name(Msg.format("dex.edit_add_item")).build(), pl -> {
            ItemStack hand = pl.getMainHandItem();
            if (hand.isEmpty()) {
                Msg.send(pl, "warps.edit_icon_empty");
                return;
            }
            t.loot.add(Reward.item(ItemSerial.toSnbt(hand), 100));
            save();
            tier(pl, id);
        });
        g.button(47, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("dex.edit_add_money")).build(), pl -> askNumber(pl, "dex.ask_money", v -> {
            if (v > 0) t.loot.add(Reward.money((long) v, 100));
        }, () -> tier(pl, id)));
        g.button(48, ItemBuilder.of(Items.NETHER_STAR).name(Msg.format("boss.editor_add_bp")).build(), pl -> askNumber(pl, "boss.ask_bp", v -> {
            if (v > 0) t.loot.add(Reward.bp((long) v, 100));
        }, () -> tier(pl, id)));
        g.button(49, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.format("dex.edit_add_command")).build(), pl -> ChatInput.ask(pl, "kits.ask_command", txt -> {
            t.loot.add(Reward.command(txt.startsWith("/") ? txt.substring(1) : txt, "", 100));
            save();
            tier(pl, id);
        }, () -> tier(pl, id)));
        g.button(50, ItemBuilder.of(Items.EXPERIENCE_BOTTLE).name(Msg.format("boss.editor_levels", "min", t.levelMin, "max", t.levelMax)).build(),
                pl -> ChatInput.ask(pl, "boss.ask_levels", txt -> {
                    String[] parts = txt.trim().split("[\\s\\-/]+");
                    try {
                        t.levelMin = Integer.parseInt(parts[0]);
                        t.levelMax = Integer.parseInt(parts.length > 1 ? parts[1] : parts[0]);
                        save();
                    } catch (NumberFormatException ignored) {
                        Msg.send(pl, "general.bad_number");
                    }
                    tier(pl, id);
                }, () -> tier(pl, id)));
        g.button(51, ItemBuilder.of(Items.COMPARATOR).name(Msg.format("boss.editor_stats")).lore(Msg.format("boss.editor_stats_lore",
                "weight", fmt(t.weight), "scale", fmt(t.scale), "ivs", t.perfectIvs, "ai", t.aiSkill, "cooldown", t.engageCooldownSeconds,
                "announce", t.announce ? "oui" : "non")).build(), pl -> ChatInput.ask(pl, "boss.ask_stats", txt -> {
            for (String kv : txt.trim().split("\\s+")) {
                String[] a = kv.split("=");
                if (a.length != 2) continue;
                try {
                    switch (a[0].toLowerCase(java.util.Locale.ROOT)) {
                        case "poids", "weight" -> t.weight = Double.parseDouble(a[1]);
                        case "taille", "scale" -> t.scale = Float.parseFloat(a[1]);
                        case "iv", "ivs" -> t.perfectIvs = Integer.parseInt(a[1]);
                        case "ia", "ai" -> t.aiSkill = Integer.parseInt(a[1]);
                        case "delai", "cooldown" -> t.engageCooldownSeconds = Long.parseLong(a[1]);
                        case "annonce", "announce" -> t.announce = Boolean.parseBoolean(a[1]) || a[1].equals("oui");
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            save();
            tier(pl, id);
        }, () -> tier(pl, id)));
        g.button(52, ItemBuilder.of(Reg.stack(t.icon)).name(Msg.format("boss.editor_icon")).build(), pl -> {
            if (!pl.getMainHandItem().isEmpty()) t.icon = Reg.id(pl.getMainHandItem().getItem());
            save();
            tier(pl, id);
        });
        g.button(53, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("boss.editor_name")).build(), pl -> ChatInput.ask(pl, "boss.ask_name", txt -> {
            String[] parts = txt.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[0].startsWith("&")) {
                t.color = parts[0];
                t.name = parts[1];
            } else t.name = txt.trim();
            save();
            tier(pl, id);
        }, () -> tier(pl, id)));
        g.open(p);
    }

    static Consumer<ServerPlayer> back() {
        return BossEditor::open;
    }
}
