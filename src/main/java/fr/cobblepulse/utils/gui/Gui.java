package fr.cobblepulse.utils.gui;

import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Menu pilote par le serveur. Avec le mod cote client, il s'affiche comme un ecran CobblePulse (cartes, barre d'actions,
 * recherche, saisie integree, voir {@link Screens}) ; sinon comme un coffre verrouille. Dans les deux cas, chaque clic
 * est transforme en action cote serveur et aucun objet ne peut etre deplace.
 */
public class Gui {
    public record Click(ClickType type, int button) {
        public boolean right() {
            return type == ClickType.PICKUP && button == 1;
        }

        public boolean left() {
            return type == ClickType.PICKUP && button == 0;
        }

        public boolean shift() {
            return type == ClickType.QUICK_MOVE;
        }
    }

    @FunctionalInterface
    public interface Handler {
        void click(ServerPlayer player, Click click);
    }

    final int rows;
    final Component title;
    SimpleContainer container;
    final Map<Integer, Handler> handlers = new HashMap<>();
    Consumer<ServerPlayer> onClose;
    /** Liste (menus pagines) : l'ecran client affiche une grille unique avec recherche. */
    boolean list;
    /** Joueur dont l'ecran client affiche ce menu (rafraichi a chaque modification). */
    ServerPlayer shownTo;
    boolean resendQueued;

    public Gui(int rows, String legacyTitle) {
        this(rows, TextUtil.legacy(legacyTitle));
    }

    public Gui(int rows, Component title) {
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title;
        this.container = new SimpleContainer(this.rows * 9);
    }

    public int size() {
        return container.getContainerSize();
    }

    /** Agrandit le menu au-dela de 54 cases (ecran client uniquement : listes completes sans pagination). */
    public Gui grow(int slots) {
        if (slots <= size()) return this;
        SimpleContainer c = new SimpleContainer(slots);
        for (int i = 0; i < container.getContainerSize(); i++) c.setItem(i, container.getItem(i));
        container = c;
        return this;
    }

    public Gui list(boolean list) {
        this.list = list;
        return this;
    }

    public Gui set(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    public Gui set(int slot, ItemStack item, Handler handler) {
        if (slot < 0 || slot >= size()) return this;
        container.setItem(slot, item == null ? ItemStack.EMPTY : item);
        if (handler != null) handlers.put(slot, handler);
        else handlers.remove(slot);
        if (shownTo != null) Screens.queueRefresh(this);
        return this;
    }

    /** Action simple (tout type de clic). */
    public Gui button(int slot, ItemStack item, Consumer<ServerPlayer> action) {
        return set(slot, item, (p, c) -> action.accept(p));
    }

    /** Action avec type de clic. */
    public Gui button(int slot, ItemStack item, Handler handler) {
        return set(slot, item, handler);
    }

    public ItemStack get(int slot) {
        return container.getItem(slot);
    }

    public Gui fill(ItemStack filler) {
        for (int i = 0; i < size(); i++) {
            if (container.getItem(i).isEmpty()) container.setItem(i, filler.copy());
        }
        return this;
    }

    public Gui border() {
        ItemStack pane = pane();
        for (int i = 0; i < size(); i++) {
            int r = i / 9;
            int c = i % 9;
            if ((r == 0 || r == rows - 1 || c == 0 || c == 8) && container.getItem(i).isEmpty()) container.setItem(i, pane.copy());
        }
        return this;
    }

    public Gui onClose(Consumer<ServerPlayer> c) {
        this.onClose = c;
        return this;
    }

    /** Cases interieures (hors bordure) d'un menu de {@code rows} lignes. */
    public static int[] innerSlots(int rows) {
        java.util.List<Integer> s = new java.util.ArrayList<>();
        for (int r = 1; r < rows - 1; r++) for (int c = 1; c < 8; c++) s.add(r * 9 + c);
        int[] out = new int[s.size()];
        for (int i = 0; i < out.length; i++) out[i] = s.get(i);
        return out;
    }

    public static ItemStack pane() {
        return ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name(" ").hideExtra().build();
    }

    public void open(ServerPlayer player) {
        if (Screens.enabled(player)) {
            Screens.show(player, this);
            return;
        }
        Screens.forget(player);
        if (size() > rows * 9) {
            SimpleContainer c = new SimpleContainer(rows * 9);
            for (int i = 0; i < rows * 9; i++) c.setItem(i, container.getItem(i));
            container = c;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new LockedMenu(id, inv, this), title));
    }

    /** Ferme le menu du joueur (coffre ou ecran client). */
    public static void close(ServerPlayer player) {
        player.closeContainer();
        Screens.close(player);
    }

    /** Ouvre au tick suivant (a utiliser depuis un clic pour eviter la reentrance). */
    public void openLater(ServerPlayer player) {
        Scheduler.next(() -> {
            if (!player.hasDisconnected()) open(player);
        });
    }

    void handleClick(ServerPlayer player, int slot, Click click) {
        Handler h = handlers.get(slot);
        if (h != null) Scheduler.next(() -> {
            if (!player.hasDisconnected()) h.click(player, click);
        });
    }
}
