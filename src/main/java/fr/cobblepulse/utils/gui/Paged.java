package fr.cobblepulse.utils.gui;

import fr.cobblepulse.utils.core.ItemBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;

/** Liste paginee generique (45 entrees par page + barre de navigation). */
public final class Paged {
    public record Entry(ItemStack item, Gui.Handler handler) {}

    private Paged() {}

    public static void open(ServerPlayer player, String title, List<Entry> entries, int page, Consumer<ServerPlayer> back) {
        open(player, title, entries, page, back, null);
    }

    public static void open(ServerPlayer player, String title, List<Entry> entries, int page, Consumer<ServerPlayer> back,
                            Consumer<Gui> decorate) {
        // ecran client : liste complete (defilement + recherche), coffre : 45 entrees par page
        boolean screen = Screens.enabled(player);
        int per = screen ? 400 : 45;
        int pages = Math.max(1, (entries.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Gui gui = new Gui(6, title + (pages > 1 ? " &8(" + (pg + 1) + "/" + pages + ")" : ""));
        gui.list(true);
        int onPage = Math.max(0, Math.min(per, entries.size() - pg * per));
        if (onPage > 45) gui.grow(54 + onPage - 45);
        for (int i = 0; i < onPage; i++) {
            Entry e = entries.get(pg * per + i);
            // les cases 45-53 restent la barre de navigation
            gui.set(i < 45 ? i : 54 + i - 45, e.item(), e.handler());
        }
        for (int i = 45; i < 54; i++) gui.set(i, Gui.pane());
        if (pg > 0) {
            gui.button(45, ItemBuilder.of(Items.ARROW).name("&ePage precedente").build(),
                    p -> open(p, title, entries, pg - 1, back, decorate));
        }
        if (pg < pages - 1) {
            gui.button(53, ItemBuilder.of(Items.ARROW).name("&ePage suivante").build(),
                    p -> open(p, title, entries, pg + 1, back, decorate));
        }
        if (back != null) {
            gui.button(49, ItemBuilder.of(Items.OAK_DOOR).name("&cRetour").build(), back);
        }
        if (decorate != null) decorate.accept(gui);
        gui.open(player);
    }
}
