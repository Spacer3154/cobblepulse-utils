package fr.cobblepulse.utils.market;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.PokemonStores;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.storage.Db;
import fr.cobblepulse.utils.teleport.Teleports;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * GTS (Hotel des Ventes entre joueurs) : objets et Pokemon, taxes / quotas / durees par grade, prix planchers.
 * Achat atomique : UPDATE ... WHERE status='ACTIVE' (une seule vente possible, meme en reseau MySQL), paiement du vendeur
 * et remise de l'article par la boite de reception dans la meme transaction.
 */
public final class Gts {
    public record Listing(long id, UUID seller, String sellerName, String kind, String data, long price, String category,
                          long created, long expires, String status, UUID buyer, String buyerName, long closedAt, long tax) {
        public boolean pokemon() {
            return "POKEMON".equals(kind);
        }
    }

    private static final Map<Long, Object> DECODED = new ConcurrentHashMap<>();

    private Gts() {}

    static Db.Source db() {
        return Db.of("gts");
    }

    public static boolean open() {
        return Cfg.gts.enabled;
    }

    public static int tax(ServerPlayer p) {
        return Math.max(0, Math.min(100, Perms.numeric(p, Perms.GTS_TAX, Cfg.gts.defaultTax, false)));
    }

    public static int limit(ServerPlayer p) {
        return Perms.numeric(p, Perms.GTS_LIMIT, Cfg.gts.defaultLimit, true);
    }

    /** Duree incluse sans supplement (grade). */
    public static int durationHours(ServerPlayer p) {
        return Math.max(1, Perms.numeric(p, Perms.GTS_DURATION, Cfg.gts.defaultDurationHours, true));
    }

