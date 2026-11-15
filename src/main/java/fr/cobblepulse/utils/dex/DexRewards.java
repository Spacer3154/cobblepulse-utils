package fr.cobblepulse.utils.dex;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.CommandDispatcher;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.DexConfig;
import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.gui.ChatInput;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Paged;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.staff.StaffPanel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dex Rewards : recompenses par paliers de completion du Pokedex (especes capturees). */
public final class DexRewards {
    private static Set<ResourceLocation> countedCache;
    private static int countedRevision = -1;

    private DexRewards() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "dex", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, DexRewards::open))
                .then(Cmd.lit("claim").executes(ctx -> Cmd.asPlayer(ctx, DexRewards::claimAll))));
    }

    /** Especes comptees pour le Pokedex (implementees, fakemons selon la config). */
    public static Set<ResourceLocation> counted() {
        if (countedCache != null && countedRevision == Cfg.revision) return countedCache;
        Set<ResourceLocation> out = new HashSet<>();
        for (Species s : PokemonSpecies.getSpecies()) {
            if (Cfg.dex.onlyImplemented && !s.getImplemented()) continue;
            if (!Cfg.dex.includeFakemons && !"cobblemon".equals(s.getResourceIdentifier().getNamespace())) continue;
            out.add(s.getResourceIdentifier());
        }
        countedCache = out;
        countedRevision = Cfg.revision;
        return out;
    }

    public static int caught(ServerPlayer p) {
        PokedexManager dex = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(p);
        Set<ResourceLocation> valid = counted();
        int n = 0;
        for (ResourceLocation id : new ArrayList<>(dex.getSpeciesRecords().keySet())) {
            if (!valid.contains(id)) continue;
            if (dex.getKnowledgeForSpecies(id) == PokedexEntryProgress.OWNED) n++;
        }
        return n;
    }

    public static int total() {
        return Math.max(1, counted().size());
    }

    public static double percent(ServerPlayer p) {
        return caught(p) * 100.0 / total();
    }

    public static boolean reached(ServerPlayer p, DexConfig.Tier t, int caught) {
        if ("count".equals(t.mode)) return caught >= t.required;
        return caught * 100.0 / total() + 1e-9 >= t.required;
    }

    private static String need(DexConfig.Tier t) {
        return "count".equals(t.mode) ? (int) t.required + " espèces" : fmt(t.required) + " %";
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.FRANCE, "%.1f", d);
    }

    public static void open(ServerPlayer p) {
        if (!Cfg.dex.enabled) {
            Msg.send(p, "dex.disabled");
            return;
        }
        int caught = caught(p);
        PlayerData d = PlayerStore.get(p);
        List<Paged.Entry> entries = new ArrayList<>();
        for (DexConfig.Tier t : Cfg.dex.tiers) {
            boolean ok = reached(p, t, caught);
            boolean claimed = d.dexClaimed.contains(t.id);
            ItemBuilder b = ItemBuilder.of(claimed ? new ItemStack(Items.LIME_STAINED_GLASS_PANE) : Reg.stack(t.icon)).name(t.name).lore(t.lore);
            b.lore(Msg.format("dex.tier_need", "need", need(t)));
            b.lore(Msg.format("dex.tier_rewards"));
            for (Reward r : t.rewards) b.lore("  &8• " + Rewards.describe(r) + (r.chance < 100 ? " &8(" + fmt(r.chance) + " %)" : ""));
            b.lore("", Msg.format(claimed ? "dex.status_claimed" : ok ? "dex.status_ready" : "dex.status_locked"));
            b.glow(ok && !claimed);
            entries.add(new Paged.Entry(b.hideExtra().build(), (pl, c) -> {
                if (claim(pl, t)) open(pl);
            }));
        }
        Paged.open(p, Msg.format("dex.title", "caught", caught, "total", total(), "percent", fmt(caught * 100.0 / total())), entries, 0, Hub::open, gui -> {
            gui.set(49, ItemBuilder.of(Items.BOOK).name(Msg.format("dex.progress", "caught", caught, "total", total(), "percent", fmt(caught * 100.0 / total())))
                    .lore(Msg.format("dex.progress_lore")).build());
            gui.button(48, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
            gui.button(50, ItemBuilder.of(Items.HOPPER).name(Msg.format("dex.claim_all")).build(), pl -> {
                claimAll(pl);
                open(pl);
            });
            if (Perms.admin(p, "dexrewards")) gui.button(53, ItemBuilder.of(Items.WRITABLE_BOOK).name(Msg.format("dex.editor_button")).build(), DexRewards::editor);
        });
    }

    public static int claimAll(ServerPlayer p) {
        int n = 0;
        int caught = caught(p);
        PlayerData d = PlayerStore.get(p);
        for (DexConfig.Tier t : Cfg.dex.tiers) {
            if (!d.dexClaimed.contains(t.id) && reached(p, t, caught) && claim(p, t)) n++;
        }
        if (n == 0) Msg.send(p, "dex.nothing");
        return n;
    }

    public static boolean claim(ServerPlayer p, DexConfig.Tier t) {
        PlayerData d = PlayerStore.get(p);
        if (d.dexClaimed.contains(t.id)) {
            Msg.send(p, "dex.already");
            return false;
        }
        if (!reached(p, t, caught(p))) {
            Msg.send(p, "dex.locked", "need", need(t));
            return false;
        }
        d.dexClaimed.add(t.id);
        PlayerStore.dirty(p.getUUID());
        PlayerStore.save(p.getUUID());
        List<String> got = new ArrayList<>();
        for (Reward r : t.rewards) {
            String s = Rewards.roll(p, r);
            if (s != null) got.add(s);
        }
        Players.saveNow(p);
        Msg.send(p, "dex.claimed", "tier", TextUtil.strip(t.name), "rewards", got.isEmpty() ? "-" : String.join("&7, ", got));
        return true;
    }

    // ------------------------------------------------------------------ editeur (cobblepulse.admin.dexrewards)

    private static void save() {
        Cfg.dex.normalize();
        Cfg.save("dexrewards.json", Cfg.dex);
    }

    public static void editor(ServerPlayer p) {
        if (!Perms.admin(p, "dexrewards")) {
            Msg.send(p, "general.no_permission");
            return;
        }
        List<Paged.Entry> entries = new ArrayList<>();
        for (DexConfig.Tier t : Cfg.dex.tiers) {
            entries.add(new Paged.Entry(ItemBuilder.of(Reg.stack(t.icon)).name(t.name)
                    .lore(Msg.format("dex.editor_info", "id", t.id, "need", need(t), "rewards", t.rewards.size()))
                    .lore("", Msg.format("dex.editor_click")).hideExtra().build(), (pl, c) -> edit(pl, t)));
        }
        Paged.open(p, Msg.format("dex.editor_title"), entries, 0, StaffPanel::open, gui -> {
            gui.button(51, ItemBuilder.of(Items.LIME_DYE).name(Msg.format("dex.editor_new")).build(),
                    pl -> ChatInput.ask(pl, "dex.ask_new", txt -> {
                        String[] parts = txt.trim().split("\\s+");
                        Long v = parts.length > 0 ? ChatInput.parseAmount(parts[0].replace("%", "")) : null;
                        if (v == null) {
                            editor(pl);
                            return;
                        }
                        DexConfig.Tier t = new DexConfig.Tier();
                        t.mode = parts.length > 1 && parts[1].startsWith("esp") ? "count" : "percent";
                        t.required = v;
                        t.id = (t.mode.equals("count") ? "c" : "p") + v;
                        t.name = "&e" + need(t);
                        Cfg.dex.tiers.add(t);
                        save();
                        edit(pl, t);
                    }));
            gui.button(47, ItemBuilder.of(Cfg.dex.enabled ? Items.LIME_DYE : Items.GRAY_DYE).name(Msg.format(Cfg.dex.enabled ? "dex.editor_on" : "dex.editor_off")).build(), pl -> {
                Cfg.dex.enabled = !Cfg.dex.enabled;
                save();
                editor(pl);
            });
        });
    }

    public static void edit(ServerPlayer p, DexConfig.Tier t) {
        Gui g = new Gui(6, Msg.format("dex.edit_title", "id", t.id));
        for (int i = 45; i < 54; i++) g.set(i, Gui.pane());
        int slot = 0;
        for (Reward r : new ArrayList<>(t.rewards)) {
            if (slot >= 45) break;
            g.button(slot++, ItemBuilder.of(Rewards.icon(r)).name(Rewards.describe(r)).lore(Msg.format("dex.edit_reward_lore", "chance", fmt(r.chance))).build(), (pl, c) -> {
                if (c.shift()) {
                    t.rewards.remove(r);
                    save();
                    edit(pl, t);
                } else {
                    ChatInput.ask(pl, "dex.ask_chance", txt -> {
                        Long v = ChatInput.parseAmount(txt.replace("%", ""));
                        if (v != null) r.chance = Math.max(0, Math.min(100, v));
                        save();
                        edit(pl, t);
                    }, () -> edit(pl, t));
                }
            });
        }
        g.button(45, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), DexRewards::editor);
        g.button(46, ItemBuilder.of(Items.CHEST).name(Msg.format("dex.edit_add_item")).build(), pl -> {
            ItemStack hand = pl.getMainHandItem();
            if (hand.isEmpty()) {
                Msg.send(pl, "warps.edit_icon_empty");
                return;
            }
            t.rewards.add(Reward.item(ItemSerial.toSnbt(hand), 100));
            save();
            edit(pl, t);
        });
        g.button(47, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("dex.edit_add_money")).build(), pl -> ChatInput.ask(pl, "dex.ask_money", txt -> {
            Long v = ChatInput.parseAmount(txt);
            if (v != null && v > 0) t.rewards.add(Reward.money(v, 100));
            save();
            edit(pl, t);
        }, () -> edit(pl, t)));
        g.button(48, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.format("dex.edit_add_command")).build(), pl -> ChatInput.ask(pl, "kits.ask_command", txt -> {
            t.rewards.add(Reward.command(txt.startsWith("/") ? txt.substring(1) : txt, "", 100));
            save();
            edit(pl, t);
        }, () -> edit(pl, t)));
        g.button(49, ItemBuilder.of(Reg.stack(t.icon)).name(t.name).lore(Msg.format("dex.edit_icon_lore")).build(), pl -> {
            if (!pl.getMainHandItem().isEmpty()) t.icon = Reg.id(pl.getMainHandItem().getItem());
            save();
            edit(pl, t);
        });
        g.button(50, ItemBuilder.of(Items.NAME_TAG).name(Msg.format("dex.edit_name")).build(), pl -> ChatInput.ask(pl, "kits.ask_display", txt -> {
            t.name = txt;
            save();
            edit(pl, t);
        }, () -> edit(pl, t)));
        g.button(51, ItemBuilder.of(Items.EXPERIENCE_BOTTLE).name(Msg.format("dex.edit_need", "need", need(t))).build(), pl -> ChatInput.ask(pl, "dex.ask_need", txt -> {
            String[] parts = txt.trim().split("\\s+");
            Long v = parts.length > 0 ? ChatInput.parseAmount(parts[0].replace("%", "")) : null;
            if (v != null) {
                t.required = v;
                t.mode = parts.length > 1 && parts[1].startsWith("esp") ? "count" : "percent";
            }
            save();
            edit(pl, t);
        }, () -> edit(pl, t)));
        g.button(53, ItemBuilder.of(Items.LAVA_BUCKET).name(Msg.format("dex.edit_delete")).lore(Msg.format("warps.edit_delete_lore")).build(), (pl, c) -> {
            if (!c.shift()) {
                Msg.send(pl, "warps.edit_delete_shift");
                return;
            }
            Cfg.dex.tiers.remove(t);
            save();
            editor(pl);
        });
        g.open(p);
    }
}
