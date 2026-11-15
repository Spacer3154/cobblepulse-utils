package fr.cobblepulse.utils.enderchest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.cobblepulse.utils.CobbleUtils;
import fr.cobblepulse.utils.command.Cmd;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.ItemSerial;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Players;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.data.PlayerData;
import fr.cobblepulse.utils.data.PlayerStore;
import fr.cobblepulse.utils.essentials.ChatFeatures;
import fr.cobblepulse.utils.essentials.EssentialsCommands;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.storage.Db;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;

/**
 * Enderchests evolutifs : 1 a 6 lignes (cobblepulse.ec.rows.N) et 1 a 5 pages (cobblepulse.ec.pages.N),
 * stockes en base (table enderchests). Une page ouverte par plusieurs personnes (joueur + /endersee) partage
 * le meme conteneur ; elle est enregistree a la fermeture du dernier spectateur et toutes les minutes.
 */
public final class Enderchests {
    public static final int PAGE_SIZE = 54;

    private static final class Session {
        final UUID owner;
        final int page;
        final SimpleContainer box = new SimpleContainer(PAGE_SIZE);
        int viewers;
        boolean dirty;

        Session(UUID owner, int page) {
            this.owner = owner;
            this.page = page;
        }
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** Donnees en cours d'ecriture (une reouverture immediate relit ces donnees, pas l'ancienne ligne en base). */
    private static final Map<String, String> PENDING_WRITES = new ConcurrentHashMap<>();

    private Enderchests() {}

