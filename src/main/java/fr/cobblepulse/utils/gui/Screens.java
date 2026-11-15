package fr.cobblepulse.utils.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.Json;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TextUtil;
import fr.cobblepulse.utils.net.Net;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Affichage des menus {@link Gui} dans un ecran client CobblePulse (le mod est present cote client).
 * <p>Le serveur reste maitre : il envoie le contenu (objets encodes comme dans un vrai coffre, titre, barre d'actions),
 * le client renvoie les clics (gauche, droit, maj, milieu) qui sont verifies contre la session courante puis traites
 * par les memes actions que le menu coffre. Une modification du menu ouvert est renvoyee automatiquement.</p>
 */
public final class Screens {
    private record Session(int id, Gui gui) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger(1);
    /** Auto-tests : joueurs factices traites comme des clients moddes ; le dernier paquet est conserve. */
    private static final Set<UUID> CAPTURE = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, JsonObject> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_KIND = new ConcurrentHashMap<>();

    private Screens() {}

    public static boolean enabled(ServerPlayer p) {
        if (p == null) return false;
        if (CAPTURE.contains(p.getUUID())) return true;
        return (Cfg.general == null || Cfg.general.clientScreens) && Net.hasClient(p);
    }

    public static void show(ServerPlayer p, Gui gui) {
        Session old = SESSIONS.get(p.getUUID());
        if (old != null && old.gui != gui) release(p, old);
        // un coffre ouvert (enderchest, poubelle...) est ferme avant d'afficher l'ecran
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        Session s = old != null && old.gui == gui ? old : new Session(IDS.getAndIncrement(), gui);
        SESSIONS.put(p.getUUID(), s);
        gui.shownTo = p;
        send(p, "menu", encode(p, s));
    }

    /** Clic recu de l'ecran client ; ignore si l'ecran ne correspond plus au menu courant. */
    public static void click(ServerPlayer p, int id, int slot, String kind) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || s.id != id || slot < 0 || slot >= s.gui.size()) return;
        Gui.Click c = switch (kind == null ? "" : kind) {
            case "right" -> new Gui.Click(ClickType.PICKUP, 1);
            case "shift" -> new Gui.Click(ClickType.QUICK_MOVE, 0);
            case "middle" -> new Gui.Click(ClickType.CLONE, 2);
            default -> new Gui.Click(ClickType.PICKUP, 0);
        };
        s.gui.handleClick(p, slot, c);
    }

    /** Ecran ferme par le joueur (Echap). */
    public static void closedByClient(ServerPlayer p, int id) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || s.id != id) return;
        SESSIONS.remove(p.getUUID());
        release(p, s);
    }

    /** Fermeture demandee par le serveur (teleportation, commande...). */
    public static void close(ServerPlayer p) {
        Session s = SESSIONS.remove(p.getUUID());
        if (s == null) return;
        release(p, s);
        send(p, "menu_close", new JsonObject());
    }

    /** Oublie la session sans rien envoyer (un autre ecran la remplace : Hub, saisie validee, deconnexion). */
    public static void forget(ServerPlayer p) {
        if (p == null) return;
        Session s = SESSIONS.remove(p.getUUID());
        if (s != null) release(p, s);
    }

    public static void forget(UUID uuid) {
        Session s = SESSIONS.remove(uuid);
        if (s != null) s.gui.shownTo = null;
    }

    private static void release(ServerPlayer p, Session s) {
        s.gui.shownTo = null;
        if (s.gui.onClose != null) s.gui.onClose.accept(p);
    }

    /** Fenetre de saisie (prix, nom...) par-dessus l'ecran ; la reponse revient par {@link ChatInput#answer}. */
    public static void prompt(ServerPlayer p, String legacyText) {
        JsonObject o = new JsonObject();
        o.addProperty("text", Component.Serializer.toJson(TextUtil.legacy(legacyText), p.registryAccess()));
        send(p, "prompt", o);
    }

    /** Selecteur client : objet (tous les objets) ou Pokemon (createur de fiche). */
    public static void pick(ServerPlayer p, String kind, String legacyTitle, String initial) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("title", Component.Serializer.toJson(TextUtil.legacy(legacyTitle), p.registryAccess()));
        o.addProperty("value", initial == null ? "" : initial);
        send(p, "pick", o);
    }

    static void queueRefresh(Gui g) {
        if (g.resendQueued) return;
        g.resendQueued = true;
        Scheduler.next(() -> {
            g.resendQueued = false;
            ServerPlayer p = g.shownTo;
            if (p == null || p.hasDisconnected()) return;
            Session s = SESSIONS.get(p.getUUID());
            if (s == null || s.gui != g || p.containerMenu != p.inventoryMenu) return;
            send(p, "menu", encode(p, s));
        });
    }

    static JsonObject encode(ServerPlayer p, Session s) {
        Gui g = s.gui;
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id);
        o.addProperty("title", Component.Serializer.toJson(g.title, p.registryAccess()));
        o.addProperty("rows", g.rows);
        o.addProperty("list", g.list);
        // la derniere ligne d'un menu de 2 lignes ou plus est la barre d'actions (retour, pages, solde...)
        int barStart = g.rows >= 2 ? (g.rows - 1) * 9 : Integer.MAX_VALUE;
        int barEnd = g.rows * 9;
        JsonArray items = new JsonArray();
        for (int i = 0; i < g.size(); i++) {
            ItemStack st = g.container.getItem(i);
            if (st.isEmpty()) continue;
            boolean click = g.handlers.containsKey(i);
            if (!click && filler(st)) continue;
            JsonObject it = new JsonObject();
            it.addProperty("s", i);
            it.addProperty("i", encodeStack(p, st));
            if (click) it.addProperty("c", true);
            if (i >= barStart && i < barEnd) it.addProperty("bar", true);
            items.add(it);
        }
        o.add("items", items);
        return o;
    }

    /** Vitres de decoration des menus coffre : inutiles a l'ecran. */
    static boolean filler(ItemStack st) {
        String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return (path.endsWith("stained_glass_pane") || path.equals("glass_pane")) && st.getHoverName().getString().isBlank();
    }

    /** Encodage reseau exact (comme les cases d'un coffre) : composants, Pokemon, enchantements... */
    static String encodeStack(ServerPlayer p, ItemStack st) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), p.registryAccess());
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, st);
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return Base64.getEncoder().encodeToString(b);
        } finally {
            buf.release();
        }
    }

    private static void send(ServerPlayer p, String kind, JsonObject o) {
        if (CAPTURE.contains(p.getUUID())) {
            LAST.put(p.getUUID(), o);
            LAST_KIND.put(p.getUUID(), kind);
            return;
        }
        Net.send(p, kind, Json.COMPACT.toJson(o));
    }

    // ------------------------------------------------------------------ auto-tests

    public static void capture(UUID uuid, boolean on) {
        if (on) CAPTURE.add(uuid);
        else {
            CAPTURE.remove(uuid);
            LAST.remove(uuid);
            LAST_KIND.remove(uuid);
        }
    }

    public static JsonObject last(UUID uuid) {
        return LAST.get(uuid);
    }

    public static String lastKind(UUID uuid) {
        return LAST_KIND.get(uuid);
    }

    public static int sessionId(UUID uuid) {
        Session s = SESSIONS.get(uuid);
        return s == null ? -1 : s.id;
    }

    public static boolean hasSession(UUID uuid) {
        return SESSIONS.containsKey(uuid);
    }

    /** Noms des entrees affichees (hors vitres de decoration). */
    public static java.util.List<String> names(UUID uuid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Session s = SESSIONS.get(uuid);
        if (s == null) return out;
        for (int i = 0; i < s.gui.size(); i++) {
            ItemStack st = s.gui.container.getItem(i);
            if (st.isEmpty() || (!s.gui.handlers.containsKey(i) && filler(st))) continue;
            out.add(st.getHoverName().getString());
        }
        return out;
    }

    /** Case cliquable dont le nom contient le texte (-1 si absente). */
    public static int slotNamed(UUID uuid, String part) {
        Session s = SESSIONS.get(uuid);
        if (s == null) return -1;
        for (int i = 0; i < s.gui.size(); i++) {
            ItemStack st = s.gui.container.getItem(i);
            if (!st.isEmpty() && s.gui.handlers.containsKey(i) && st.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
                    .contains(part.toLowerCase(java.util.Locale.ROOT))) return i;
        }
        return -1;
    }

    public static String encodeForTest(ServerPlayer p, ItemStack st) {
        return encodeStack(p, st);
    }

    /** Decodage cote serveur d'une case encodee (auto-test de l'aller-retour). */
    public static ItemStack decodeStack(ServerPlayer p, String b64) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Base64.getDecoder().decode(b64)), p.registryAccess());
        try {
            return ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
