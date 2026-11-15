package fr.cobblepulse.utils.market;

import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.PokemonStores;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.pokemon.SpeciesLookup;
import fr.cobblepulse.utils.storage.Db;
import fr.cobblepulse.utils.teleport.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.argument;

/** STS : rachat instantane de Pokemon par le serveur, quota par 24 h (cobblepulse.sts.limit.N), journal d'audit. */
public final class Sts {
    private Sts() {}

    static Db.Source db() {
        return Db.of("sts_logs");
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "sts", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, StsMenus::open))
                .then(Cmd.lit("sell").requires(src -> !src.isPlayer() || Perms.sub(src.getPlayer(), "sts", "sell"))
                        .then(argument("slot", IntegerArgumentType.integer(1, 6)).executes(ctx -> Cmd.run(ctx, p ->
                                sell(p, IntegerArgumentType.getInteger(ctx, "slot") - 1, ok -> {
                                }))))));
    }

    public static boolean open() {
        return Cfg.sts.enabled;
    }

    public static int limit(ServerPlayer p) {
        return Perms.numeric(p, Perms.STS_LIMIT, Cfg.sts.defaultDailyLimit, true);
    }

    /** Prix de rachat d'un Pokemon. */
    public static long price(Pokemon pk) {
        Long fixed = Cfg.sts.speciesPrices.get(SpeciesLookup.ref(pk.getSpecies()));
        double v;
        if (fixed != null) v = fixed;
        else {
            int bst = 0;
            for (Stat s : PokemonText.STATS) {
                Integer b = pk.getForm().getBaseStats().get(s);
                bst += b == null ? 0 : b;
            }
            v = Cfg.sts.basePrice + Cfg.sts.perLevel * pk.getLevel() + Cfg.sts.perPerfectIv * PokemonText.perfectIvs(pk)
                    + Math.max(0, bst - 300) * Cfg.sts.perBstPoint;
        }
        Set<String> l = pk.getSpecies().getLabels();
        if (pk.getShiny()) v *= Cfg.sts.shinyMultiplier;
        if (l.contains("mythical")) v *= Cfg.sts.mythicalMultiplier;
        else if (l.contains("legendary")) v *= Cfg.sts.legendaryMultiplier;
        else if (l.contains("ultra_beast")) v *= Cfg.sts.ultraBeastMultiplier;
        else if (l.contains("paradox")) v *= Cfg.sts.paradoxMultiplier;
        return Math.max(1, Math.round(v));
    }

    public static CompletableFuture<Integer> soldToday(UUID u) {
        long since = System.currentTimeMillis() - 86_400_000L;
        return db().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + db().table("sts_logs") + " WHERE player=? AND at>?")) {
                ps.setString(1, u.toString());
                ps.setLong(2, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public static void sell(ServerPlayer p, int slot, Consumer<Boolean> done) {
        if (!open()) {
            Msg.send(p, "sts.closed");
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
        if (Cfg.sts.keepOnePartyMember && party.occupied() <= 1) {
            Msg.send(p, "gts.keep_one");
            done.accept(false);
            return;
        }
        if (PokemonText.listed(Cfg.sts.blacklistSpecies, pk)) {
            Msg.send(p, "sts.blacklisted");
            done.accept(false);
            return;
        }
        int max = limit(p);
        soldToday(p.getUUID()).whenComplete((count, t) -> Scheduler.next(() -> {
            if (t != null) {
                Msg.send(p, "general.db_error");
                done.accept(false);
                return;
            }
            if (count >= max) {
                Msg.send(p, "sts.quota", "max", max);
                done.accept(false);
                return;
            }
            if (party.get(slot) != pk) {
                done.accept(false);
                return;
            }
            long price = price(pk);
            String json = PokemonText.toJson(pk);
            String species = SpeciesLookup.ref(pk.getSpecies());
            String title = PokemonText.title(pk).getString();
            int level = pk.getLevel();
            boolean shiny = pk.getShiny();
            party.remove(pk);
            PokemonStores.saveNow(p);
            UUID u = p.getUUID();
            String name = p.getGameProfile().getName();
            db().txAsync(c -> Db.insert(c, "INSERT INTO " + db().table("sts_logs") + " (player, player_name, species, level, shiny, price, data, at) VALUES (?,?,?,?,?,?,?,?)",
                    u.toString(), name, species, level, shiny ? 1 : 0, price, json, System.currentTimeMillis())).whenComplete((id, t2) -> Scheduler.next(() -> {
                if (t2 != null) {
                    CobbleUtils.LOG.error("[CobblePulse-Utils] Rachat STS impossible : {}", t2.toString());
                    party.add(pk);
                    Msg.send(p, "general.db_error");
                    done.accept(false);
                    return;
                }
                Economy.deposit(u, price);
                Players.saveNow(p);
                Msg.send(p, "sts.sold", "pokemon", title, "money", Economy.format(price), "left", Math.max(0, max - count - 1), "max", max);
                done.accept(true);
            }));
        }));
    }
}
