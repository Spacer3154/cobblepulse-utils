package fr.cobblepulse.utils.shop;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.config.ShopConfig;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.PokemonFactory;
import fr.cobblepulse.utils.pokemon.PokemonText;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Logique du Shop Admin : prix (promotions + reduction personnelle), achats et ventes groupes. */
public final class ShopService {
    private ShopService() {}

    /** Reduction personnelle (cobblepulse.shop.discount.N, la plus forte). */
    public static double personalDiscount(ServerPlayer p) {
        return Math.max(0, Math.min(100, Perms.numeric(p, Perms.SHOP_DISCOUNT, 0, true)));
    }

    /** Prix d'achat unitaire effectif (promotions globale, categorie, article et personnelle cumulees). */
    public static long unitBuy(ServerPlayer p, ShopConfig.Category c, ShopConfig.Entry e) {
        if (e.buy < 0) return -1;
        double price = e.buy;
        price *= 1 - Cfg.shop.globalDiscount / 100.0;
        price *= 1 - c.discount / 100.0;
        price *= 1 - e.discount / 100.0;
        if (p != null) price *= 1 - personalDiscount(p) / 100.0;
        return Math.max(0, Math.round(price));
    }

    public static double totalDiscount(ServerPlayer p, ShopConfig.Category c, ShopConfig.Entry e) {
        double m = (1 - Cfg.shop.globalDiscount / 100.0) * (1 - c.discount / 100.0) * (1 - e.discount / 100.0) * (1 - personalDiscount(p) / 100.0);
        return Math.round((1 - m) * 1000) / 10.0;
    }

    public static ItemStack stack(ShopConfig.Entry e) {
        return ItemSerial.fromSnbt(e.item);
    }

    public static boolean open(ShopConfig.Category c) {
        return Cfg.shop.enabled && c.enabled;
    }

    public static String label(ShopConfig.Entry e) {
        if (e.display != null && !e.display.isBlank()) return e.display;
        if ("pokemon".equals(e.type) && e.pokemon != null) return "&e" + e.pokemon.species + " &7Nv." + e.pokemon.level + (e.pokemon.shiny ? " &e★" : "");
        ItemStack st = stack(e);
        return st.isEmpty() ? "&f" + e.id : "&f" + st.getHoverName().getString() + (st.getCount() > 1 ? " &7×" + st.getCount() : "");
    }

    /** Achat de {@code qty} lots. */
    public static boolean buy(ServerPlayer p, ShopConfig.Category c, ShopConfig.Entry e, int qty) {
        if (!open(c)) {
            Msg.send(p, "shop.closed_category", "category", TextUtil.strip(c.name));
            return false;
        }
        long unit = unitBuy(p, c, e);
        if (unit < 0 || qty <= 0) {
            Msg.send(p, "shop.not_buyable");
            return false;
        }
        if ("pokemon".equals(e.type)) qty = Math.min(qty, 6);
        long total = unit * qty;
        if (!Economy.has(p.getUUID(), total)) {
            Msg.send(p, "economy.not_enough", "money", Economy.format(total));
            return false;
        }
        List<Pokemon> mons = new ArrayList<>();
        if ("pokemon".equals(e.type)) {
            if (e.pokemon == null) return false;
            for (int i = 0; i < qty; i++) {
                List<String> errors = new ArrayList<>();
                Pokemon pk = PokemonFactory.create(fr.cobblepulse.utils.core.Json.copy(e.pokemon, fr.cobblepulse.utils.pokemon.MonSpec.class), errors);
                if (pk == null) {
                    Msg.send(p, "shop.invalid_entry");
                    CobbleUtils.LOG.warn("[CobblePulse-Utils] Article Pokemon invalide {} : {}", e.id, errors);
                    return false;
                }
                mons.add(pk);
            }
        }
        if (!Economy.withdraw(p.getUUID(), total)) {
            Msg.send(p, "economy.not_enough", "money", Economy.format(total));
            return false;
        }
        switch (e.type) {
            case "pokemon" -> {
                for (Pokemon pk : mons) PokemonText.party(p).add(pk);
            }
            case "command" -> {
                for (int i = 0; i < qty; i++) {
                    for (String cmd : e.commands) Rewards.run(cmd.replace("%amount%", "1"), p);
                }
            }
            default -> {
                ItemStack st = stack(e);
                if (st.isEmpty()) {
                    Economy.deposit(p.getUUID(), total);
                    Msg.send(p, "shop.invalid_entry");
                    return false;
                }
                Rewards.giveItem(p, st.copyWithCount(st.getCount() * qty));
            }
        }
        Players.saveNow(p);
        Msg.send(p, "shop.bought", "amount", qty, "item", label(e), "money", Economy.format(total));
        return true;
    }