    /** Durees proposees au joueur : celles de gts.json, plus la duree incluse de son grade. */
    public static java.util.List<Integer> durationChoices(ServerPlayer p) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        set.add(durationHours(p));
        for (var d : Cfg.gts.durations) set.add(d.hours);
        return new java.util.ArrayList<>(set);
    }

    /** Supplement de taxe pour une duree (0 jusqu'a la duree incluse du grade). */
    public static int extraTax(ServerPlayer p, int hours) {
        if (hours <= durationHours(p)) return 0;
        int extra = 0;
        for (var d : Cfg.gts.durations) {
            if (d.hours == hours) return d.extraTax;
            if (d.hours < hours) extra = d.extraTax;
        }
        return extra;
    }

    /** Taxe totale pour une duree donnee (grade + supplement, plafonnee). */
    public static int taxFor(ServerPlayer p, int hours) {
        return Math.min(Math.max(tax(p), Cfg.gts.maxTax), tax(p) + extraTax(p, hours));
    }

    /** Duree valide (une des durees proposees) ou -1. */
    public static int validDuration(ServerPlayer p, int hours) {
        return durationChoices(p).contains(hours) ? hours : -1;
    }

    private static Listing read(ResultSet rs) throws SQLException {
        String buyer = rs.getString("buyer");
        return new Listing(rs.getLong("id"), UUID.fromString(rs.getString("seller")), rs.getString("seller_name"), rs.getString("kind"),
                rs.getString("data"), rs.getLong("price"), rs.getString("category"), rs.getLong("created"), rs.getLong("expires"),
                rs.getString("status"), buyer == null ? null : UUID.fromString(buyer), rs.getString("buyer_name"), rs.getLong("closed_at"), rs.getLong("tax"));
    }

    private static CompletableFuture<List<Listing>> query(String where, Object... args) {
        return db().async(c -> {
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + db().table("gts_listings") + " WHERE " + where)) {
                Db.bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            return out;
        });
    }

    public static CompletableFuture<List<Listing>> active() {
        return query("status='ACTIVE' AND expires>? ORDER BY created DESC", System.currentTimeMillis());
    }

    public static CompletableFuture<List<Listing>> mine(UUID seller) {
        return query("status='ACTIVE' AND seller=? ORDER BY created DESC", seller.toString());
    }

    public static CompletableFuture<List<Listing>> history(UUID u) {
        return query("status<>'ACTIVE' AND (seller=? OR buyer=?) ORDER BY closed_at DESC LIMIT 45", u.toString(), u.toString());
    }

    /** Objet ou Pokemon decode (mis en cache, donnees immuables). */
    public static Object decode(Listing l) {
        return DECODED.computeIfAbsent(l.id, k -> l.pokemon() ? PokemonText.fromJson(l.data) : ItemSerial.decodeOne(l.data));
    }

    public static ItemStack icon(Listing l, String... extra) {
        Object o = decode(l);
        if (o instanceof Pokemon pk) return PokemonText.icon(pk, extra);
        ItemStack st = o instanceof ItemStack s ? s.copy() : ItemStack.EMPTY;
        if (st.isEmpty()) return fr.cobblepulse.utils.core.ItemBuilder.of(net.minecraft.world.item.Items.BARRIER).name("&c?").build();
        return fr.cobblepulse.utils.core.ItemBuilder.of(st.copyWithCount(Math.max(1, Math.min(st.getMaxStackSize(), st.getCount()))))
                .lore("&7Quantité : &f" + st.getCount()).lore("").lore(extra).build();
    }

    public static String name(Listing l) {
        Object o = decode(l);
        if (o instanceof Pokemon pk) return PokemonText.title(pk).getString();
        if (o instanceof ItemStack st && !st.isEmpty()) return st.getCount() + " × " + st.getHoverName().getString();
        return "?";
    }

    private static long floor(String category) {
        Long v = Cfg.gts.priceFloors.get(category);
        return v == null ? 1 : Math.max(1, v);
    }

    // ------------------------------------------------------------------ mise en vente

    public static boolean checkCommon(ServerPlayer p, long price, String category) {
        if (!open()) {
            Msg.send(p, "gts.closed");
            return false;
        }
        long fl = floor(category);
        if (price < fl) {
            Msg.send(p, "gts.price_floor", "money", Economy.format(fl), "category", Msg.raw("gts.categories." + category));
            return false;
        }
        if (price > Cfg.gts.maxPrice) {
            Msg.send(p, "gts.price_max", "money", Economy.format(Cfg.gts.maxPrice));
            return false;
        }
        return true;
    }

    /** Met en vente l'objet en main (pile entiere), pour la duree incluse du grade. */
    public static void listHand(ServerPlayer p, long price, Consumer<Boolean> done) {
        listHand(p, price, durationHours(p), done);
    }

    public static void listHand(ServerPlayer p, long price, int hours, Consumer<Boolean> done) {
        if (validDuration(p, hours) < 0) {
            Msg.send(p, "gts.bad_duration", "choices", choicesText(p));
            done.accept(false);
            return;
        }
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty()) {
            Msg.send(p, "gts.hand_empty");
            done.accept(false);
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
        if (Cfg.gts.blacklistItems.contains(id)) {
            Msg.send(p, "gts.blacklisted");
            done.accept(false);
            return;
        }
        if (!checkCommon(p, price, "items")) {
            done.accept(false);
            return;
        }
        withQuota(p, () -> {
            ItemStack taken = p.getMainHandItem();
            if (taken.isEmpty()) {
                done.accept(false);
                return;
            }
            ItemStack copy = taken.copy();
            p.getInventory().removeItem(taken);
            p.getInventory().setChanged();
            Players.saveNow(p);
            insert(p, "ITEM", ItemSerial.encodeOne(copy), price, "items", hours, ok -> {
                if (!ok) Rewards.giveItem(p, copy);
                else announce(p, copy.getCount() + " × " + copy.getHoverName().getString(), price);
                done.accept(ok);
            });
        }, done);
    }

    public static void listPokemon(ServerPlayer p, int slot, long price, Consumer<Boolean> done) {
        listPokemon(p, slot, price, durationHours(p), done);
    }

    public static void listPokemon(ServerPlayer p, int slot, long price, int hours, Consumer<Boolean> done) {
        if (validDuration(p, hours) < 0) {
            Msg.send(p, "gts.bad_duration", "choices", choicesText(p));
            done.accept(false);
            return;
        }
        var party = PokemonText.party(p);
        Pokemon pk = slot >= 0 && slot < 6 ? party.get(slot) : null;
        if (pk == null) {
            Msg.send(p, "gts.no_pokemon", "slot", slot + 1);
            done.accept(false);
            return;
        }
        if (Teleports.inBattle(p)) {
            Msg.send(p, "gts.in_battle");
            done.accept(false);
            return;
        }
        if (Cfg.gts.keepOnePartyMember && party.occupied() <= 1) {
            Msg.send(p, "gts.keep_one");
            done.accept(false);
            return;
        }
        if (!pk.getTradeable() && !Cfg.gts.allowUntradeable) {
            Msg.send(p, "gts.untradeable");
            done.accept(false);
            return;
        }
        if (PokemonText.listed(Cfg.gts.blacklistSpecies, pk)) {
            Msg.send(p, "gts.blacklisted");
            done.accept(false);
            return;
        }
        String cat = PokemonText.category(pk);
        if (!checkCommon(p, price, cat)) {
            done.accept(false);
            return;
        }
        withQuota(p, () -> {
            Pokemon cur = party.get(slot);
            if (cur != pk) {
                done.accept(false);
                return;
            }
            String json = PokemonText.toJson(pk);
            party.remove(pk);
            PokemonStores.saveNow(p);
            insert(p, "POKEMON", json, price, cat, hours, ok -> {
                if (!ok) party.add(pk);
                else announce(p, PokemonText.title(pk).getString(), price);
                done.accept(ok);
            });
        }, done);
    }

    private static void withQuota(ServerPlayer p, Runnable then, Consumer<Boolean> done) {
        int max = limit(p);
        mine(p.getUUID()).whenComplete((list, t) -> Scheduler.next(() -> {
            if (t != null) {
                Msg.send(p, "general.db_error");
                done.accept(false);
                return;
            }
            if (list.size() >= max) {
                Msg.send(p, "gts.limit", "max", max);
                done.accept(false);
                return;
            }
            then.run();
        }));
    }

    public static String durationText(int hours) {
        return hours % 24 == 0 && hours >= 48 ? (hours / 24) + " j" : hours + " h";
    }

    public static String choicesText(ServerPlayer p) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int h : durationChoices(p)) out.add(durationText(h) + " (" + taxFor(p, h) + " %)");
        return String.join(", ", out);
    }

    private static void insert(ServerPlayer p, String kind, String data, long price, String category, int hours, Consumer<Boolean> done) {
        long now = System.currentTimeMillis();
        long exp = now + hours * 3_600_000L;
        UUID seller = p.getUUID();
        String name = p.getGameProfile().getName();
        int taxPercent = taxFor(p, hours);
        // tant que l'annonce est active, la colonne tax contient le pourcentage du vendeur ; a la vente, le montant preleve
        db().txAsync(c -> Db.insert(c, "INSERT INTO " + db().table("gts_listings")
                        + " (seller, seller_name, kind, data, summary, price, category, created, expires, status, server, tax) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                seller.toString(), name, kind, data, "", price, category, now, exp, "ACTIVE", Cfg.database.serverId, taxPercent))
                .whenComplete((id, t) -> Scheduler.next(() -> {
                    if (t != null) {
                        CobbleUtils.LOG.error("[CobblePulse-Utils] Mise en vente GTS impossible : {}", t.toString());
                        Msg.send(p, "general.db_error");
                        done.accept(false);
                        return;
                    }
                    Msg.send(p, "gts.listed", "money", Economy.format(price), "hours", durationText(hours), "tax", taxPercent);
                    done.accept(true);
                }));
    }

    private static void announce(ServerPlayer p, String what, long price) {
        if (!Cfg.gts.broadcastNewListings || price < Cfg.gts.broadcastMinPrice) return;
        Msg.broadcast(Msg.get("gts.broadcast", "player", p.getGameProfile().getName(), "item", what, "money", Economy.format(price)));
    }

    // ------------------------------------------------------------------ achat / retrait

    public static void buy(ServerPlayer p, Listing l, Consumer<Boolean> done) {
        if (!open()) {
            Msg.send(p, "gts.closed");
            done.accept(false);
            return;
        }
        if (l.seller.equals(p.getUUID())) {
            Msg.send(p, "gts.own_listing");
            done.accept(false);
            return;
        }
        if (!Economy.withdraw(p.getUUID(), l.price)) {
            Msg.send(p, "economy.not_enough", "money", Economy.format(l.price));
            done.accept(false);
            return;
        }
        Players.saveNow(p);
        long taxAmount = l.price * Math.max(0, Math.min(100, l.tax)) / 100;
        UUID buyer = p.getUUID();
        String buyerName = p.getGameProfile().getName();
        String what = name(l);
        db().txAsync(c -> {
            int n = Db.update(c, "UPDATE " + db().table("gts_listings") + " SET status='SOLD', buyer=?, buyer_name=?, closed_at=?, tax=?"
                    + " WHERE id=? AND status='ACTIVE' AND expires>?", buyer.toString(), buyerName, System.currentTimeMillis(), taxAmount, l.id,
                    System.currentTimeMillis());
            if (n != 1) return false;
            Mailbox.add(c, l.seller, "MONEY", "", l.price - taxAmount, Msg.format("gts.mail_sold", "item", what, "player", buyerName));
            Mailbox.add(c, buyer, l.kind, l.data, 0, Msg.format("gts.mail_bought", "player", l.sellerName));
            return true;
        }).whenComplete((ok, t) -> Scheduler.next(() -> {
            if (t != null || !Boolean.TRUE.equals(ok)) {
                Economy.deposit(buyer, l.price);
                Players.saveNow(p);
                Msg.send(p, t != null ? "general.db_error" : "gts.already_sold");
                done.accept(false);
                return;
            }
            Msg.send(p, "gts.bought", "item", what, "money", Economy.format(l.price), "player", l.sellerName);
            ServerPlayer seller = Players.online(l.seller);
            if (seller != null) {
                Msg.send(seller, "gts.sold_notify", "item", what, "player", buyerName, "money", Economy.format(l.price - taxAmount),
                        "tax", Economy.format(taxAmount));
            }
            claimAllNow(p, () -> {
                if (seller != null) Mailbox.autoClaimMoney(seller, false);
                done.accept(true);
            });
        }));
    }

    /** Retrait par le vendeur (ou le staff) : l'article revient par la boite de reception. */
    public static void cancel(ServerPlayer actor, Listing l, boolean staff, Consumer<Boolean> done) {
        if (!staff && !l.seller.equals(actor.getUUID())) {
            done.accept(false);
            return;
        }
        String status = staff ? "REMOVED" : "CANCELLED";
        String reason = Msg.format(staff ? "gts.mail_removed" : "gts.mail_cancelled");
        db().txAsync(c -> {
            int n = Db.update(c, "UPDATE " + db().table("gts_listings") + " SET status=?, closed_at=? WHERE id=? AND status='ACTIVE'",
                    status, System.currentTimeMillis(), l.id);
            if (n != 1) return false;
            Mailbox.add(c, l.seller, l.kind, l.data, 0, reason);
            return true;
        }).whenComplete((ok, t) -> Scheduler.next(() -> {
            if (t != null || !Boolean.TRUE.equals(ok)) {
                Msg.send(actor, "gts.already_sold");
                done.accept(false);
                return;
            }
            Msg.send(actor, staff ? "gts.removed_staff" : "gts.cancelled", "item", name(l));
            ServerPlayer seller = Players.online(l.seller);
            if (seller != null && seller == actor) claimAllNow(seller, () -> done.accept(true));
            else {
                if (seller != null) Msg.send(seller, "gts.removed_notify", "item", name(l));
                done.accept(true);
            }
        }));
    }

    /** Remet immediatement tout le contenu de la boite de reception. */
    public static void claimAllNow(ServerPlayer p, Runnable after) {
        Mailbox.list(p.getUUID()).whenComplete((mails, t) -> Scheduler.next(() -> {
            if (t != null || mails == null || mails.isEmpty()) {
                if (after != null) after.run();
                return;
            }
            int[] left = {mails.size()};
            for (Mailbox.Mail m : mails) {
                Mailbox.claim(p, m, ok -> {
                    if (--left[0] == 0 && after != null) after.run();
                });
            }
        }));
    }

    /** Toutes les minutes : les annonces expirees retournent a leur vendeur (boite de reception). */
    public static void expireTick() {
        long now = System.currentTimeMillis();
        db().txAsync(c -> {
            List<Listing> exp = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + db().table("gts_listings") + " WHERE status='ACTIVE' AND expires<=?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) exp.add(read(rs));
                }
            }
            int n = 0;
            for (Listing l : exp) {
                if (Db.update(c, "UPDATE " + db().table("gts_listings") + " SET status='EXPIRED', closed_at=? WHERE id=? AND status='ACTIVE'", now, l.id) == 1) {
                    Mailbox.add(c, l.seller, l.kind, l.data, 0, Msg.format("gts.mail_expired"));
                    n++;
                }
            }
            return n;
        }).whenComplete((n, t) -> {
            if (t != null) CobbleUtils.LOG.warn("[CobblePulse-Utils] Expiration GTS : {}", t.toString());
            else if (n != null && n > 0) CobbleUtils.LOG.info("[CobblePulse-Utils] {} annonce(s) GTS expiree(s)", n);
        });
    }

    public static String timeLeft(Listing l) {
        return TimeFmt.duration(Math.max(0, l.expires - System.currentTimeMillis()));
    }

    public static Component describe(Listing l) {
        return TextUtil.legacy(name(l));
    }
}
