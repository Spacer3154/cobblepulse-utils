package fr.cobblepulse.utils.integration;

import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Players;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Economie du serveur : CobbleDollars (par defaut) ou Impactor, choisie dans general.json (« economy »).
 * Les deux mods sont appeles par reflexion : aucun n'est requis pour lancer le mod.
 * Sans mod d'economie, un porte-monnaie interne (memoire, auto-test) est utilise et un avertissement est journalise.
 * Montants entiers. A appeler sur le thread principal.
 */
public final class Economy {
    private interface Backend {
        String name();

        long balance(UUID uuid);

        boolean withdraw(UUID uuid, long amount);

        boolean deposit(UUID uuid, long amount);
    }

    private static Backend backend;
    private static final Map<UUID, Long> TEST_WALLETS = new ConcurrentHashMap<>();
    /** Joueurs factices de l'auto-test utilisant un porte-monnaie en memoire. */
    private static final java.util.Set<UUID> TEST_USERS = ConcurrentHashMap.newKeySet();

    private Economy() {}

    public static void init() {
        String want = Cfg.general.economy;
        boolean cd = FabricLoader.getInstance().isModLoaded("cobbledollars");
        boolean imp = FabricLoader.getInstance().isModLoaded("impactor");
        Backend b = null;
        try {
            if ("IMPACTOR".equals(want) && imp) b = new ImpactorBackend();
            else if ("COBBLEDOLLARS".equals(want) && cd) b = new CobbleDollarsBackend();
            else if (cd) b = new CobbleDollarsBackend();
            else if (imp) b = new ImpactorBackend();
        } catch (Throwable t) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Economie {} indisponible : {}", want, t.toString());
        }
        if (b == null) {
            b = new MemoryBackend();
            CobbleUtils.LOG.warn("[CobblePulse-Utils] Aucun mod d'economie (CobbleDollars / Impactor) : Shop, GTS et STS utilisent un porte-monnaie temporaire.");
        }
        backend = b;
        CobbleUtils.LOG.info("[CobblePulse-Utils] Economie : {}", backend.name());
    }

    public static String name() {
        return backend == null ? "?" : backend.name();
    }

    public static boolean real() {
        return backend != null && !(backend instanceof MemoryBackend);
    }

    public static long balance(UUID uuid) {
        if (TEST_USERS.contains(uuid)) return TEST_WALLETS.getOrDefault(uuid, 0L);
        try {
            return backend.balance(uuid);
        } catch (Throwable t) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Solde de {} illisible : {}", uuid, t.toString());
            return 0;
        }
    }

    public static boolean has(UUID uuid, long amount) {
        return amount <= 0 || balance(uuid) >= amount;
    }

    public static boolean withdraw(UUID uuid, long amount) {
        if (amount <= 0) return true;
        if (TEST_USERS.contains(uuid)) {
            synchronized (TEST_WALLETS) {
                long b = TEST_WALLETS.getOrDefault(uuid, 0L);
                if (b < amount) return false;
                TEST_WALLETS.put(uuid, b - amount);
                return true;
            }
        }
        try {
            if (backend.balance(uuid) < amount) return false;
            return backend.withdraw(uuid, amount);
        } catch (Throwable t) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Retrait impossible ({} {}) : {}", uuid, amount, t.toString());
            return false;
        }
    }

    public static boolean deposit(UUID uuid, long amount) {
        if (amount <= 0) return true;
        if (TEST_USERS.contains(uuid)) {
            TEST_WALLETS.merge(uuid, amount, Long::sum);
            return true;
        }
        try {
            return backend.deposit(uuid, amount);
        } catch (Throwable t) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Depot impossible ({} {}) : {}", uuid, amount, t.toString());
            return false;
        }
    }

    /** Auto-test : porte-monnaie des joueurs factices. */
    public static void setTestBalance(UUID uuid, long amount) {
        TEST_USERS.add(uuid);
        TEST_WALLETS.put(uuid, amount);
    }

    /** Auto-test : force le vrai mod d'economie pour ce joueur factice. */
    public static void useRealBackend(UUID uuid) {
        TEST_USERS.remove(uuid);
    }

    public static String format(long amount) {
        String n = NumberFormat.getIntegerInstance(Locale.FRANCE).format(amount).replace(' ', ' ').replace(' ', ' ');
        return Cfg.general.moneyFormat.replace("%amount%", n);
    }

    // ------------------------------------------------------------------ CobbleDollars

    /** fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt (getCobbleDollars / setCobbleDollars, comptes hors ligne). */
    private static final class CobbleDollarsBackend implements Backend {
        private final Method get;
        private final Method set;
        private final Method getOffline;
        private final Method addOffline;

        CobbleDollarsBackend() throws Exception {
            Class<?> k = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt");
            get = k.getMethod("getCobbleDollars", Player.class);
            set = k.getMethod("setCobbleDollars", Player.class, BigInteger.class);
            getOffline = k.getMethod("getOfflineCobbleDollars", UUID.class, MinecraftServer.class);
            addOffline = k.getMethod("addOfflineCobbleDollars", UUID.class, MinecraftServer.class, BigInteger.class);
        }

        public String name() {
            return "CobbleDollars";
        }

        public long balance(UUID uuid) {
            try {
                ServerPlayer p = Players.online(uuid);
                Object v = p != null ? get.invoke(null, p) : getOffline.invoke(null, uuid, CobbleUtils.server());
                return v instanceof BigInteger bi ? clamp(bi) : 0;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public boolean withdraw(UUID uuid, long amount) {
            return change(uuid, -amount);
        }

        public boolean deposit(UUID uuid, long amount) {
            return change(uuid, amount);
        }

        private boolean change(UUID uuid, long delta) {
            try {
                ServerPlayer p = Players.online(uuid);
                if (p != null) {
                    BigInteger cur = (BigInteger) get.invoke(null, p);
                    BigInteger next = cur.add(BigInteger.valueOf(delta));
                    if (next.signum() < 0) return false;
                    set.invoke(null, p, next);
                    return true;
                }
                if (delta < 0) {
                    BigInteger cur = (BigInteger) getOffline.invoke(null, uuid, CobbleUtils.server());
                    if (cur == null || cur.compareTo(BigInteger.valueOf(-delta)) < 0) return false;
                }
                Object ok = addOffline.invoke(null, uuid, CobbleUtils.server(), BigInteger.valueOf(delta));
                return !(ok instanceof Boolean b) || b;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static long clamp(BigInteger bi) {
            return bi.bitLength() > 62 ? Long.MAX_VALUE / 2 : bi.longValue();
        }
    }

    // ------------------------------------------------------------------ Impactor

    /** net.impactdev.impactor.api.economy.EconomyService (comptes par UUID, montants BigDecimal). */
    private static final class ImpactorBackend implements Backend {
        private final Method instance;
        private final Method account;
        private final Class<?> accountClass;

        ImpactorBackend() throws Exception {
            Class<?> svc = Class.forName("net.impactdev.impactor.api.economy.EconomyService");
            instance = svc.getMethod("instance");
            account = svc.getMethod("account", UUID.class);
            accountClass = Class.forName("net.impactdev.impactor.api.economy.accounts.Account");
        }

        public String name() {
            return "Impactor";
        }

        private Object acc(UUID uuid) throws Exception {
            Object service = instance.invoke(null);
            Object fut = account.invoke(service, uuid);
            return ((java.util.concurrent.CompletableFuture<?>) fut).get(5, TimeUnit.SECONDS);
        }

        public long balance(UUID uuid) {
            try {
                BigDecimal bd = (BigDecimal) accountClass.getMethod("balance").invoke(acc(uuid));
                return bd.longValue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private boolean op(UUID uuid, String method, long amount) {
            try {
                Object tx = accountClass.getMethod(method, BigDecimal.class).invoke(acc(uuid), BigDecimal.valueOf(amount));
                Object ok = tx.getClass().getMethod("successful").invoke(tx);
                return Boolean.TRUE.equals(ok);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public boolean withdraw(UUID uuid, long amount) {
            return op(uuid, "withdraw", amount);
        }

        public boolean deposit(UUID uuid, long amount) {
            return op(uuid, "deposit", amount);
        }
    }

    // ------------------------------------------------------------------ Repli

    private static final class MemoryBackend implements Backend {
        public String name() {
            return "Aucune (porte-monnaie temporaire)";
        }

        public long balance(UUID uuid) {
            return TEST_WALLETS.getOrDefault(uuid, 0L);
        }

        public boolean withdraw(UUID uuid, long amount) {
            synchronized (TEST_WALLETS) {
                long b = TEST_WALLETS.getOrDefault(uuid, 0L);
                if (b < amount) return false;
                TEST_WALLETS.put(uuid, b - amount);
                return true;
            }
        }

        public boolean deposit(UUID uuid, long amount) {
            TEST_WALLETS.merge(uuid, amount, Long::sum);
            return true;
        }
    }
}
