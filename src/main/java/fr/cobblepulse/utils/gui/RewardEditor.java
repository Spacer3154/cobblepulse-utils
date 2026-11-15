package fr.cobblepulse.utils.gui;

import fr.cobblepulse.utils.config.Reward;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Reg;
import fr.cobblepulse.utils.core.Rewards;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editeur generique d'une liste de recompenses : objet en main (NBT complet) ou choisi dans la liste de tous les objets,
 * argent, commande ; clic = chance, Maj + clic = retirer. Cases 50 a 53 libres pour l'appelant.
 */
public final class RewardEditor {
    private RewardEditor() {}

    public static void open(ServerPlayer p, String title, List<Reward> rewards, Runnable save, Consumer<ServerPlayer> back, Consumer<Gui> extras) {
        Gui g = new Gui(6, title);
        for (int i = 45; i < 54; i++) g.set(i, Gui.pane());
        Runnable reopen = () -> open(p, title, rewards, save, back, extras);
        int slot = 0;
        for (Reward r : new ArrayList<>(rewards)) {
            if (slot >= 45) break;
            g.button(slot++, ItemBuilder.of(Rewards.icon(r)).name(Rewards.describe(r))
                    .lore(Msg.format("dex.edit_reward_lore", "chance", fmt(r.chance))).build(), (pl, c) -> {
                if (c.shift()) {
                    rewards.remove(r);
                    save.run();
                    reopen.run();
                } else {
                    ChatInput.ask(pl, "dex.ask_chance", txt -> {
                        Long v = ChatInput.parseAmount(txt.replace("%", ""));
                        if (v != null) r.chance = Math.max(0, Math.min(100, v));
                        save.run();
                        reopen.run();
                    }, reopen);
                }
            });
        }
        if (rewards.isEmpty()) g.set(22, ItemBuilder.of(Items.BARRIER).name(Msg.format("rewards.none")).build());
        g.button(45, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), back::accept);
        g.button(46, ItemBuilder.of(Items.CHEST).name(Msg.format("rewards.add_hand")).lore(Msg.format("rewards.add_hand_lore")).build(), pl -> {
            ItemStack hand = pl.getMainHandItem();
            if (hand.isEmpty()) {
                Msg.send(pl, "warps.edit_icon_empty");
                return;
            }
            rewards.add(Reward.item(ItemSerial.toSnbt(hand), 100));
            save.run();
            reopen.run();
        });
        g.button(47, ItemBuilder.of(Items.ITEM_FRAME).name(Msg.format("rewards.add_pick")).lore(Msg.format("rewards.add_pick_lore")).build(),
                pl -> Pickers.item(pl, "rewards.pick_title", id -> ChatInput.ask(pl, "rewards.ask_count", txt -> {
                    Long n = ChatInput.parseAmount(txt);
                    rewards.add(Reward.item(id + " " + (n == null || n < 1 ? 1 : Math.min(n, 6400)), 100));
                    save.run();
                    reopen.run();
                }, reopen), reopen));
        g.button(48, ItemBuilder.of(Items.GOLD_INGOT).name(Msg.format("dex.edit_add_money")).build(), pl -> ChatInput.ask(pl, "dex.ask_money", txt -> {
            Long v = ChatInput.parseAmount(txt);
            if (v != null && v > 0) rewards.add(Reward.money(v, 100));
            save.run();
            reopen.run();
        }, reopen));
        g.button(49, ItemBuilder.of(Items.COMMAND_BLOCK).name(Msg.format("dex.edit_add_command")).build(), pl -> ChatInput.ask(pl, "kits.ask_command", txt -> {
            rewards.add(Reward.command(txt.startsWith("/") ? txt.substring(1) : txt, "", 100));
            save.run();
            reopen.run();
        }, reopen));
        if (extras != null) extras.accept(g);
        g.open(p);
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.FRANCE, "%.1f", d);
    }

    /** Icone choisie : identifiant valide ou objet par defaut. */
    public static String iconOr(String id, String def) {
        return Reg.itemExists(id) ? id : def;
    }
}
