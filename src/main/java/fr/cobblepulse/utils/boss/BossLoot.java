package fr.cobblepulse.utils.boss;

import com.cobblemon.mod.common.battles.BattleRegistry;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.gui.Screens;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fenetre de butin apres une victoire contre un Boss : chaque objet peut etre recupere, lache au sol ou jete
 * (detruit), un par un ou tous ensemble. Fermer la fenetre place le reste dans l'inventaire : rien n'est perdu
 * sans un choix explicite. L'argent, les BP et les commandes sont remis directement, hors fenetre.
 */
public final class BossLoot {
    private static final int MAX_SHOWN = 45;

    private static final class Pending {
        final List<ItemStack> items = new ArrayList<>();
        Component title;
        /** Instant (ms) a partir duquel la fenetre peut s'ouvrir. */
        long ready;
        Gui gui;
        long destroyAsked;
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private BossLoot() {}

    /** Propose ces objets au joueur ; la fenetre s'ouvre une fois le combat termine. */
    public static void offer(ServerPlayer p, Component title, List<ItemStack> items) {
        if (items.isEmpty()) return;
        Pending pd = PENDING.computeIfAbsent(p.getUUID(), k -> new Pending());
        if (pd.gui != null) {
            // une fenetre est deja ouverte (deux Boss vaincus coup sur coup) : on la reconstruit avec tout le butin
            Gui old = pd.gui;
            pd.gui = null;
            if (Screens.hasSession(p.getUUID()) || p.containerMenu != p.inventoryMenu) Gui.close(p);
            CobbleUtils.LOG.debug("[CobblePulse-Utils] Butin de Boss fusionne pour {} ({})", p.getGameProfile().getName(), old.size());
        }
        for (ItemStack st : items) if (!st.isEmpty()) pd.items.add(st.copy());
        pd.title = title;
        // ecran du mod : le client attend lui-meme la fin de l'animation du combat ; coffre : delai fixe
        pd.ready = System.currentTimeMillis() + (Screens.enabled(p) ? 1000 : 4000);
    }

