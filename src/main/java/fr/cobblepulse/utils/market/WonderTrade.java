package fr.cobblepulse.utils.market;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.pokemon.PokemonStores;
import fr.cobblepulse.utils.pokemon.PokemonText;
import fr.cobblepulse.utils.pokemon.SpeciesLookup;
import fr.cobblepulse.utils.storage.Db;
import fr.cobblepulse.utils.teleport.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.argument;

/**
 * Echange Miracle : le joueur depose un Pokemon dans un reservoir anonyme et en recoit un au hasard.
 * Echange atomique (DELETE du Pokemon tire + INSERT du depot dans la meme transaction), reservoir partageable en MySQL.
 */
public final class WonderTrade {
    private static final Random RND = new Random();

    private WonderTrade() {}

    static Db.Source db() {
        return Db.of("wondertrade_pool");
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "wondertrade", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, WonderTradeMenus::open))
                .then(argument("slot", IntegerArgumentType.integer(1, 6)).executes(ctx -> Cmd.run(ctx, p ->
                        trade(p, IntegerArgumentType.getInteger(ctx, "slot") - 1, r -> {
                        })))));
    }

    public static boolean open() {
        return Cfg.wt.enabled;
    }

    public record Stats(int poolSize, int totalTrades, int playerTrades, int playerShinies) {}

    public static CompletableFuture<Stats> stats(UUID u) {
        return db().async(c -> {
            int pool = count(c, "SELECT COUNT(*) FROM " + db().table("wt_pool"));
            int total = count(c, "SELECT COUNT(*) FROM " + db().table("wt_log"));
            int mine = 0;
            int shinies = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT trades, shinies FROM " + db().table("wt_stats") + " WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        mine = rs.getInt(1);
                        shinies = rs.getInt(2);
                    }
                }
            }
            return new Stats(pool, total, mine, shinies);
        });
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public static long cooldownLeft(ServerPlayer p) {
        if (Perms.perk(p, Perms.WT_BYPASS)) return 0;
        return PlayerStore.get(p).cooldownLeft("wondertrade");
    }

    /** Ligne « Prochain echange : ... » (Hub, menu, resultat). */
    public static String cooldownLine(ServerPlayer p) {
        long cd = cooldownLeft(p);
        return cd > 0 ? Msg.format("wt.cooldown_line", "time", TimeFmt.duration(cd)) : Msg.format("wt.cooldown_ready");
    }

    /** Barre de progression du delai (10 cases). */
    public static String cooldownBar(ServerPlayer p) {
        long cd = cooldownLeft(p);
        long total = Math.max(1, Cfg.wt.cooldownSeconds * 1000L);
        int done = cd <= 0 ? 10 : (int) Math.max(0, Math.min(10, Math.round((total - cd) * 10.0 / total)));
        return "&a" + "■".repeat(done) + "&8" + "■".repeat(10 - done);
    }

    /** Verifications du Pokemon depose (message d'erreur, ou null si accepte). */
    public static String refuse(ServerPlayer p, Pokemon pk) {
        var party = PokemonText.party(p);
        if (Cfg.wt.keepOnePartyMember && party.occupied() <= 1) return "gts.keep_one";
        if (pk.getLevel() < Cfg.wt.minLevel) return "wt.min_level";
        if (pk.getShiny() && !Cfg.wt.allowShiny) return "wt.no_shiny";
        Set<String> l = pk.getSpecies().getLabels();
        if (!Cfg.wt.allowLegendary && (l.contains("legendary") || l.contains("mythical") || l.contains("ultra_beast") || l.contains("paradox"))) return "wt.no_legendary";
        if (!pk.getTradeable() && !Cfg.wt.allowUntradeable) return "gts.untradeable";
        if (PokemonText.listed(Cfg.wt.blacklistSpecies, pk)) return "wt.blacklisted";
        return null;
    }

    /** @param done Pokemon recu (null en cas d'echec) */
    public static void trade(ServerPlayer p, int slot, Consumer<Pokemon> done) {
        if (!open()) {
            Msg.send(p, "wt.closed");
            done.accept(null);
            return;
        }
        var party = PokemonText.party(p);
        Pokemon pk = slot >= 0 && slot < 6 ? party.get(slot) : null;
        if (pk == null) {
            Msg.send(p, "gts.no_pokemon", "slot", slot + 1);
            done.accept(null);
            return;
        }
        if (Teleports.inBattle(p)) {
            Msg.send(p, "gts.in_battle");
            done.accept(null);
            return;
        }
        long cd = cooldownLeft(p);
        if (cd > 0) {
            Msg.send(p, "wt.cooldown", "time", TimeFmt.duration(cd));
            done.accept(null);
            return;
        }
        String why = refuse(p, pk);
        if (why != null) {
            Msg.send(p, why, "level", Cfg.wt.minLevel);
            done.accept(null);
            return;
        }
        String json = PokemonText.toJson(pk);
        String species = SpeciesLookup.ref(pk.getSpecies());
        boolean rare = PokemonText.isRare(pk);
        boolean shiny = pk.getShiny();
        int level = pk.getLevel();
        String depositedTitle = PokemonText.title(pk).getString();
        party.remove(pk);
        PokemonStores.saveNow(p);
        UUID u = p.getUUID();
        String name = p.getGameProfile().getName();
        db().txAsync(c -> {
            long pickId = -1;
            String pickData = null;
            String pickSpecies = null;
            boolean receivedShiny = false;
            try (PreparedStatement ps = c.prepareStatement("SELECT id, data, species, shiny FROM " + db().table("wt_pool") + " ORDER BY " + db().random() + " LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    pickId = rs.getLong(1);
                    pickData = rs.getString(2);
                    pickSpecies = rs.getString(3);
                    receivedShiny = rs.getInt(4) == 1;
                }
            }
            if (pickId < 0) return null;
            if (Db.update(c, "DELETE FROM " + db().table("wt_pool") + " WHERE id=?", pickId) != 1) throw new SQLException("Pokemon deja echange");
            Db.insert(c, "INSERT INTO " + db().table("wt_pool") + " (data, species, level, shiny, rare, depositor, depositor_name, deposited) VALUES (?,?,?,?,?,?,?,?)",
                    json, species, level, shiny ? 1 : 0, rare ? 1 : 0, u.toString(), name, System.currentTimeMillis());
            Db.insert(c, "INSERT INTO " + db().table("wt_log") + " (player, player_name, given, received, at) VALUES (?,?,?,?,?)",
                    u.toString(), name, species, pickSpecies, System.currentTimeMillis());
            String up = db().upsert("wt_stats", new String[]{"uuid", "name", "trades", "shinies", "last_at"}, new String[]{"uuid"});
            int prevTrades = 0;
            int prevShinies = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT trades, shinies FROM " + db().table("wt_stats") + " WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        prevTrades = rs.getInt(1);
                        prevShinies = rs.getInt(2);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(up)) {
                Db.bind(ps, u.toString(), name, prevTrades + 1, prevShinies + (receivedShiny ? 1 : 0), System.currentTimeMillis());
                ps.executeUpdate();
            }
            // remise via la boite de reception (survit a un arret brutal), reclamee juste apres
            Mailbox.add(c, u, "POKEMON", pickData, 0, Msg.format("wt.mail_reason"));
            return pickData;
        }).whenComplete((data, t) -> Scheduler.next(() -> {
            if (t != null || data == null) {
                if (t != null) CobbleUtils.LOG.error("[CobblePulse-Utils] Echange Miracle impossible : {}", t.toString());
                party.add(pk);
                PokemonStores.saveNow(p);
                Msg.send(p, data == null && t == null ? "wt.empty_pool" : "general.db_error");
                done.accept(null);
                return;
            }
            PlayerData pd = PlayerStore.get(p);
            pd.setCooldown("wondertrade", Cfg.wt.cooldownSeconds * 1000);
            PlayerStore.dirty(u);
            Pokemon received = PokemonText.fromJson(data);
            Gts.claimAllNow(p, () -> {
                if (received != null) Msg.send(p, "wt.received", "given", depositedTitle, "received", PokemonText.title(received).getString());
                if (rare && Cfg.wt.broadcastRareDeposits) Msg.broadcast("wt.broadcast_rare", "pokemon", depositedTitle);
                done.accept(received);
            });
        }));
    }

    // ------------------------------------------------------------------ reservoir

    /** Complete le reservoir avec des Pokemon aleatoires jusqu'a pool_size. */
    public static void refill() {
        db().async(c -> count(c, "SELECT COUNT(*) FROM " + db().table("wt_pool"))).whenComplete((n, t) -> Scheduler.next(() -> {
            if (t != null || n == null) return;
            int missing = Cfg.wt.poolSize - n;
            if (missing <= 0) return;
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < missing; i++) {
                Pokemon pk = generate();
                if (pk == null) continue;
                rows.add(new String[]{PokemonText.toJson(pk), SpeciesLookup.ref(pk.getSpecies()), String.valueOf(pk.getLevel()),
                        pk.getShiny() ? "1" : "0", PokemonText.isRare(pk) ? "1" : "0"});
            }
            db().txAsync(c -> {
                for (String[] r : rows) {
                    Db.insert(c, "INSERT INTO " + db().table("wt_pool") + " (data, species, level, shiny, rare, depositor, depositor_name, deposited) VALUES (?,?,?,?,?,?,?,?)",
                            r[0], r[1], Integer.parseInt(r[2]), Integer.parseInt(r[3]), Integer.parseInt(r[4]), null, "Serveur", System.currentTimeMillis());
                }
                return rows.size();
            }).whenComplete((k, t2) -> {
                if (t2 != null) CobbleUtils.LOG.error("[CobblePulse-Utils] Remplissage du reservoir miracle : {}", t2.toString());
                else CobbleUtils.LOG.info("[CobblePulse-Utils] Reservoir de l'Echange Miracle complete : +{} Pokemon", k);
            });
        }));
    }

    public static Pokemon generate() {
        List<Species> pool = new ArrayList<>();
        for (Species s : PokemonSpecies.getImplemented()) {
            Set<String> l = s.getLabels();
            if (!Cfg.wt.generatedAllowLegendary && (l.contains("legendary") || l.contains("mythical") || l.contains("ultra_beast") || l.contains("paradox"))) continue;
            pool.add(s);
        }
        pool.removeIf(s -> Cfg.wt.blacklistSpecies.contains(s.getResourceIdentifier().getPath()));
        if (pool.isEmpty()) return null;
        Species s = pool.get(RND.nextInt(pool.size()));
        int level = Cfg.wt.generatedLevelMin + RND.nextInt(Cfg.wt.generatedLevelMax - Cfg.wt.generatedLevelMin + 1);
        boolean shiny = RND.nextDouble() * 100 < Cfg.wt.generatedShinyChance;
        try {
            PokemonProperties pp = PokemonProperties.Companion.parse("level=" + level + " shiny=" + (shiny ? "yes" : "no"));
            pp.setSpecies(s.getResourceIdentifier().toString());
            return pp.create();
        } catch (Throwable t) {
            return null;
        }
    }
}
