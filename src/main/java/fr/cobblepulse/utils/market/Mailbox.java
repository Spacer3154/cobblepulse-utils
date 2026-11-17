package fr.cobblepulse.utils.market;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Rewards;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.PokemonStores;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.storage.Db;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Boite de reception (table mailbox) : paiements des ventes GTS, annonces expirees ou retirees, objets en attente.
 * Fonctionne en reseau (MySQL) : une vente sur un serveur est encaissee sur n'importe quel autre.
 * Remise anti-duplication : la ligne est supprimee (transaction validee) AVANT la remise au joueur.
 */
public final class Mailbox {
    public record Mail(long id, UUID owner, String kind, String data, long amount, String reason, long created) {}

    private Mailbox() {}

    static Db.Source db() {
        return Db.of("gts");
    }

    /** A appeler dans une transaction. */
    public static void add(Connection c, UUID owner, String kind, String data, long amount, String reason) throws SQLException {
        Db.insert(c, "INSERT INTO " + db().table("mailbox") + " (owner, kind, data, amount, reason, created) VALUES (?,?,?,?,?,?)",
                owner.toString(), kind, data == null ? "" : data, amount, reason == null ? "" : reason, System.currentTimeMillis());
    }

    public static CompletableFuture<List<Mail>> list(UUID owner) {
        return db().async(c -> {
            List<Mail> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, kind, data, amount, reason, created FROM " + db().table("mailbox")
                    + " WHERE owner=? ORDER BY id")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Mail(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getString(6), rs.getLong(7)));
                }
            }
            return out;
        });
    }

    /** Retire une ligne (validee) puis remet son contenu au joueur sur le thread principal. */
    public static void claim(ServerPlayer p, Mail m, Consumer<Boolean> done) {
        db().txAsync(c -> Db.update(c, "DELETE FROM " + db().table("mailbox") + " WHERE id=? AND owner=?", m.id, p.getUUID().toString()))
                .whenComplete((n, t) -> Scheduler.next(() -> {
                    if (t != null || n == null || n != 1) {
                        if (done != null) done.accept(false);
                        return;
                    }
                    boolean ok = deliver(p, m);
                    if (!ok) {
                        // remise impossible : la ligne est recreee
                        db().txAsync(c -> {
                            add(c, m.owner, m.kind, m.data, m.amount, m.reason);
                            return null;
                        });
                    }
                    if (done != null) done.accept(ok);
                }));
    }

    private static boolean deliver(ServerPlayer p, Mail m) {
        switch (m.kind) {
            case "MONEY" -> {
                if (!Economy.deposit(p.getUUID(), m.amount)) return false;
                Players.saveNow(p);
                Msg.send(p, "mailbox.money", "money", Economy.format(m.amount), "reason", m.reason);
                return true;
            }
            case "ITEM" -> {
                ItemStack st = ItemSerial.decodeOne(m.data);
                if (st.isEmpty()) return true;
                Rewards.giveItem(p, st);
                Players.saveNow(p);
                Msg.send(p, "mailbox.item", "item", st.getCount() + " × " + st.getHoverName().getString(), "reason", m.reason);
                return true;
            }
            case "POKEMON" -> {
                Pokemon pk = PokemonText.fromJson(m.data);
                if (pk == null) {
                    CobbleUtils.LOG.error("[CobblePulse-Utils] Pokemon illisible dans la boite de {} (#{}), conserve en base", p.getGameProfile().getName(), m.id);
                    return false;
                }
                PokemonText.party(p).add(pk);
                PokemonStores.saveNow(p);
                Msg.send(p, "mailbox.pokemon", "pokemon", fr.cobblepulse.utils.pokemon.SpeciesNames.text(pk.getSpecies()), "reason", m.reason);
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /** Encaisse automatiquement l'argent en attente (connexion + toutes les 30 s). */
    public static void autoClaimMoney(ServerPlayer p, boolean notify) {
        list(p.getUUID()).thenAccept(mails -> Scheduler.next(() -> {
            if (p.hasDisconnected()) return;
            for (Mail m : mails) if ("MONEY".equals(m.kind)) claim(p, m, null);
            long others = mails.stream().filter(m -> !"MONEY".equals(m.kind)).count();
            if (notify && others > 0) Msg.send(p, "mailbox.waiting", "count", others);
        }));
    }
}
