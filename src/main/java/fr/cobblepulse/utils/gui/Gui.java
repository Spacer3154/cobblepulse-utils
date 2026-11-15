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
 * Menu coffre pilote par le serveur. Aucun item ne peut etre deplace, retire ou jete :
 * chaque clic est intercepte et transforme en action.
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
    final SimpleContainer container;
    final Map<Integer, Handler> handlers = new HashMap<>();
    Consumer<ServerPlayer> onClose;

    public Gui(int rows, String legacyTitle) {
        this(rows, TextUtil.legacy(legacyTitle));
    }

    public Gui(int rows, Component title) {
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title;
        this.container = new SimpleContainer(this.rows * 9);
    }

    public int size() {
        return rows * 9;
    }

    public Gui set(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    public Gui set(int slot, ItemStack item, Handler handler) {
        if (slot < 0 || slot >= size()) return this;
        container.setItem(slot, item == null ? ItemStack.EMPTY : item);
        if (handler != null) handlers.put(slot, handler);
        else handlers.remove(slot);
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
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new LockedMenu(id, inv, this), title));
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
