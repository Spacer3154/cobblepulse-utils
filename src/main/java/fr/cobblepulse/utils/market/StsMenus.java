package fr.cobblepulse.utils.market;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.integration.Economy;
import fr.cobblepulse.utils.pokemon.PokemonText;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Interface du STS : equipe avec prix de rachat, confirmation, quota restant. */
public final class StsMenus {
    private StsMenus() {}

    public static void open(ServerPlayer p) {
        if (!Sts.open() && !Perms.admin(p, "sts")) {
            Msg.send(p, "sts.closed");
            return;
        }
        Sts.soldToday(p.getUUID()).whenComplete((count, t) -> Scheduler.next(() -> {
            if (p.hasDisconnected()) return;
            int max = Sts.limit(p);
            int left = Math.max(0, max - (count == null ? 0 : count));
            Gui g = new Gui(3, Msg.format("sts.title", "left", left, "max", max));
            g.fill(Gui.pane());
            var party = PokemonText.party(p);
            for (int i = 0; i < 6; i++) {
                Pokemon pk = party.get(i);
                int slot = i;
                if (pk == null) {
                    g.set(10 + i, ItemBuilder.of(Items.LIGHT_GRAY_STAINED_GLASS_PANE).name(Msg.format("gts.empty_slot", "slot", i + 1)).build());
                    continue;
                }
                long price = Sts.price(pk);
                g.button(10 + i, PokemonText.icon(pk, Msg.format("sts.icon_price", "money", Economy.format(price)), Msg.format("sts.icon_click")),
                        pl -> confirm(pl, slot, pk, price));
            }
            g.set(4, ItemBuilder.of(Items.HOPPER).name(Msg.format("sts.info_name")).lore(Msg.format("sts.info_lore", "left", left, "max", max)).build());
            g.button(18, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
            g.set(26, ItemBuilder.of(Items.SUNFLOWER).name(Msg.format("hub.balance", "money", Economy.format(Economy.balance(p.getUUID())))).build());
            g.open(p);
        }));
    }

    private static void confirm(ServerPlayer p, int slot, Pokemon pk, long price) {
        Gui g = new Gui(3, Msg.format("sts.confirm_title", "money", Economy.format(price)));
        g.fill(Gui.pane());
        g.set(13, PokemonText.icon(pk, Msg.format("sts.icon_price", "money", Economy.format(price))));
        g.button(11, ItemBuilder.of(Items.LIME_CONCRETE).name(Msg.format("sts.confirm_sell", "money", Economy.format(price))).build(), pl -> {
            pl.closeContainer();
            Sts.sell(pl, slot, ok -> open(pl));
        });
        g.button(15, ItemBuilder.of(Items.RED_CONCRETE).name(Msg.format("general.cancel")).build(), StsMenus::open);
        g.open(p);
    }
}
