package fr.cobblepulse.utils.market;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.utils.config.Cfg;
import fr.cobblepulse.utils.core.ItemBuilder;
import fr.cobblepulse.utils.core.Msg;
import fr.cobblepulse.utils.core.Perms;
import fr.cobblepulse.utils.core.Scheduler;
import fr.cobblepulse.utils.core.TimeFmt;
import fr.cobblepulse.utils.gui.Gui;
import fr.cobblepulse.utils.hub.Hub;
import fr.cobblepulse.utils.pokemon.PokemonText;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Interface de l'Echange Miracle : equipe, confirmation, resultat et statistiques. */
public final class WonderTradeMenus {
    private WonderTradeMenus() {}

    public static void open(ServerPlayer p) {
        if (!WonderTrade.open() && !Perms.admin(p, "wondertrade")) {
            Msg.send(p, "wt.closed");
            return;
        }
        WonderTrade.stats(p.getUUID()).whenComplete((st, t) -> Scheduler.next(() -> {
            if (p.hasDisconnected()) return;
            Gui g = new Gui(3, Msg.format("wt.title"));
            g.fill(Gui.pane());
            long cd = WonderTrade.cooldownLeft(p);
            var party = PokemonText.party(p);
            for (int i = 0; i < 6; i++) {
                Pokemon pk = party.get(i);
                int slot = i;
                if (pk == null) {
                    g.set(10 + i, ItemBuilder.of(Items.LIGHT_GRAY_STAINED_GLASS_PANE).name(Msg.format("gts.empty_slot", "slot", i + 1)).build());
                    continue;
                }
                String why = WonderTrade.refuse(p, pk);
                g.button(10 + i, PokemonText.icon(pk, why == null ? Msg.format("wt.icon_click") : Msg.format(why, "level", Cfg.wt.minLevel)), pl -> {
                    if (why != null) {
                        Msg.send(pl, why, "level", Cfg.wt.minLevel);
                        return;
                    }
                    confirm(pl, slot, pk);
                });
            }
            g.set(4, ItemBuilder.of(Items.ENDER_EYE).name(Msg.format("wt.info_name"))
                    .lore(Msg.format("wt.info_lore", "pool", st == null ? "?" : st.poolSize(), "total", st == null ? "?" : st.totalTrades(),
                            "mine", st == null ? "?" : st.playerTrades(), "shinies", st == null ? "?" : st.playerShinies(),
                            "cooldown", cd > 0 ? TimeFmt.duration(cd) : Msg.format("wt.ready"))).build());
            g.button(18, ItemBuilder.of(Items.OAK_DOOR).name(Msg.format("general.back")).build(), Hub::open);
            g.open(p);
        }));
    }

    private static void confirm(ServerPlayer p, int slot, Pokemon pk) {
        Gui g = new Gui(3, Msg.format("wt.confirm_title"));
        g.fill(Gui.pane());
        g.set(13, PokemonText.icon(pk));
        g.button(11, ItemBuilder.of(Items.LIME_CONCRETE).name(Msg.format("wt.confirm")).lore(Msg.format("wt.confirm_lore")).build(), pl -> {
            fr.cobblepulse.utils.gui.Gui.close(pl);
            WonderTrade.trade(pl, slot, received -> {
                if (received == null) return;
                Gui r = new Gui(3, Msg.format("wt.result_title"));
                r.fill(Gui.pane());
                r.set(13, PokemonText.icon(received));
                r.button(22, ItemBuilder.of(Items.ARROW).name(Msg.format("wt.again")).build(), WonderTradeMenus::open);
                r.open(pl);
            });
        });
        g.button(15, ItemBuilder.of(Items.RED_CONCRETE).name(Msg.format("general.cancel")).build(), WonderTradeMenus::open);
        g.open(p);
    }
}