    /** Nombre d'exemplaires de l'article dans l'inventaire. */
    public static int count(ServerPlayer p, ItemStack ref) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, ref)) n += s.getCount();
        }
        return n;
    }

    private static void remove(ServerPlayer p, ItemStack ref, int amount) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.items.size() && amount > 0; i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, ref)) continue;
            int take = Math.min(amount, s.getCount());
            s.shrink(take);
            amount -= take;
        }
        inv.setChanged();
    }

    /** Vente de {@code qty} lots (un lot = la quantite de l'article). qty = -1 : tout. */
    public static long sell(ServerPlayer p, ShopConfig.Category c, ShopConfig.Entry e, int qty, boolean quiet) {
        if (!Cfg.shop.allowSell || !open(c)) {
            if (!quiet) Msg.send(p, "shop.closed_category", "category", TextUtil.strip(c.name));
            return 0;
        }
        if (e.sell < 0 || !"item".equals(e.type)) {
            if (!quiet) Msg.send(p, "shop.not_sellable");
            return 0;
        }
        ItemStack ref = stack(e);
        if (ref.isEmpty()) return 0;
        int per = Math.max(1, ref.getCount());
        int have = count(p, ref) / per;
        int lots = qty < 0 ? have : Math.min(qty, have);
        if (lots <= 0) {
            if (!quiet) Msg.send(p, "shop.nothing_to_sell", "item", label(e));
            return 0;
        }
        long total = e.sell * lots;
        remove(p, ref, lots * per);
        if (!Economy.deposit(p.getUUID(), total)) {
            Rewards.giveItem(p, ref.copyWithCount(lots * per));
            Msg.send(p, "economy.error");
            return 0;
        }
        Players.saveNow(p);
        if (!quiet) Msg.send(p, "shop.sold", "amount", lots * per, "item", label(e), "money", Economy.format(total));
        return total;
    }

    /** Article du shop correspondant a un objet (pour /sell). */
    public static Object[] findSellable(ItemStack s) {
        for (ShopConfig.Category c : Cfg.SHOP.values()) {
            if (!open(c)) continue;
            for (ShopConfig.Entry e : c.entries) {
                if (!"item".equals(e.type) || e.sell < 0) continue;
                ItemStack ref = stack(e);
                if (!ref.isEmpty() && ItemStack.isSameItemSameComponents(ref, s)) return new Object[]{c, e};
            }
        }
        return null;
    }

    /** /sell hand [all] : vend l'objet en main, ou tout l'inventaire vendable. */
    public static void sellCommand(ServerPlayer p, boolean all) {
        if (!Cfg.shop.allowSell || !Cfg.shop.enabled) {
            Msg.send(p, "shop.closed");
            return;
        }
        long total = 0;
        int kinds = 0;
        if (!all) {
            ItemStack hand = p.getMainHandItem();
            if (hand.isEmpty()) {
                Msg.send(p, "shop.sell_hand_empty");
                return;
            }
            Object[] found = findSellable(hand);
            if (found == null) {
                Msg.send(p, "shop.not_sellable");
                return;
            }
            total = sell(p, (ShopConfig.Category) found[0], (ShopConfig.Entry) found[1], -1, false);
            return;
        }
        List<String> seen = new ArrayList<>();
        for (ItemStack s : new ArrayList<>(p.getInventory().items)) {
            if (s.isEmpty()) continue;
            Object[] found = findSellable(s);
            if (found == null) continue;
            ShopConfig.Entry e = (ShopConfig.Entry) found[1];
            String key = ((ShopConfig.Category) found[0]).id + "/" + e.id;
            if (seen.contains(key)) continue;
            seen.add(key);
            long got = sell(p, (ShopConfig.Category) found[0], e, -1, true);
            if (got > 0) {
                total += got;
                kinds++;
            }
        }
        if (total == 0) Msg.send(p, "shop.sell_all_nothing");
        else Msg.send(p, "shop.sell_all_done", "kinds", kinds, "money", Economy.format(total));
    }
}