    private static Db.Source db() {
        return Db.of("enderchests");
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Cmd.player(d, "enderchest", l -> Cmd.lit(l).executes(ctx -> Cmd.run(ctx, p -> open(p, p.getUUID(), p.getGameProfile().getName(), -1)))
                .then(argument("page", IntegerArgumentType.integer(1, 5)).executes(ctx -> Cmd.run(ctx, p ->
                        open(p, p.getUUID(), p.getGameProfile().getName(), IntegerArgumentType.getInteger(ctx, "page") - 1))))
                .then(argument("joueur", StringArgumentType.word()).requires(src -> Perms.admin(src, "endersee"))
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerNames(), b))
                        .executes(ctx -> Cmd.run(ctx, p -> openOther(p, StringArgumentType.getString(ctx, "joueur"), -1)))));
        Cmd.admin(d, "endersee", "endersee", l -> Cmd.lit(l).then(argument("joueur", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerNames(), b))
                .executes(ctx -> Cmd.run(ctx, p -> openOther(p, StringArgumentType.getString(ctx, "joueur"), -1)))
                .then(argument("page", IntegerArgumentType.integer(1, 5)).executes(ctx -> Cmd.run(ctx, p ->
                        openOther(p, StringArgumentType.getString(ctx, "joueur"), IntegerArgumentType.getInteger(ctx, "page") - 1))))));
    }

    public static int rows(ServerPlayer p) {
        return Math.max(1, Math.min(6, Perms.numeric(p, Perms.EC_ROWS, Cfg.general.enderchest.defaultRows, true)));
    }

    public static int pages(ServerPlayer p) {
        return Math.max(1, Math.min(5, Perms.numeric(p, Perms.EC_PAGES, Cfg.general.enderchest.defaultPages, true)));
    }

    private static void openOther(ServerPlayer staff, String name, int page) {
        UUID u = ChatFeatures.resolve(name);
        if (u == null) {
            Msg.send(staff, "general.player_not_found", "player", name);
            return;
        }
        open(staff, u, Players.name(u), page);
    }

    /**
     * Ouvre l'Enderchest de {@code owner} pour {@code viewer}. page = -1 : selecteur (si plusieurs pages) ou page 1.
     * Le staff (owner != viewer) voit toujours 6 lignes et 5 pages.
     */
    public static void open(ServerPlayer viewer, UUID owner, String ownerName, int page) {
        boolean self = viewer.getUUID().equals(owner);
        if (!self && !Perms.admin(viewer, "endersee")) {
            Msg.send(viewer, "general.no_permission");
            return;
        }
        int pages = self ? pages(viewer) : 5;
        int rows = self ? rows(viewer) : 6;
        if (self) importVanilla(viewer);
        if (page < 0) {
            if (pages > 1) {
                selector(viewer, owner, ownerName, pages, rows);
                return;
            }
            page = 0;
        }
        if (page >= pages) {
            Msg.send(viewer, "enderchest.page_locked", "page", page + 1, "max", pages);
            return;
        }
        Session s = session(owner, page);
        s.viewers++;
        final int pg = page;
        Container view = new SlicedContainer(s.box, rows * 9);
        String title = Msg.format(self ? "enderchest.title" : "enderchest.title_other", "page", page + 1, "pages", pages, "player", ownerName);
        viewer.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(EssentialsCommands.type(rows), id, inv, view, rows) {
            @Override
            public void removed(Player player) {
                super.removed(player);
                s.dirty = true;
                close(s);
            }
        }, TextUtil.legacy(title)));
        viewer.playNotifySound(SoundEvents.ENDER_CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1f);
        if (self && pages > 1) Msg.actionBar(viewer, "enderchest.page_hint", "page", pg + 1, "pages", pages);
    }

    private static void selector(ServerPlayer viewer, UUID owner, String ownerName, int pages, int rows) {
        Gui g = new Gui(1, Msg.format("enderchest.selector_title", "player", ownerName));
        g.fill(Gui.pane());
        int start = (9 - pages) / 2;
        for (int i = 0; i < pages; i++) {
            final int page = i;
            g.button(start + i, ItemBuilder.of(Items.ENDER_CHEST).count(i + 1).name(Msg.format("enderchest.selector_page", "page", i + 1))
                    .lore(Msg.format("enderchest.selector_lore", "rows", rows)).build(), pl -> {
                pl.closeContainer();
                fr.cobblepulse.utils.core.Scheduler.next(() -> open(pl, owner, ownerName, page));
            });
        }
        g.open(viewer);
    }

    private static String key(UUID owner, int page) {
        return owner + "#" + page;
    }

    private static Session session(UUID owner, int page) {
        return SESSIONS.computeIfAbsent(key(owner, page), k -> {
            Session s = new Session(owner, page);
            String data = load(owner, page);
            if (data != null) ItemSerial.decodeInto(data, s.box);
            return s;
        });
    }

    private static String load(UUID owner, int page) {
        String pending = PENDING_WRITES.get(key(owner, page));
        if (pending != null) return pending;
        try {
            return db().run(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT data FROM " + db().table("enderchests") + " WHERE uuid=? AND page=?")) {
                    ps.setString(1, owner.toString());
                    ps.setInt(2, page);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
        } catch (Exception e) {
            CobbleUtils.LOG.error("[CobblePulse-Utils] Lecture de l'Enderchest {} p{} : {}", owner, page, e.toString());
            return null;
        }
    }

    private static void close(Session s) {
        s.viewers = Math.max(0, s.viewers - 1);
        saveAsync(s);
        if (s.viewers == 0) SESSIONS.remove(key(s.owner, s.page));
    }

    private static void saveAsync(Session s) {
        String data = ItemSerial.encode(s.box);
        s.dirty = false;
        String k = key(s.owner, s.page);
        PENDING_WRITES.put(k, data);
        db().txAsync(c -> write(c, s.owner, s.page, data)).whenComplete((v, t) -> {
            if (t == null) PENDING_WRITES.remove(k, data);
            else CobbleUtils.LOG.error("[CobblePulse-Utils] Sauvegarde de l'Enderchest {} p{} impossible : {}", s.owner, s.page, t.toString());
        });
    }

    private static Void write(java.sql.Connection c, UUID owner, int page, String data) throws java.sql.SQLException {
        String sql = db().upsert("enderchests", new String[]{"uuid", "page", "data", "updated"}, new String[]{"uuid", "page"});
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, page);
            ps.setString(3, data);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
        return null;
    }

    /** Toutes les minutes : sauvegarde des pages ouvertes (protection contre les coupures). */
    public static void periodicSave() {
        for (Session s : SESSIONS.values()) saveAsync(s);
    }

    /** Arret du serveur : ecriture synchrone. */
    public static void saveAllSync() {
        for (Session s : SESSIONS.values()) {
            try {
                String data = ItemSerial.encode(s.box);
                db().tx(c -> write(c, s.owner, s.page, data));
            } catch (Exception e) {
                CobbleUtils.LOG.error("[CobblePulse-Utils] Sauvegarde finale de l'Enderchest {} : {}", s.owner, e.toString());
            }
        }
        SESSIONS.clear();
        for (Map.Entry<String, String> e : PENDING_WRITES.entrySet()) {
            String[] parts = e.getKey().split("#");
            try {
                db().tx(c -> write(c, UUID.fromString(parts[0]), Integer.parseInt(parts[1]), e.getValue()));
            } catch (Exception ex) {
                CobbleUtils.LOG.error("[CobblePulse-Utils] Ecriture en attente de l'Enderchest {} : {}", e.getKey(), ex.toString());
            }
        }
        PENDING_WRITES.clear();
    }

    /** Premier acces : le contenu du coffre de l'Ender vanilla est transfere dans la page 1. */
    private static void importVanilla(ServerPlayer p) {
        if (!Cfg.general.enderchest.importVanilla) return;
        PlayerData d = PlayerStore.get(p);
        if (d.ecImported) return;
        d.ecImported = true;
        PlayerStore.dirty(p.getUUID());
        PlayerEnderChestContainer vanilla = p.getEnderChestInventory();
        if (vanilla.isEmpty()) return;
        Session s = session(p.getUUID(), 0);
        int moved = 0;
        for (int i = 0; i < vanilla.getContainerSize(); i++) {
            ItemStack st = vanilla.getItem(i);
            if (st.isEmpty()) continue;
            for (int j = 0; j < PAGE_SIZE; j++) {
                if (s.box.getItem(j).isEmpty()) {
                    s.box.setItem(j, st.copy());
                    vanilla.setItem(i, ItemStack.EMPTY);
                    moved++;
                    break;
                }
            }
        }
        saveAsync(s);
        if (s.viewers == 0) SESSIONS.remove(key(p.getUUID(), 0));
        Players.saveNow(p);
        if (moved > 0) Msg.send(p, "enderchest.imported", "count", moved);
    }

    /** Auto-test : contenu d'une page (lecture directe). */
    public static List<ItemStack> peek(UUID owner, int page) {
        Session s = SESSIONS.get(key(owner, page));
        if (s != null) {
            List<ItemStack> l = new ArrayList<>();
            for (int i = 0; i < PAGE_SIZE; i++) l.add(s.box.getItem(i));
            return l;
        }
        String data = load(owner, page);
        return data == null ? new ArrayList<>() : ItemSerial.decode(data);
    }
}