    /** Toutes les 10 ticks : ouvre les fenetres en attente des joueurs sortis de combat. */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> en : new ArrayList<>(PENDING.entrySet())) {
            Pending pd = en.getValue();
            if (pd.gui != null || now < pd.ready) continue;
            ServerPlayer p = Players.online(en.getKey());
            if (p == null || p.hasDisconnected()) {
                // deconnexion sans passage par flush (ne devrait pas arriver) : conserve 10 min puis abandonne
                if (now - pd.ready > 600_000L) PENDING.remove(en.getKey());
                continue;
            }
            try {
                if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) continue;
            } catch (Throwable ignored) {
            }
            open(p);
        }
    }

    public static boolean has(UUID uuid) {
        return PENDING.containsKey(uuid);
    }

    /** Auto-test : objets encore en attente. */
    public static List<ItemStack> pending(UUID uuid) {
        Pending pd = PENDING.get(uuid);
        List<ItemStack> out = new ArrayList<>();
        if (pd != null) for (ItemStack st : pd.items) if (st != null && !st.isEmpty()) out.add(st);
        return out;
    }

    public static boolean isOpen(UUID uuid) {
        Pending pd = PENDING.get(uuid);
        return pd != null && pd.gui != null;
    }

    public static void open(ServerPlayer p) {
        Pending pd = PENDING.get(p.getUUID());
        if (pd == null) return;
        pd.items.removeIf(st -> st == null || st.isEmpty());
        // au-dela de la place affichable, le surplus va directement dans l'inventaire
        while (pd.items.size() > MAX_SHOWN) fr.cobblepulse.utils.core.Rewards.giveItem(p, pd.items.remove(pd.items.size() - 1));
        if (pd.items.isEmpty()) {
            PENDING.remove(p.getUUID());
            return;
        }
        int n = pd.items.size();
        int rows = Math.max(2, (n + 8) / 9 + 1);
        Gui g = new Gui(rows, pd.title);
        g.afterBattle(true);
        for (int i = 0; i < n; i++) {
            int slot = i;
            g.button(slot, icon(pd.items.get(i)), (pl, click) -> itemClick(pl, pd, g, slot, click));
        }
        int bar = (rows - 1) * 9;
        for (int i = bar; i < bar + 9; i++) g.set(i, Gui.pane());
        g.button(bar + 2, ItemBuilder.of(Items.CHEST).name(Msg.format("boss.loot_take_all")).lore(Msg.format("boss.loot_take_all_lore")).build(),
                pl -> takeAll(pl, pd, g));
        g.button(bar + 4, ItemBuilder.of(Items.DROPPER).name(Msg.format("boss.loot_drop_all")).lore(Msg.format("boss.loot_drop_all_lore")).build(),
                pl -> dropAll(pl, pd, g));
        destroyButton(pd, g, false);
        g.onClose(pl -> closed(pl, pd, g));
        pd.gui = g;
        pd.destroyAsked = 0;
        g.open(p);
    }

    private static void destroyButton(Pending pd, Gui g, boolean confirm) {
        int slot = (g.rows() - 1) * 9 + 6;
        g.button(slot, ItemBuilder.of(confirm ? Items.TNT : Items.LAVA_BUCKET).name(Msg.format(confirm ? "boss.loot_destroy_confirm" : "boss.loot_destroy_all"))
                .lore(Msg.format("boss.loot_destroy_all_lore")).build(), pl -> destroyAll(pl, pd, g));
    }

    /** L'objet lui-meme (avec son infobulle), suivi du rappel des trois actions. */
    private static ItemStack icon(ItemStack st) {
        ItemStack d = st.copy();
        List<Component> lines = new ArrayList<>(d.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        for (String part : Msg.format("boss.loot_item_lore").split("\n|\\|")) lines.add(TextUtil.item(part));
        d.set(DataComponents.LORE, new ItemLore(lines));
        return d;
    }

    private static boolean current(ServerPlayer p, Pending pd, Gui g) {
        return PENDING.get(p.getUUID()) == pd && pd.gui == g;
    }

    private static void itemClick(ServerPlayer p, Pending pd, Gui g, int slot, Gui.Click click) {
        if (!current(p, pd, g) || slot >= pd.items.size()) return;
        ItemStack st = pd.items.get(slot);
        if (st == null || st.isEmpty()) return;
        if (click.shift()) {
            pd.items.set(slot, ItemStack.EMPTY);
        } else if (click.right()) {
            drop(p, st);
            pd.items.set(slot, ItemStack.EMPTY);
        } else {
            ItemStack rest = take(p, st);
            pd.items.set(slot, rest);
            if (!rest.isEmpty()) Msg.send(p, "boss.loot_full");
        }
        refresh(p, pd, g, slot);
    }

    private static void refresh(ServerPlayer p, Pending pd, Gui g, int slot) {
        ItemStack st = pd.items.get(slot);
        if (st.isEmpty()) g.set(slot, ItemStack.EMPTY);
        else g.button(slot, icon(st), (pl, click) -> itemClick(pl, pd, g, slot, click));
        if (pd.items.stream().allMatch(ItemStack::isEmpty)) finish(p, pd);
    }

    private static void takeAll(ServerPlayer p, Pending pd, Gui g) {
        if (!current(p, pd, g)) return;
        boolean full = false;
        for (int i = 0; i < pd.items.size(); i++) {
            ItemStack st = pd.items.get(i);
            if (st.isEmpty()) continue;
            ItemStack rest = take(p, st);
            pd.items.set(i, rest);
            if (!rest.isEmpty()) full = true;
        }
        if (full) {
            Msg.send(p, "boss.loot_full");
            for (int i = 0; i < pd.items.size(); i++) {
                int slot = i;
                ItemStack st = pd.items.get(i);
                if (st.isEmpty()) g.set(slot, ItemStack.EMPTY);
                else g.button(slot, icon(st), (pl, click) -> itemClick(pl, pd, g, slot, click));
            }
            return;
        }
        Msg.send(p, "boss.loot_taken");
        finish(p, pd);
    }

    private static void dropAll(ServerPlayer p, Pending pd, Gui g) {
        if (!current(p, pd, g)) return;
        for (ItemStack st : pd.items) if (!st.isEmpty()) drop(p, st);
        pd.items.clear();
        Msg.send(p, "boss.loot_dropped");
        finish(p, pd);
    }

    /** Destruction definitive : un second clic dans les 5 secondes confirme. */
    private static void destroyAll(ServerPlayer p, Pending pd, Gui g) {
        if (!current(p, pd, g)) return;
        long now = System.currentTimeMillis();
        if (now - pd.destroyAsked > 5000) {
            pd.destroyAsked = now;
            destroyButton(pd, g, true);
            fr.cobblepulse.utils.core.Scheduler.later(100, () -> {
                if (current(p, pd, g) && System.currentTimeMillis() - pd.destroyAsked >= 5000) destroyButton(pd, g, false);
            });
            return;
        }
        pd.items.clear();
        Msg.send(p, "boss.loot_destroyed");
        finish(p, pd);
    }

    private static void finish(ServerPlayer p, Pending pd) {
        PENDING.remove(p.getUUID(), pd);
        pd.gui = null;
        Gui.close(p);
        Players.saveNow(p);
    }

    /** Fenetre fermee sans choix (Echap, autre menu) : le reste va dans l'inventaire, le surplus au sol. */
    private static void closed(ServerPlayer p, Pending pd, Gui g) {
        if (!current(p, pd, g)) return;
        PENDING.remove(p.getUUID(), pd);
        pd.gui = null;
        boolean any = false;
        for (ItemStack st : pd.items) if (!st.isEmpty()) {
            fr.cobblepulse.utils.core.Rewards.giveItem(p, st.copy());
            any = true;
        }
        pd.items.clear();
        if (any && !p.hasDisconnected()) Msg.send(p, "boss.loot_closed");
    }

    /** Deconnexion ou arret du serveur : le butin en attente est place dans l'inventaire avant l'enregistrement du joueur. */
    public static void flush(ServerPlayer p) {
        Pending pd = PENDING.remove(p.getUUID());
        if (pd == null) return;
        pd.gui = null;
        for (ItemStack st : pd.items) if (st != null && !st.isEmpty()) fr.cobblepulse.utils.core.Rewards.giveItem(p, st.copy());
        pd.items.clear();
    }

    public static void flushAll() {
        for (UUID u : new ArrayList<>(PENDING.keySet())) {
            ServerPlayer p = Players.online(u);
            if (p != null) flush(p);
        }
        PENDING.clear();
    }

    /** Place dans l'inventaire ce qui rentre. @return le reste (vide si tout est entre) */
    private static ItemStack take(ServerPlayer p, ItemStack st) {
        ItemStack rest = st.copy();
        int max = Math.max(1, rest.getMaxStackSize());
        int left = rest.getCount();
        while (left > 0) {
            ItemStack part = rest.copyWithCount(Math.min(max, left));
            int asked = part.getCount();
            p.getInventory().add(part);
            int added = asked - part.getCount();
            left -= added;
            if (added < asked) break;
        }
        return left <= 0 ? ItemStack.EMPTY : rest.copyWithCount(left);
    }

    private static void drop(ServerPlayer p, ItemStack st) {
        int max = Math.max(1, st.getMaxStackSize());
        int left = st.getCount();
        while (left > 0) {
            ItemStack part = st.copyWithCount(Math.min(max, left));
            left -= part.getCount();
            p.drop(part, false);
        }
    }
}
